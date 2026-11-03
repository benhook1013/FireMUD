package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.ApplyResult;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.realm.AccountCurrentLifecycleRealmGrantReadOwner;
import net.firedevops.firemud.accountservice.testsupport.CurrentPrivatePlaytestReadTransportFixture;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec;
import net.firedevops.firemud.common.operator.RealmGrantMutation;
import net.firedevops.firemud.common.operator.RealmGrantMutation.Operation;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Actual Account Flyway/SQL grant owner composed with the actual peer-qualified read client. Game
 * Session replies are explicitly controlled transport inputs behind loopback mTLS; this is not
 * actual GS source SQL, workflow authorization, admission or cross-owner commit protection.
 */
class AccountCurrentLifecycleRealmGrantReadPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void start() throws Exception {
    POSTGRES.start();
    pki = CurrentPrivatePlaytestReadTransportFixture.createPki(directory);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void readsActiveGrantLatestRevisionTombstoneAndDeliberateRegrantWithoutChangingHistory()
      throws Exception {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry =
        context
            .now()
            .plusSeconds(300)
            .with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
    UUID originalRequest = UUID.randomUUID();
    RealmGrantMutation create = context.command(Operation.CREATE, null, expiry);
    var original = context.apply(originalRequest, create, fork);
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      transport.current(
          Optional.of(context.controlledSource(context.scope().playtestLifecycleId(), fork)));
      var owner = context.owner(transport);
      var active = owner.read(context.scope());
      assertThat(active.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.ACTIVE_AS_OF_READ);
      assertThat(active.grant().orElseThrow().receipt()).isEqualTo(original.receipt());
      assertThat(active.grant().orElseThrow().effectiveExpiresAt()).isEqualTo(expiry);
      assertThat(active.admissionAllowed()).isFalse();
      context.apply(
          UUID.randomUUID(), context.command(Operation.EXTEND, 1L, expiry.plusSeconds(100)), fork);
      assertThat(owner.read(context.scope()).grant().orElseThrow().receipt().grantRevision())
          .isEqualTo(2);
      assertThat(context.apply(originalRequest, create, fork).receipt())
          .isEqualTo(original.receipt());
      var revoked =
          context.apply(UUID.randomUUID(), context.command(Operation.REVOKE, 2L, null), fork);
      var tombstone = owner.read(context.scope());
      assertThat(tombstone.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.REVOKED);
      assertThat(tombstone.grant().orElseThrow().receipt()).isEqualTo(revoked.receipt());
      assertThat(tombstone.grant().orElseThrow().active()).isFalse();
      context.apply(
          UUID.randomUUID(), context.command(Operation.REGRANT, 3L, expiry.plusSeconds(200)), fork);
      var beforeReads = context.history();
      var regranted = owner.read(context.scope());
      assertThat(regranted.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.ACTIVE_AS_OF_READ);
      assertThat(regranted.grant().orElseThrow().receipt().grantRevision()).isEqualTo(4);
      assertThat(regranted.admissionAllowed()).isFalse();
      assertThat(context.history()).usingRecursiveComparison().isEqualTo(beforeReads);
      assertThat(transport.finiteDeadlineObserved()).isTrue();
    }
  }

  @Test
  void actualGrantExpiryAndControlledForkShorteningUseAccountDatabaseClock() throws Exception {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    Instant expiry = context.now().plusSeconds(5);
    context.apply(UUID.randomUUID(), context.command(Operation.CREATE, null, expiry), fork);
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      var owner = context.owner(transport);
      Instant shortened = context.now().minusNanos(1);
      transport.current(
          Optional.of(context.controlledSource(context.scope().playtestLifecycleId(), shortened)));
      var shortenedResult = owner.read(context.scope());
      assertThat(shortenedResult.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.EXPIRED);
      assertThat(shortenedResult.grant().orElseThrow().effectiveExpiresAt()).isEqualTo(shortened);
      transport.current(
          Optional.of(context.controlledSource(context.scope().playtestLifecycleId(), fork)));
      assertThat(owner.read(context.scope()).status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.ACTIVE_AS_OF_READ);
      awaitExpiry(context, expiry);
      var expired = owner.read(context.scope());
      assertThat(expired.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.EXPIRED);
      assertThat(expired.grant().orElseThrow().observedAt()).isAfterOrEqualTo(expiry);
      assertThat(expired.grant().orElseThrow().effectiveExpiresAt()).isEqualTo(expiry);
      assertThat(expired.admissionAllowed()).isFalse();
      assertThat(context.history()).hasSize(1);
    }
  }

  @Test
  void absentGrantAbsentSourceWrongLifecycleAndTransportFailureNeverInferAuthority()
      throws Exception {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      var owner = context.owner(transport);
      assertThat(owner.read(context.scope()).status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.SOURCE_ABSENT);
      transport.current(
          Optional.of(context.controlledSource(context.scope().playtestLifecycleId(), fork)));
      assertThat(owner.read(context.scope()).status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.GRANT_ABSENT);
      context.apply(
          UUID.randomUUID(),
          context.command(Operation.CREATE, null, context.now().plusSeconds(300)),
          fork);
      var before = context.history();
      transport.current(Optional.of(context.controlledSource(UUID.randomUUID(), fork)));
      var wrongLifecycle = owner.read(context.scope());
      assertThat(wrongLifecycle.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.CURRENT_LIFECYCLE_MISMATCH);
      assertThat(wrongLifecycle.grant()).isEmpty();
      assertThat(wrongLifecycle.admissionAllowed()).isFalse();
      transport.respond(
          request -> {
            throw Status.UNAVAILABLE
                .withDescription("Controlled GS source outage")
                .asRuntimeException();
          });
      assertThatThrownBy(() -> owner.read(context.scope())).isInstanceOf(RuntimeException.class);
      assertThat(context.history()).usingRecursiveComparison().isEqualTo(before);
    }
  }

  @Test
  void sourceReplacementAfterReadDoesNotTurnAsOfObservationIntoAContinuingHold() throws Exception {
    Context context = context();
    Instant fork = context.now().plusSeconds(3600);
    context.apply(
        UUID.randomUUID(),
        context.command(Operation.CREATE, null, context.now().plusSeconds(300)),
        fork);
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      var original = context.controlledSource(context.scope().playtestLifecycleId(), fork);
      var replacement =
          CurrentPrivatePlaytestReadTransportFixture.snapshot(
              original.scope(), UUID.randomUUID(), fork, context.now(), 2L);
      transport.respond(
          request -> {
            var response =
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(
                    request, Optional.of(original));
            transport.current(Optional.of(replacement));
            return response;
          });
      var owner = context.owner(transport);
      var asOf = owner.read(context.scope());
      assertThat(asOf.status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.ACTIVE_AS_OF_READ);
      assertThat(asOf.lifecycleSource().evidence().current()).contains(original);
      assertThat(asOf.admissionAllowed()).isFalse();
      assertThat(owner.read(context.scope()).status())
          .isEqualTo(AccountCurrentLifecycleRealmGrantReadOwner.Status.CURRENT_LIFECYCLE_MISMATCH);
      assertThat(context.history()).hasSize(1);
    }
  }

  private static void awaitExpiry(Context context, Instant expiry) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (!context.now().isBefore(expiry)) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    throw new AssertionError("Real Account database clock did not reach grant expiry");
  }

  private Context context() {
    String schema = "qualified_grant_read_" + UUID.randomUUID().toString().replace("-", "");
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
    var manager = new DataSourceTransactionManager(dataSource);
    var transaction = new TransactionTemplate(manager);
    UUID target = account(dsl, transaction);
    UUID controlledGrantor = account(dsl, transaction);
    return new Context(
        dsl,
        manager,
        transaction,
        new AccountLifecycleBoundRealmGrantRepository(
            dsl, new AccountAuthorityOutboxRepository(dsl)),
        new Scope(target, UUID.randomUUID(), "world", "realm", UUID.randomUUID()),
        controlledGrantor);
  }

  private static UUID account(DSLContext dsl, TransactionTemplate transaction) {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString();
    account.setUsername("read-grant-" + suffix);
    account.setEmail("qualified-grant-" + suffix + "@example.test");
    account.setPasswordHash("controlled-storage-proof");
    account.setRole("player");
    transaction.executeWithoutResult(status -> new AccountRepository(dsl).save(account));
    return account.getAccountUuid();
  }

  private record Context(
      DSLContext dsl,
      DataSourceTransactionManager manager,
      TransactionTemplate transaction,
      AccountLifecycleBoundRealmGrantRepository grants,
      Scope scope,
      UUID controlledGrantor) {
    Instant now() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
          .get("now", OffsetDateTime.class)
          .toInstant();
    }

    RealmGrantMutation command(Operation operation, Long expected, Instant expiry) {
      return new RealmGrantMutation(
          CurrentPrivatePlaytestReadTransportFixture.NAMESPACE,
          scope,
          operation,
          expected,
          expiry,
          "Controlled Account storage source inputs");
    }

    ApplyResult apply(UUID id, RealmGrantMutation command, Instant fork) {
      return Objects.requireNonNull(
          transaction.execute(status -> grants.apply(id, controlledGrantor, command, fork)));
    }

    CurrentPrivatePlaytestLifecycleReadEvidence.Snapshot controlledSource(
        UUID lifecycle, Instant expiry) {
      var selector =
          new CurrentPrivatePlaytestLifecycleReadEvidence.Scope(
              CurrentPrivatePlaytestReadTransportFixture.NAMESPACE,
              scope.tenantId(),
              scope.worldSlug(),
              scope.realmSlug());
      return CurrentPrivatePlaytestReadTransportFixture.snapshot(
          selector, lifecycle, expiry, now());
    }

    AccountCurrentLifecycleRealmGrantReadOwner owner(
        CurrentPrivatePlaytestReadTransportFixture transport) {
      return new AccountCurrentLifecycleRealmGrantReadOwner(
          transport.client(),
          grants,
          manager,
          CurrentPrivatePlaytestReadTransportFixture.NAMESPACE);
    }

    List<Map<String, Object>> history() {
      return dsl.fetch(
              "SELECT * FROM account_lifecycle_bound_realm_grant_revisions ORDER BY grant_revision")
          .intoMaps();
    }
  }
}
