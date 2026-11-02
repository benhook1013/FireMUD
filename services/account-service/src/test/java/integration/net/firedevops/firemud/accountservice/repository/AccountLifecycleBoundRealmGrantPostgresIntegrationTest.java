package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.ApplyResult;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.CurrentGrant;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.GrantRevisionConflictException;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.State;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec.Scope;
import net.firedevops.firemud.common.account.authority.RealmGrantAuthorityEventV1Codec;
import net.firedevops.firemud.common.operator.RealmGrantMutation;
import net.firedevops.firemud.common.operator.RealmGrantMutation.Operation;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical owner-storage proof using real Flyway/PostgreSQL and production guards.
 *
 * <p>Actors and fork expiries are explicitly controlled source inputs. This proves neither caller
 * authentication/authorization nor genuine Game Session lifecycle qualification, delivery or
 * ejection.
 */
class AccountLifecycleBoundRealmGrantPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final String HISTORY = "account_lifecycle_bound_realm_grant_revisions";
  private static final String CURRENT = "account_lifecycle_bound_realm_grants";

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void createExtendRevokeRegrantAndExactReplayRetainOriginalSemanticEvidence() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant requested =
        context
            .now()
            .plusSeconds(300)
            .with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
    RealmGrantMutation create = command(context.scope(), Operation.CREATE, null, requested);
    UUID createRequest = UUID.randomUUID();
    ApplyResult created = context.apply(createRequest, create, fork);
    assertThat(created.replayed()).isFalse();
    assertThat(created.receipt().grantRevision()).isEqualTo(1);
    assertThat(created.receipt().grantExpiresAt()).isEqualTo(requested);
    assertThat(created.receipt().canonicalPreimage()).containsExactly(create.canonicalPreimage());
    var wire = RealmGrantAuthorityEventV1Codec.verify(created.receipt().event().payload());
    assertThat(wire.eventDigest()).isEqualTo(created.receipt().event().eventDigest());
    assertThat(wire.eventId()).isEqualTo("realm-grant-event:v1:" + createRequest);
    assertThat(wire.canonicalPreimage()).containsExactly(created.receipt().eventPreimage());
    assertThat(wire.preimage().grant()).isEqualTo(context.scope());
    assertThat(wire.preimage().grantExpiresAt()).isEqualTo(requested);
    assertThat(wire.preimage().grantAuthorityGeneration())
        .isEqualTo(created.receipt().grantAuthorityGeneration());
    var extended =
        context.apply(
            UUID.randomUUID(),
            command(context.scope(), Operation.EXTEND, 1L, requested.plusSeconds(100)),
            fork);
    var revoked =
        context.apply(
            UUID.randomUUID(), command(context.scope(), Operation.REVOKE, 2L, null), fork);
    assertThat(revoked.receipt().state()).isEqualTo(State.REVOKED);
    assertThat(revoked.receipt().grantExpiresAt()).isEqualTo(extended.receipt().grantExpiresAt());
    assertThat(context.read(fork).active()).isFalse();
    var regranted =
        context.apply(
            UUID.randomUUID(),
            command(context.scope(), Operation.REGRANT, 3L, requested.plusSeconds(200)),
            fork);
    assertThat(regranted.receipt().grantRevision()).isEqualTo(4);
    assertThat(regranted.receipt().grantAuthorityGeneration())
        .isNotEqualTo(created.receipt().grantAuthorityGeneration());
    assertThat(context.read(fork).active()).isTrue();
    List<Map<String, Object>> beforeReplay = context.rows();
    // Even a later, shorter/expired fork input cannot reinterpret the frozen completed request.
    ApplyResult replay = context.apply(createRequest, create, context.now().minusSeconds(1));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.receipt()).isEqualTo(created.receipt());
    // A lost current source observation also cannot turn exact historical replay into a new write.
    assertThat(context.apply(createRequest, create, null).receipt()).isEqualTo(created.receipt());
    context.assertRowsUnchanged(beforeReplay);
    assertThat(context.read(fork).receipt().grantRevision()).isEqualTo(4);
    assertThat(context.count("account_authority_outbox_events")).isEqualTo(4);
    assertThat(context.count("account_audit_outbox")).isEqualTo(4);
  }

  @Test
  void changedRequestIntentAndActorConflictWithoutAnyStateResultOrEventChange() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry = context.now().plusSeconds(300);
    UUID request = UUID.randomUUID();
    RealmGrantMutation original = command(context.scope(), Operation.CREATE, null, expiry);
    context.apply(request, original, fork);
    List<RealmGrantMutation> changes =
        List.of(
            command(context.scope(), Operation.CREATE, null, expiry.plusNanos(1)),
            command(context.scope(), Operation.EXTEND, 1L, expiry.plusSeconds(1)),
            command(context.scope(), Operation.REGRANT, 1L, expiry.plusSeconds(1)),
            new RealmGrantMutation(
                "local", context.scope(), Operation.CREATE, null, expiry, "Changed audit reason"),
            command(
                new Scope(
                    context.scope().accountId(),
                    UUID.randomUUID(),
                    "world",
                    "realm",
                    context.scope().playtestLifecycleId()),
                Operation.CREATE,
                null,
                expiry),
            command(
                new Scope(
                    context.scope().accountId(),
                    context.scope().tenantId(),
                    "other-world",
                    "realm",
                    context.scope().playtestLifecycleId()),
                Operation.CREATE,
                null,
                expiry),
            command(
                new Scope(
                    context.scope().accountId(),
                    context.scope().tenantId(),
                    "world",
                    "other-realm",
                    context.scope().playtestLifecycleId()),
                Operation.CREATE,
                null,
                expiry),
            command(
                new Scope(
                    context.scope().accountId(),
                    context.scope().tenantId(),
                    "world",
                    "realm",
                    UUID.randomUUID()),
                Operation.CREATE,
                null,
                expiry));
    List<Map<String, Object>> before = context.rows();
    for (RealmGrantMutation changed : changes) {
      assertThatThrownBy(() -> context.apply(request, changed, fork))
          .isInstanceOf(IdempotencyConflictException.class);
      context.assertRowsUnchanged(before);
    }
    UUID otherControlledActor = context.newAccount();
    RealmGrantMutation changedAccount =
        command(
            new Scope(
                otherControlledActor,
                context.scope().tenantId(),
                "world",
                "realm",
                context.scope().playtestLifecycleId()),
            Operation.CREATE,
            null,
            expiry);
    assertThatThrownBy(() -> context.apply(request, changedAccount, fork))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            context
                                .repository()
                                .apply(request, otherControlledActor, original, fork)))
        .isInstanceOf(IdempotencyConflictException.class);
    context.assertRowsUnchanged(before);
    assertThat(context.count("account_authority_outbox_events")).isEqualTo(1);
    assertThat(context.count("account_audit_outbox")).isEqualTo(1);
  }

  @Test
  void failedCasAndRetainedTombstoneCannotResurrectAuthority() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry = context.now().plusSeconds(300);
    RealmGrantMutation create = command(context.scope(), Operation.CREATE, null, expiry);
    context.apply(UUID.randomUUID(), create, fork);
    context.apply(UUID.randomUUID(), command(context.scope(), Operation.REVOKE, 1L, null), fork);
    List<Map<String, Object>> before = context.rows();
    for (RealmGrantMutation rejected :
        List.of(
            create,
            command(context.scope(), Operation.EXTEND, 1L, expiry.plusSeconds(10)),
            command(context.scope(), Operation.EXTEND, 2L, expiry.plusSeconds(10)),
            command(context.scope(), Operation.REVOKE, 2L, null),
            command(context.scope(), Operation.REGRANT, 1L, expiry.plusSeconds(10)))) {
      assertThatThrownBy(() -> context.apply(UUID.randomUUID(), rejected, fork))
          .isInstanceOf(GrantRevisionConflictException.class);
      context.assertRowsUnchanged(before);
    }
    assertThat(context.read(fork).receipt().state()).isEqualTo(State.REVOKED);
    assertThat(context.count("account_authority_outbox_events")).isEqualTo(2);
    assertThat(context.count("account_audit_outbox")).isEqualTo(2);
  }

  @Test
  void twoWritersWithOneExpectedRevisionHaveOneWinnerAndOneNoMutationConflict() throws Exception {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry = context.now().plusSeconds(300);
    context.apply(
        UUID.randomUUID(), command(context.scope(), Operation.CREATE, null, expiry), fork);
    CountDownLatch bothReady = new CountDownLatch(2);
    CountDownLatch begin = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  concurrentWrite(
                      context,
                      command(context.scope(), Operation.EXTEND, 1L, expiry.plusSeconds(10)),
                      fork,
                      bothReady,
                      begin));
      var second =
          executor.submit(
              () ->
                  concurrentWrite(
                      context,
                      command(context.scope(), Operation.REVOKE, 1L, null),
                      fork,
                      bothReady,
                      begin));
      assertThat(bothReady.await(30, TimeUnit.SECONDS)).isTrue();
      begin.countDown();
      assertThat(List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("APPLIED", "GRANT_REVISION_CONFLICT");
      assertThat(context.read(fork).receipt().grantRevision()).isEqualTo(2);
      assertThat(context.count(HISTORY)).isEqualTo(2);
      assertThat(context.count("account_authority_outbox_events")).isEqualTo(2);
      assertThat(context.count("account_audit_outbox")).isEqualTo(2);
    } finally {
      begin.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void expiryForkShorteningAndForkExtensionUseRealDatabaseClockWithoutRewritingGrant() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry =
        context
            .now()
            .plusSeconds(300)
            .with(java.time.temporal.ChronoField.NANO_OF_SECOND, 999999999);
    context.apply(
        UUID.randomUUID(), command(context.scope(), Operation.CREATE, null, expiry), fork);
    assertThat(context.read(fork.plusSeconds(3600)).effectiveExpiresAt()).isEqualTo(expiry);
    Instant shortened = context.now().plusSeconds(10);
    assertThat(context.read(shortened).effectiveExpiresAt()).isEqualTo(shortened);
    assertThat(context.read(shortened).active()).isTrue();
    Instant expiredFork = context.now();
    assertThat(context.read(expiredFork).active()).isFalse();
    assertThat(context.read(expiredFork).effectiveExpiresAt()).isEqualTo(expiredFork);
    assertThat(context.read(fork).receipt().grantExpiresAt()).isEqualTo(expiry);
    List<Map<String, Object>> before = context.rows();
    assertThatThrownBy(
            () ->
                context.apply(
                    UUID.randomUUID(),
                    command(context.scope(), Operation.EXTEND, 1L, expiry.plusSeconds(1)),
                    expiredFork))
        .isInstanceOf(GrantRevisionConflictException.class);
    assertThatThrownBy(
            () ->
                context.apply(
                    UUID.randomUUID(),
                    command(context.scope(), Operation.EXTEND, 1L, fork.plusNanos(1)),
                    fork))
        .isInstanceOf(IllegalArgumentException.class);
    context.assertRowsUnchanged(before);
  }

  @Test
  void explicitGrantExpiryDeniesAtRealBoundaryAndDoesNotExtendWithFork() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant shortExpiry = context.now().plusSeconds(5);
    context.apply(
        UUID.randomUUID(), command(context.scope(), Operation.CREATE, null, shortExpiry), fork);
    assertThat(context.read(fork).active()).isTrue();
    awaitDatabaseBoundary(context.dsl(), shortExpiry);
    var expired = context.read(fork.plusSeconds(3600));
    assertThat(expired.observedAt()).isAfterOrEqualTo(shortExpiry);
    assertThat(expired.active()).isFalse();
    assertThat(expired.effectiveExpiresAt()).isEqualTo(shortExpiry);
    assertThat(expired.receipt().grantRevision()).isEqualTo(1);
    assertThat(context.count(HISTORY)).isEqualTo(1);
    assertThat(context.count("account_authority_outbox_events")).isEqualTo(1);
  }

  @Test
  void rollbackAndFinalCommitExpiryRefusalLeaveNoPartialStateReceiptAuditOrEvent()
      throws Exception {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry = context.now().plusSeconds(300);
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          context
                              .repository()
                              .apply(
                                  UUID.randomUUID(),
                                  context.controlledActor(),
                                  command(context.scope(), Operation.CREATE, null, expiry),
                                  fork);
                          throw new IllegalStateException("Controlled downstream owner refusal");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled downstream owner refusal");
    context.assertEmpty();
    UUID request = UUID.randomUUID();
    // The policy is genuinely short; no clock, timestamp, source or trigger is rewritten.
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          Instant shortExpiry = context.now().plusSeconds(5);
                          context
                              .repository()
                              .apply(
                                  request,
                                  context.controlledActor(),
                                  command(context.scope(), Operation.CREATE, null, shortExpiry),
                                  fork);
                          awaitDatabaseBoundary(context.dsl(), shortExpiry);
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("expired before final commit");
    context.assertEmpty();
    // A refused transaction did not reserve request identity or stream sequence.
    assertThat(
            context
                .apply(request, command(context.scope(), Operation.CREATE, null, expiry), fork)
                .receipt()
                .event()
                .outboxSequence())
        .isEqualTo(1);
  }

  @Test
  void directHistoryTombstoneHeadAndAuditIdentityViolationsAreRejectedByProductionGuards() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    context.apply(
        UUID.randomUUID(),
        command(context.scope(), Operation.CREATE, null, context.now().plusSeconds(300)),
        fork);
    context.apply(UUID.randomUUID(), command(context.scope(), Operation.REVOKE, 1L, null), fork);
    for (String sql :
        List.of(
            "UPDATE " + HISTORY + " SET state = 'ACTIVE' WHERE grant_revision = 2",
            "UPDATE "
                + HISTORY
                + " SET mutation_preimage = mutation_preimage WHERE grant_revision = 1",
            "DELETE FROM " + HISTORY + " WHERE grant_revision = 2",
            "TRUNCATE " + HISTORY + " CASCADE",
            "DELETE FROM " + CURRENT,
            "TRUNCATE " + CURRENT + " CASCADE",
            "UPDATE " + CURRENT + " SET grant_revision = 1",
            "UPDATE " + CURRENT + " SET grant_revision = 3",
            "UPDATE account_authority_outbox_events SET payload = payload",
            "UPDATE account_audit_outbox SET event_type = 'WRONG'")) {
      assertThatThrownBy(
              () ->
                  context.transaction().executeWithoutResult(status -> context.dsl().execute(sql)))
          .as(sql)
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(context.read(fork).receipt().grantRevision()).isEqualTo(2);
    assertThat(context.read(fork).active()).isFalse();
    assertThat(context.count(HISTORY)).isEqualTo(2);
    assertThat(context.count("account_authority_outbox_events")).isEqualTo(2);
  }

  @Test
  void detachedGenericOutboxEventCannotCommitWithoutItsExactGrantOwnerReceipt() {
    Context context = context();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          String stream =
                              GrantAuthorityScopeCodec.outboxStreamKey(
                                  context.scope(), GrantAuthorityScopeCodec.V1_BOUNDS);
                          new AccountAuthorityOutboxRepository(context.dsl())
                              .append(
                                  stream,
                                  UUID.randomUUID().toString(),
                                  "unqualified-event",
                                  "unqualified-digest",
                                  new byte[] {1});
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("exact atomic owner receipt");
    context.assertEmpty();
  }

  @Test
  void primitiveRequiresOwnerTransactionAndDoesNotReadLegacyGrantsOrInferMembership() {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    assertThatThrownBy(
            () ->
                context
                    .repository()
                    .apply(
                        UUID.randomUUID(),
                        context.controlledActor(),
                        command(
                            context.scope(),
                            Operation.CREATE,
                            null,
                            context.now().plusSeconds(300)),
                        fork))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
    assertThatThrownBy(() -> context.repository().readCurrent(context.scope(), fork))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
    context.assertEmpty();
    Optional<CurrentGrant> absent =
        context
            .transaction()
            .execute(status -> context.repository().readCurrent(context.scope(), fork));
    assertThat(absent).isEmpty();
  }

  private static String concurrentWrite(
      Context context,
      RealmGrantMutation mutation,
      Instant fork,
      CountDownLatch ready,
      CountDownLatch begin) {
    ready.countDown();
    try {
      if (!begin.await(30, TimeUnit.SECONDS))
        throw new AssertionError("Concurrent writer was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
    try {
      context.apply(UUID.randomUUID(), mutation, fork);
      return "APPLIED";
    } catch (GrantRevisionConflictException conflict) {
      return conflict.getMessage();
    }
  }

  private static void awaitDatabaseBoundary(DSLContext dsl, Instant boundary) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Instant now =
          Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
              .get("now", OffsetDateTime.class)
              .toInstant();
      if (!now.isBefore(boundary)) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    throw new AssertionError("Real database expiry boundary was not reached");
  }

  private static RealmGrantMutation command(
      Scope scope, Operation operation, Long expected, Instant expiry) {
    return new RealmGrantMutation(
        "local", scope, operation, expected, expiry, "Controlled owner-storage proof");
  }

  private Context context() {
    String schema = "lifecycle_grant_proof_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Context base =
        new Context(
            dsl,
            transaction,
            new AccountLifecycleBoundRealmGrantRepository(
                dsl, new AccountAuthorityOutboxRepository(dsl)),
            null,
            null);
    UUID target = base.newAccount();
    UUID actor = base.newAccount();
    return new Context(
        dsl,
        transaction,
        base.repository(),
        new Scope(target, UUID.randomUUID(), "world", "realm", UUID.randomUUID()),
        actor);
  }

  private record Context(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountLifecycleBoundRealmGrantRepository repository,
      Scope scope,
      UUID controlledActor) {
    UUID newAccount() {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("grant-" + suffix);
      account.setEmail("grant-" + suffix + "@example.test");
      account.setPasswordHash("controlled-proof-hash");
      account.setRole("player");
      transaction.executeWithoutResult(status -> new AccountRepository(dsl).save(account));
      return account.getAccountUuid();
    }

    Instant now() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
          .get("now", OffsetDateTime.class)
          .toInstant();
    }

    ApplyResult apply(UUID request, RealmGrantMutation mutation, Instant fork) {
      return Objects.requireNonNull(
          transaction.execute(
              status -> repository.apply(request, controlledActor, mutation, fork)));
    }

    AccountLifecycleBoundRealmGrantRepository.CurrentGrant read(Instant fork) {
      return Objects.requireNonNull(
          transaction.execute(status -> repository.readCurrent(scope, fork).orElseThrow()));
    }

    int count(String table) {
      String filter =
          table.startsWith("account_authority_outbox_") ? " WHERE outbox_stream_key = ?" : "";
      Object[] bindings =
          filter.isEmpty()
              ? new Object[0]
              : new Object[] {
                GrantAuthorityScopeCodec.outboxStreamKey(scope, GrantAuthorityScopeCodec.V1_BOUNDS)
              };
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) AS total FROM " + table + filter, bindings))
          .get("total", Integer.class);
    }

    List<Map<String, Object>> rows() {
      return dsl.fetch("SELECT * FROM " + HISTORY + " ORDER BY grant_revision").intoMaps();
    }

    void assertRowsUnchanged(List<Map<String, Object>> before) {
      assertThat(rows()).usingRecursiveComparison().isEqualTo(before);
    }

    void assertEmpty() {
      for (String table :
          List.of(
              HISTORY,
              CURRENT,
              "account_authority_outbox_streams",
              "account_authority_outbox_events",
              "account_audit_outbox")) assertThat(count(table)).as(table).isZero();
    }
  }
}
