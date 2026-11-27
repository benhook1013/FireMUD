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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
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
  private static final long CONCURRENCY_TIMEOUT_SECONDS = 30L;
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
    TestContext context = newTestContextAtV39();
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
    // Read only the columns owned by this historical migration boundary. The generated
    // current AccountRepository includes later columns that do not exist before V38.
    UUID accountUuid = Objects.requireNonNull(accountRow).get("account_uuid", UUID.class);
    long accountId = accountRow.get("id", Long.class);
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

    Account account = accounts.findByAccountUuid(accountUuid).orElseThrow();
    assertThat(account.getId()).isEqualTo(accountId);
    assertThat(account.getAccountUuid()).isEqualTo(accountUuid);

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

    Account freshAccount = new Account();
    freshAccount.setUsername("first-join");
    freshAccount.setEmail("first-join@example.test");
    freshAccount.setPasswordHash("first-join-test-hash");
    inTransaction(transaction, () -> accounts.save(freshAccount));
    UUID freshAccountUuid = freshAccount.getAccountUuid();
    UUID tenantUuid = UUID.fromString("f8871fb0-7810-4b72-bb13-09e29a3509f2");
    FreshTenantCreationEvidence tenantSource = freshTenantEvidence(tenantUuid);
    transaction.executeWithoutResult(
        status -> {
          freshTenants.importVerified(tenantSource);
          new AccountAuthorityGenerationRepository(dsl).initializeTenantIfAbsent(tenantUuid);
        });
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
                  memberships.createFreshMembershipForJoin(freshAccountUuid, tenantUuid);
              long membershipId = Objects.requireNonNull(pendingMembership.getId());
              assertThat(
                      roleSnapshots.findForCanonicalUpdate(
                          freshAccountUuid,
                          tenantUuid,
                          provenance,
                          membershipId,
                          pendingMembership.getMembershipVersion()))
                  .isEmpty();

              AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot written =
                  roleSnapshots.replaceCanonical(
                      pendingMembership,
                      freshAccountUuid,
                      tenantUuid,
                      provenance,
                      pendingMembership.getMembershipVersion(),
                      List.of());
              assertThat(written.roles()).isEmpty();
              assertThat(written.accountUuid()).isEqualTo(freshAccountUuid);
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
                              freshAccountUuid,
                              tenantUuid,
                              changedProvenance,
                              membershipId,
                              pendingMembership.getMembershipVersion()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("differs from exact UUID identity or stored provenance");
              assertThatThrownBy(
                      () ->
                          roleSnapshots.findForCanonicalUpdate(
                              freshAccountUuid,
                              tenantUuid,
                              provenance,
                              membershipId,
                              pendingMembership.getMembershipVersion() + 1L))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("membership version is missing or mismatched");
              assertThat(
                      roleSnapshots.findForCanonicalUpdate(
                          freshAccountUuid,
                          tenantUuid,
                          provenance,
                          membershipId,
                          pendingMembership.getMembershipVersion()))
                  .contains(written);

              assertThatThrownBy(
                      () -> memberships.findFreshMembership(freshAccountUuid, tenantUuid))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no positive authority event");
              status.setRollbackOnly();
              return membershipId;
            });

    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_uuid = ?",
                    freshAccount.getId(),
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
  void canonicalUpgradeWaitsForLegacyWriterAndPreservesItsUnverifiedIdentity() throws Exception {
    TestContext context = newTestContextBeforeV38();
    DSLContext dsl = context.dsl();
    org.jooq.Record account =
        dsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash, role) VALUES (?, ?, ?, ?) "
                + "RETURNING id, account_uuid",
            "writer-" + UUID.randomUUID(),
            "writer-" + UUID.randomUUID() + "@example.test",
            "test-hash",
            "platformAdmin");
    long accountId = Objects.requireNonNull(account).get("id", Long.class);
    try (java.sql.Connection writer = context.dataSource().getConnection()) {
      writer.setAutoCommit(false);
      DSLContext writerDsl = DSL.using(writer, SQLDialect.POSTGRES);
      int writerPid = writerDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
      var original =
          writerDsl.fetchSingle(
              "INSERT INTO account_tenant_membership "
                  + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                  + "membership_version, membership_authority_generation, authority_provenance) "
                  + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                  + "RETURNING id, account_id, tenant_id, tenant_uuid, gameplay_admission_allowed, "
                  + "lifecycle_state, membership_version, membership_authority_generation, "
                  + "authority_provenance, xmin::text AS row_version",
              accountId,
              7102L);
      var originalRows = original.intoMap();
      long membershipId = original.get("id", Long.class);
      var executor = Executors.newSingleThreadExecutor();
      try {
        var migration = executor.submit(() -> migrateToVersion(context, "38"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
          blocked =
              Boolean.TRUE.equals(
                  dsl.fetchSingle(
                          "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE "
                              + "wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)))",
                          writerPid)
                      .get(0, Boolean.class));
          if (blocked) break;
          Thread.sleep(10);
        }
        assertThat(blocked)
            .as("canonical migration physically waits for its legacy writer")
            .isTrue();
        assertThat(migration.isDone()).isFalse();
        writer.commit();
        migration.get(60, TimeUnit.SECONDS);

        // V38 waits for the committed writer without changing either evidence or row version.
        assertThat(retainedMembership(context, membershipId).intoMap()).isEqualTo(originalRows);
        migrateToVersion(context, "40");
        assertThat(retainedMembership(context, membershipId).intoMap()).isEqualTo(originalRows);
        String originalHeap = membershipHeap(context);

        // V40.1 deliberately adds a STORED generated retention key, rewriting the physical heap.
        // Logical identity/authority must remain exact; xmin is not immutable across that DDL.
        migrateToVersion(context, "40.1");
        var rewritten = retainedMembership(context, membershipId).intoMap();
        var originalEvidence = new java.util.LinkedHashMap<>(originalRows);
        var rewrittenEvidence = new java.util.LinkedHashMap<>(rewritten);
        originalEvidence.remove("row_version");
        rewrittenEvidence.remove("row_version");
        assertThat(rewrittenEvidence).isEqualTo(originalEvidence);
        assertThat(rewritten.get("row_version")).isNotEqualTo(originalRows.get("row_version"));
        String rewrittenHeap = membershipHeap(context);
        assertThat(rewrittenHeap).isNotEqualTo(originalHeap);

        migrateToLatest(context);
        assertThat(retainedMembership(context, membershipId).intoMap()).isEqualTo(rewritten);
        assertThat(membershipHeap(context)).isEqualTo(rewrittenHeap);
        assertThat(original.get("tenant_uuid", UUID.class)).isNull();
        assertThat(
                dsl.resultQuery(
                        "SELECT count(*) FROM account_tenant_membership_role_snapshots "
                            + "WHERE membership_id = ?",
                        membershipId)
                    .fetchOne(0, Long.class))
            .isZero();
        assertThat(dsl.fetchCount(org.jooq.impl.DSL.table("account_authority_outbox_events")))
            .isZero();
      } finally {
        try {
          writer.rollback();
        } finally {
          executor.shutdownNow();
          assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
      }
    }
  }

  @Test
  void missingHeadWithNoExactHistoryIsEmptyDespiteAnotherStreamsRetainedEvent() {
    TestContext context = newTestContextAtV39();
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
  void databaseRejectsRetainedEventMutationAndStillAllowsExactAppendAndStreamAdvance() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    DSLContext dsl = context.dsl();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());
    byte[] retainedPayload = new byte[] {31, 32, 33};
    // Component storage proof only: this does not execute or authorize a positive JOIN.
    Event retained =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream,
                    "immutable-request-1",
                    "immutable-event-1",
                    "immutable-digest-1",
                    retainedPayload));
    Checkpoint retainedCheckpoint =
        new Checkpoint(stream, 1L, retained.eventId(), retained.eventDigest());

    for (String statement :
        List.of(
            "UPDATE account_authority_outbox_events SET payload = decode('ffff', 'hex') "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
            "DELETE FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = 1")) {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      transaction,
                      () -> {
                        dsl.execute(statement, stream);
                        return null;
                      }))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Account authority outbox event evidence is immutable");
      assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 1L)))
          .contains(retained);
      assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
          .contains(retainedCheckpoint);
      assertThat(
              dsl.resultQuery(
                      "SELECT count(*) FROM account_authority_outbox_events "
                          + "WHERE outbox_stream_key = ?",
                      stream)
                  .fetchOne(0, Long.class))
          .isEqualTo(1L);
    }

    Event appended =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream,
                    "immutable-request-2",
                    "immutable-event-2",
                    "immutable-digest-2",
                    new byte[] {41, 42, 43}));
    assertThat(appended.outboxSequence()).isEqualTo(2L);
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 1L)))
        .contains(retained);
    assertThat(
            inTransaction(transaction, () -> repository.findEvent(stream, 1L))
                .orElseThrow()
                .payload())
        .containsExactly(retainedPayload);
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 2L)))
        .contains(appended);
    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(new Checkpoint(stream, 2L, appended.eventId(), appended.eventDigest()));
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
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
      assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("both concurrent append workers become ready")
          .isTrue();
      start.countDown();
      List<Event> events =
          List.of(
              first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
              second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));

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
    migrateToVersion(context, null);
  }

  private void migrateToVersion(TestContext context, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(context.dataSource())
            .schemas(context.schema())
            .defaultSchema(context.schema())
            .placeholders(Map.of("serviceSchema", context.schema()))
            .locations("classpath:db/migration");
    if (targetVersion != null) configuration.target(targetVersion);
    configuration.load().migrate();
  }

  private org.jooq.Record retainedMembership(TestContext context, long membershipId) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT id, account_id, tenant_id, tenant_uuid, gameplay_admission_allowed, "
                + "lifecycle_state, membership_version, membership_authority_generation, "
                + "authority_provenance, xmin::text AS row_version "
                + "FROM account_tenant_membership WHERE id = ?",
            membershipId);
  }

  private String membershipHeap(TestContext context) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT pg_relation_filenode(?::regclass)::text",
            context.schema() + ".account_tenant_membership")
        .get(0, String.class);
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
      if (!latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Authority outbox concurrency proof timed out at latch");
      }
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
