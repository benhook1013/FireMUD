package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

class AccountFirstJoinAuthorityStorageIntegrationTest {
  private static final String SCHEMA_PREFIX = "first_join_authority_storage_proof";
  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();
  private final Set<String> schemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(postgres.dataSource());
    for (String schema : schemas) {
      if (!schema.startsWith(SCHEMA_PREFIX + "_") || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    schemas.clear();
  }

  @Test
  void appendsContiguousPerStreamAndReplaysOnlyExactRequestEvidence() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());
    String unrelated = "account:auth-authority:v1:tenant/" + UUID.randomUUID();

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream))).isEmpty();

    Event first =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream, "request-1", "event-1", "canonical-digest-1", new byte[] {1, 2}));
    Event exactReplay =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream, "request-1", "event-1", "canonical-digest-1", new byte[] {1, 2}));
    assertThat(exactReplay).isEqualTo(first);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.append(
                            stream, "request-1", "event-1", "different-digest", new byte[] {1, 2})))
        .isInstanceOf(IdempotencyConflictException.class);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.append(
                            stream,
                            "request-1",
                            "event-1",
                            "canonical-digest-1",
                            new byte[] {1, 9})))
        .isInstanceOf(IdempotencyConflictException.class);

    Event second =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream, "request-2", "event-2", "canonical-digest-2", new byte[] {3, 4}));
    Event otherStreamFirst =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    unrelated, "request-1", "event-1", "canonical-digest-1", new byte[] {1, 2}));
    Optional<Checkpoint> checkpoint =
        inTransaction(transaction, () -> repository.readCheckpoint(stream));

    assertThat(first.outboxSequence()).isEqualTo(1L);
    assertThat(second.outboxSequence()).isEqualTo(2L);
    assertThat(otherStreamFirst.outboxSequence()).isEqualTo(1L);
    assertThat(checkpoint).contains(new Checkpoint(stream, 2L, "event-2", "canonical-digest-2"));
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 1L))).contains(first);
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 2L))).contains(second);
  }

  @Test
  void v38DoesNotBackfillRetainedMembershipAndCanonicalRoleSnapshotRequiresExactSource() {
    TestContext context = newTestContextBeforeV38();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountTenantMembershipRoleSnapshotRepository roleSnapshots =
        new AccountTenantMembershipRoleSnapshotRepository(dsl);
    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(dsl, "prod");
    AccountRepository accounts = new AccountRepository(dsl);
    AccountMembershipPairAuthorityRepository pairAuthority =
        new AccountMembershipPairAuthorityRepository(dsl);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(dsl, accounts, freshTenants, pairAuthority);

    org.jooq.Record accountRow =
        dsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash, role) VALUES (?, ?, ?, ?) "
                + "RETURNING id, account_uuid",
            "authority-" + UUID.randomUUID(),
            "authority-storage-" + UUID.randomUUID() + "@example.test",
            "test-hash",
            "platformAdmin");
    Account account =
        accounts
            .findByAccountUuid(Objects.requireNonNull(accountRow).get("account_uuid", UUID.class))
            .orElseThrow();
    long accountId = Objects.requireNonNull(account.getId());
    long retainedTenantId = 7101L;
    long retainedMembershipId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                        + "RETURNING id",
                    accountId,
                    retainedTenantId)
                .fetchOne(0, Long.class));
    migrateToLatest(context);

    org.jooq.Record retainedReadback =
        dsl.fetchOne(
            "SELECT tenant_id, tenant_uuid, lifecycle_state, membership_version, "
                + "authority_provenance FROM account_tenant_membership WHERE id = ?",
            retainedMembershipId);
    assertThat(Objects.requireNonNull(retainedReadback).get("tenant_id", Long.class))
        .isEqualTo(retainedTenantId);
    assertThat(retainedReadback.get("tenant_uuid", UUID.class)).isNull();
    assertThat(retainedReadback.get("lifecycle_state", String.class))
        .isEqualTo("LEGACY_UNVERIFIED");
    assertThat(retainedReadback.get("membership_version", Long.class)).isEqualTo(1L);
    assertThat(retainedReadback.get("authority_provenance", String.class))
        .isEqualTo("LEGACY_UNVERIFIED");

    assertThat(
            inTransaction(
                transaction,
                () ->
                    roleSnapshots.findForUpdate(
                        accountId, retainedTenantId, retainedMembershipId, 1L)))
        .isEmpty();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_membership_role_snapshots "
                        + "WHERE membership_id = ?",
                    retainedMembershipId)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery("SELECT count(*) FROM account_authority_outbox_streams")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery("SELECT count(*) FROM account_authority_outbox_events")
                .fetchOne(0, Long.class))
        .isZero();

    UUID tenantUuid = UUID.fromString("f8871fb0-7810-4b72-bb13-09e29a3509f2");
    FreshTenantCreationEvidence tenantSource = freshTenantEvidence(tenantUuid);
    transaction.executeWithoutResult(status -> freshTenants.importVerified(tenantSource));
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantSource.operationId(),
            tenantSource.evidenceDigest());

    Long canonicalMembershipId =
        transaction.execute(
            status -> {
              AccountTenantMembership pendingMembership =
                  memberships.createFreshMembershipForJoin(account.getAccountUuid(), tenantUuid);
              long membershipId = Objects.requireNonNull(pendingMembership.getId());
              assertThat(
                      roleSnapshots.findForCanonicalUpdate(
                          account.getAccountUuid(),
                          tenantUuid,
                          provenance,
                          membershipId,
                          pendingMembership.getMembershipVersion()))
                  .isEmpty();

              AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot written =
                  roleSnapshots.replaceCanonical(
                      pendingMembership,
                      account.getAccountUuid(),
                      tenantUuid,
                      provenance,
                      pendingMembership.getMembershipVersion(),
                      List.of());
              assertThat(written.roles()).isEmpty();
              assertThat(written.accountUuid()).isEqualTo(account.getAccountUuid());
              assertThat(written.tenantUuid()).isEqualTo(tenantUuid);
              assertThat(written.tenantId()).isNull();
              assertThat(written.tenantProvenance()).isEqualTo(provenance);

              VerifiedTenantProvenance changedProvenance =
                  new VerifiedTenantProvenance(
                      null,
                      TenantProvenanceKind.FRESH_GAME_DESIGN,
                      UUID.randomUUID(),
                      tenantSource.evidenceDigest());
              assertThatThrownBy(
                      () ->
                          roleSnapshots.findForCanonicalUpdate(
                              account.getAccountUuid(),
                              tenantUuid,
                              changedProvenance,
                              membershipId,
                              pendingMembership.getMembershipVersion()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("differs from exact UUID identity or stored provenance");
              assertThatThrownBy(
                      () ->
                          roleSnapshots.findForCanonicalUpdate(
                              account.getAccountUuid(),
                              tenantUuid,
                              provenance,
                              membershipId,
                              pendingMembership.getMembershipVersion() + 1L))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("membership version is missing or mismatched");
              assertThat(
                      roleSnapshots.findForCanonicalUpdate(
                          account.getAccountUuid(),
                          tenantUuid,
                          provenance,
                          membershipId,
                          pendingMembership.getMembershipVersion()))
                  .contains(written);

              assertThatThrownBy(
                      () -> memberships.findFreshMembership(account.getAccountUuid(), tenantUuid))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no positive authority event");
              status.setRollbackOnly();
              return membershipId;
            });

    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_uuid = ?",
                    accountId,
                    tenantUuid)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_membership_role_snapshots "
                        + "WHERE membership_id = ?",
                    canonicalMembershipId)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_membership_role_snapshot_roles "
                        + "WHERE membership_id = ?",
                    canonicalMembershipId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void missingHeadWithNoExactHistoryIsEmptyDespiteAnotherStreamsRetainedEvent() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    DSLContext dsl = context.dsl();
    String missingStream = membershipStream(UUID.randomUUID(), UUID.randomUUID());
    String retainedStream = "account:auth-authority:v1:tenant/" + UUID.randomUUID();
    byte[] retainedPayload = new byte[] {4, 5, 6};

    Event retained =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    retainedStream,
                    "retained-request",
                    "retained-event",
                    "retained-digest",
                    retainedPayload));

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(missingStream)))
        .isEmpty();

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(retainedStream)))
        .contains(new Checkpoint(retainedStream, 1L, "retained-event", "retained-digest"));
    Event retainedAfterRead =
        inTransaction(transaction, () -> repository.findEvent(retainedStream, 1L)).orElseThrow();
    assertThat(retainedAfterRead).isEqualTo(retained);
    assertThat(retainedAfterRead.eventId()).isEqualTo(retained.eventId());
    assertThat(retainedAfterRead.eventDigest()).isEqualTo(retained.eventDigest());
    assertThat(retainedAfterRead.payload()).containsExactly(retainedPayload);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    missingStream)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    missingStream)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    retainedStream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void databaseRejectsDeletingAHeadWithRetainedHistoryWithoutChangingEvidence() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    DSLContext dsl = context.dsl();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());
    byte[] retainedPayload = new byte[] {7, 8, 9};
    Event retained =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream,
                    "retained-request",
                    "retained-event",
                    "retained-digest",
                    retainedPayload));

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> {
                      dsl.execute(
                          "DELETE FROM account_authority_outbox_streams "
                              + "WHERE outbox_stream_key = ?",
                          stream);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Account authority outbox stream history cannot be deleted");

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(new Checkpoint(stream, 1L, retained.eventId(), retained.eventDigest()));
    Event retainedAfterDelete =
        inTransaction(transaction, () -> repository.findEvent(stream, 1L)).orElseThrow();
    assertThat(retainedAfterDelete).isEqualTo(retained);
    assertThat(retainedAfterDelete.eventId()).isEqualTo(retained.eventId());
    assertThat(retainedAfterDelete.eventDigest()).isEqualTo(retained.eventDigest());
    assertThat(retainedAfterDelete.payload()).containsExactly(retainedPayload);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void databaseRejectsTruncatingOutboxHistoryAndPreservesEventsAndCheckpoint() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    DSLContext dsl = context.dsl();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());
    byte[] firstPayload = new byte[] {11, 12, 13};
    byte[] secondPayload = new byte[] {21, 22, 23};
    Event first =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream,
                    "truncate-request-1",
                    "truncate-event-1",
                    "truncate-digest-1",
                    firstPayload));
    Event second =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream,
                    "truncate-request-2",
                    "truncate-event-2",
                    "truncate-digest-2",
                    secondPayload));
    Checkpoint retainedCheckpoint =
        new Checkpoint(stream, 2L, second.eventId(), second.eventDigest());

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(retainedCheckpoint);
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 1L))).contains(first);
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 2L))).contains(second);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> {
                      dsl.execute("TRUNCATE TABLE account_authority_outbox_events CASCADE");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Account authority outbox history cannot be truncated");

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> {
                      dsl.execute("TRUNCATE TABLE account_authority_outbox_streams CASCADE");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Account authority outbox history cannot be truncated");

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(retainedCheckpoint);
    Event firstAfterTruncate =
        inTransaction(transaction, () -> repository.findEvent(stream, 1L)).orElseThrow();
    assertThat(firstAfterTruncate.outboxSequence()).isEqualTo(first.outboxSequence());
    assertThat(firstAfterTruncate.requestId()).isEqualTo(first.requestId());
    assertThat(firstAfterTruncate.eventId()).isEqualTo(first.eventId());
    assertThat(firstAfterTruncate.eventDigest()).isEqualTo(first.eventDigest());
    assertThat(firstAfterTruncate.payload()).containsExactly(firstPayload);
    Event secondAfterTruncate =
        inTransaction(transaction, () -> repository.findEvent(stream, 2L)).orElseThrow();
    assertThat(secondAfterTruncate.outboxSequence()).isEqualTo(second.outboxSequence());
    assertThat(secondAfterTruncate.requestId()).isEqualTo(second.requestId());
    assertThat(secondAfterTruncate.eventId()).isEqualTo(second.eventId());
    assertThat(secondAfterTruncate.eventDigest()).isEqualTo(second.eventDigest());
    assertThat(secondAfterTruncate.payload()).containsExactly(secondPayload);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
  }

  @Test
  void callerRollbackRemovesAppendAndConcurrentExactRetriesShareOneSequence() throws Exception {
    // This is a passive outbox storage proof. Later migrations require owner source rows for
    // issuer/Account streams, which is outside this fixture's storage-only boundary.
    TestContext context = newTestContextAtV39();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    String rollbackStream = "account:auth-authority:v1:account/" + UUID.randomUUID();

    transaction.execute(
        status -> {
          repository.append(
              rollbackStream, "rollback-request", "rollback-event", "digest", new byte[] {9});
          status.setRollbackOnly();
          return null;
        });
    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(rollbackStream)))
        .isEmpty();

    String concurrentStream = "account:auth-authority:v1:issuer/" + UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> concurrentAppend(repository, transaction, concurrentStream, ready, start));
      var second =
          executor.submit(
              () -> concurrentAppend(repository, transaction, concurrentStream, ready, start));
      ready.await();
      start.countDown();
      List<Event> events = List.of(first.get(), second.get());

      assertThat(events).allMatch(event -> event.outboxSequence() == 1L);
      assertThat(events).extracting(Event::eventId).containsOnly("event-1");
      assertThat(inTransaction(transaction, () -> repository.readCheckpoint(concurrentStream)))
          .contains(new Checkpoint(concurrentStream, 1L, "event-1", "digest-1"));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void evidenceFactoryFailureRollsBackStreamCreationAndDoesNotAdvanceHead() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.append(
                            stream,
                            "factory-failure-request",
                            sequence -> {
                              assertThat(sequence).isEqualTo(1L);
                              throw new IllegalStateException("producer failed");
                            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("producer failed");

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream))).isEmpty();
  }

  @Test
  void exhaustedSequenceRejectsAppendWithoutChangingEventCheckpointOrCallerSentinel() {
    // The synthetic owner stream is seeded only to exercise storage overflow behavior.
    TestContext context = newTestContextAtV39();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    DSLContext dsl = context.dsl();
    String stream = "account:auth-authority:v1:account/" + UUID.randomUUID();
    byte[] originalPayload = new byte[] {7, 8, 9};
    Event originalEvent =
        new Event(
            stream,
            "exhaustion-seed-request",
            Long.MAX_VALUE,
            "exhaustion-seed-event",
            "exhaustion-seed-digest",
            originalPayload);

    dsl.execute(
        "CREATE TABLE authority_overflow_sentinel "
            + "(sentinel_id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
    dsl.execute("INSERT INTO authority_overflow_sentinel (sentinel_id, value) VALUES (1, 0)");
    // Seed a consistent exhausted stream; bypass only its contiguous-insert guard for this setup.
    dsl.execute(
        "ALTER TABLE account_authority_outbox_events "
            + "DISABLE TRIGGER account_authority_outbox_event_insert");
    try {
      inTransaction(
          transaction,
          () -> {
            dsl.execute(
                "INSERT INTO account_authority_outbox_streams "
                    + "(outbox_stream_key, last_sequence) VALUES (?, ?)",
                stream,
                Long.MAX_VALUE);
            dsl.execute(
                "INSERT INTO account_authority_outbox_events "
                    + "(outbox_stream_key, outbox_sequence, request_id, event_id, "
                    + "event_digest, payload) VALUES (?, ?, ?, ?, ?, ?)",
                stream,
                Long.MAX_VALUE,
                originalEvent.requestId(),
                originalEvent.eventId(),
                originalEvent.eventDigest(),
                originalPayload);
            return null;
          });
    } finally {
      dsl.execute(
          "ALTER TABLE account_authority_outbox_events "
              + "ENABLE TRIGGER account_authority_outbox_event_insert");
    }

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(
            new Checkpoint(
                stream, Long.MAX_VALUE, "exhaustion-seed-event", "exhaustion-seed-digest"));
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, Long.MAX_VALUE)))
        .contains(originalEvent);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> {
                      dsl.execute(
                          "UPDATE authority_overflow_sentinel SET value = value + 1 "
                              + "WHERE sentinel_id = 1");
                      return repository.append(
                          stream,
                          "exhaustion-attempt-request",
                          "exhaustion-attempt-event",
                          "exhaustion-attempt-digest",
                          new byte[] {1, 2, 3});
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account authority outbox sequence is exhausted");

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(
            new Checkpoint(
                stream, Long.MAX_VALUE, "exhaustion-seed-event", "exhaustion-seed-digest"));
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, Long.MAX_VALUE)))
        .contains(originalEvent);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(
            dsl.resultQuery("SELECT value FROM authority_overflow_sentinel WHERE sentinel_id = 1")
                .fetchOne(0, Integer.class))
        .isZero();
  }

  private Event concurrentAppend(
      AccountAuthorityOutboxRepository repository,
      TransactionTemplate transaction,
      String stream,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return inTransaction(
        transaction,
        () -> repository.append(stream, "same-request", "event-1", "digest-1", new byte[] {1}));
  }

  private TestContext newTestContext() {
    return newTestContextAt(null);
  }

  private TestContext newTestContextBeforeV38() {
    return newTestContextAt("37");
  }

  private TestContext newTestContextAtV39() {
    return newTestContextAt("39");
  }

  private TestContext newTestContextAt(String targetVersion) {
    String schema =
        SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    schemas.add(schema);
    var dataSource = postgres.dataSource(schema);
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      configuration.target(org.flywaydb.core.api.MigrationVersion.fromVersion(targetVersion));
    }
    configuration.load().migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        new AccountAuthorityOutboxRepository(dsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        dsl,
        dataSource,
        schema);
  }

  private void migrateToLatest(TestContext context) {
    Flyway.configure()
        .dataSource(context.dataSource())
        .schemas(context.schema())
        .defaultSchema(context.schema())
        .placeholders(Map.of("serviceSchema", context.schema()))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private String membershipStream(UUID accountId, UUID tenantId) {
    return "account:auth-authority:v1:membership/" + accountId + "/" + tenantId;
  }

  private FreshTenantCreationEvidence freshTenantEvidence(UUID tenantId) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String requestDigest = "sha256:" + "a".repeat(64);
    String sourceTenantKey = "fj-" + UUID.randomUUID().toString().replace("-", "");
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            tenantId,
            7001L,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        tenantId,
        7001L,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Authority outbox concurrency proof was interrupted", interrupted);
    }
  }

  private record TestContext(
      AccountAuthorityOutboxRepository repository,
      TransactionTemplate transaction,
      DSLContext dsl,
      DataSource dataSource,
      String schema) {}
}
