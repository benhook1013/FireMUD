package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Genuine V122 proof transferred from the retired test-only protected-entry feasibility fixture.
 *
 * <p>Upstream carrier fields are shape fixtures only. Recovery requires exact independent
 * durability evidence; a quiet primary does not guarantee that evidence, and unavailable coverage
 * must deny without a marker, forced flush, retry warming or deadline extension. Async-original
 * unflushed-COMMIT and guaranteed catalog-PRUNE provenance are not claimed by this fixture. SQL
 * checks binding and shape; only the trusted JVM physical-COMMIT path supplies the opaque ACK.
 * Neither this receipt nor the restricted test role establishes production privilege adoption or
 * gameplay admission.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class AccountGameplayAdmissionProtectedEntryPrototypeIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");

  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";
  private static final String RECEIPTS = "account_gameplay_admission_original_commit_ack_receipts";

  @Test
  void coldGenuineAcknowledgedFinalizationPersistsExactProofWithoutAuxiliaryWrites() {
    var context = context();
    var ack = finalized(context, 15000);
    var receipt =
        new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(context.admin()).confirm(ack);
    assertThat(receipt.finalizationXid()).isEqualTo(ack.finalizationXid());
    assertThat(receipt.committedBeforeMs()).isEqualTo(ack.committedBeforeMs());
    assertThat(receipt.expiresAtMs()).isEqualTo(expiry(ack));
    assertThat(receipt.receiptXid()).isNotEqualTo(ack.finalizationXid());
    assertThat(receipt.operation().evidence()).isEqualTo(ack.evidence());
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isOne();
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  @Test
  void catalogChurnBeforeOriginalCommitPreservesGenuineAcknowledgedFinalization() {
    var context = context();
    for (int index = 0; index < 30; index++) {
      context.dsl().execute("CREATE TABLE retired_entry_catalog_" + index + "(id integer)");
      context.dsl().execute("DROP TABLE retired_entry_catalog_" + index);
    }
    var ack = finalized(context, 15000);
    var receipt =
        new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(context.admin()).confirm(ack);
    assertThat(receipt.finalizationXid()).isEqualTo(ack.finalizationXid());
    assertThat(receipt.committedBeforeMs()).isEqualTo(ack.committedBeforeMs());
    assertThat(receipt.expiresAtMs()).isEqualTo(expiry(ack));
  }

  @Test
  void concurrentGenuineAckCreatorsRetainOneExactWinnerWithoutRestamping() throws Exception {
    var context = context();
    var ack = finalized(context, 15000);
    var original = operation(context, ack.evidence());
    var sources = sourceSnapshot(context);
    var start = new CountDownLatch(1);
    java.util.concurrent.Callable<Object> duplicate =
        () -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          return retrySerialization(
              () ->
                  context
                      .transaction()
                      .execute(status -> repository(context).create(ack).receipt()));
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(duplicate);
      var right = executor.submit(duplicate);
      start.countDown();
      assertThat(left.get(30, TimeUnit.SECONDS)).isEqualTo(right.get(30, TimeUnit.SECONDS));
    }
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isOne();
    assertThat(operation(context, ack.evidence())).isEqualTo(original);
    assertThat(sourceSnapshot(context)).isEqualTo(sources);
    var before = receipt(context);
    waitPastExpiry(context, ack);
    var replay = context.transaction().execute(status -> repository(context).create(ack));
    assertThat(replay.inserted()).isFalse();
    assertThat(replay.receipt().committedBeforeMs()).isEqualTo(ack.committedBeforeMs());
    assertThat(receipt(context)).isEqualTo(before);
    // This asserts exact storage replay. A retry COMMIT is not historical durability proof.
  }

  @Test
  void staleSerializableGenuineAckCreatorRetriesAndRetainsOneExactWinner() throws Exception {
    var context = context();
    var ack = finalized(context, 15000);
    try (var stale = context.admin().getConnection()) {
      stale.setAutoCommit(false);
      stale.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      DSL.using(stale).fetchSingle("SELECT pg_current_snapshot()");
      var winner =
          context.transaction().execute(status -> repository(context).create(ack).receipt());
      assertThatThrownBy(() -> insertAck(DSL.using(stale), ack))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("40001"));
      stale.rollback();
      var retry = context.transaction().execute(status -> repository(context).create(ack));
      assertThat(retry.inserted()).isFalse();
      assertThat(retry.receipt()).isEqualTo(winner);
      assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isOne();
    }
  }

  @Test
  void restrictedCallerCannotForgeProofWriteTablesInvokeGuardOrEscalate() throws Exception {
    var context = context();
    var ack = finalized(context, 15000);
    String role = context.schema() + "_caller";
    context
        .dsl()
        .execute(
            "CREATE ROLE "
                + role
                + " LOGIN PASSWORD 'isolated-retirement-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION");
    context.dsl().execute("GRANT USAGE ON SCHEMA " + context.schema() + " TO " + role);
    var caller = source(context.schema(), role, "isolated-retirement-only");
    var denied =
        List.of(
            "INSERT INTO " + RECEIPTS + "(request_id) VALUES ('" + request(ack.evidence()) + "')",
            "UPDATE " + RECEIPTS + " SET committed_before_ms = 1",
            "DELETE FROM " + RECEIPTS,
            "TRUNCATE " + RECEIPTS,
            "INSERT INTO account_gameplay_admission_commit_confirmations(request_id) VALUES ('"
                + request(ack.evidence())
                + "')",
            "SELECT account_gameplay_admission_original_ack_receipt_guard()",
            "SELECT account_gameplay_admission_confirmation_guard()",
            "SET ROLE " + POSTGRES.getUsername(),
            "ALTER FUNCTION account_gameplay_admission_original_ack_receipt_guard() RESET ALL",
            "ALTER TABLE " + RECEIPTS + " DISABLE TRIGGER ALL",
            "CREATE TABLE " + context.schema() + ".shadow(id integer)",
            "SET session_replication_role = replica");
    for (String sql : denied) {
      try (var connection = caller.getConnection()) {
        assertThatThrownBy(() -> DSL.using(connection).execute(sql))
            .isInstanceOf(RuntimeException.class);
      }
    }
    // The genuine trusted owner remains usable after all denied caller attempts.
    var receipt =
        new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(context.admin()).confirm(ack);
    assertThat(receipt.finalizationXid()).isEqualTo(ack.finalizationXid());
    assertThat(receipt.committedBeforeMs()).isEqualTo(ack.committedBeforeMs());
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isOne();
  }

  @Test
  void ownFinalizationAndReleasedSubtransactionCannotSupplyIndependentOriginal() {
    for (boolean subtransaction : List.of(false, true)) {
      var context = context();
      var evidence = pending(context, 15000);
      UUID decision = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(
                          status -> {
                            if (subtransaction)
                              context.dsl().execute("SAVEPOINT original_finalization");
                            new AccountGameplayAdmissionLeaseRepository(context.dsl())
                                .recordCommitted(evidence, decision);
                            if (subtransaction)
                              context.dsl().execute("RELEASE SAVEPOINT original_finalization");
                            context
                                .dsl()
                                .execute(
                                    "INSERT INTO "
                                        + RECEIPTS
                                        + "(request_id, evidence_sha256, binding_decision_id, finalization_xid, committed_before_ms) VALUES (?, ?, ?, pg_current_xact_id()::text, ?)",
                                    request(evidence),
                                    evidence.sha256(),
                                    decision,
                                    Long.parseLong((String) evidence.carrier().get("evaluatedAt")));
                          }))
          .hasMessageContaining("requires independent transaction");
      assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
    }
  }

  @Test
  void receiptInCurrentTransactionAndReleasedSubtransactionCannotProveDurability() {
    for (boolean subtransaction : List.of(false, true)) {
      var context = context();
      var ack = finalized(context, 15000);
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(
                          status -> {
                            if (subtransaction) context.dsl().execute("SAVEPOINT receipt_creation");
                            repository(context).create(ack);
                            if (subtransaction)
                              context.dsl().execute("RELEASE SAVEPOINT receipt_creation");
                            repository(context).readDurably(ack.evidence(), ack.decisionId());
                          }))
          .hasMessageContaining("requires independent commit");
      assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
    }
  }

  @Test
  void staleSnapshotBeforeOriginalCommitCannotCreateAcknowledgementReceipt() throws Exception {
    var context = context();
    var evidence = pending(context, 15000);
    try (var stale = context.admin().getConnection()) {
      stale.setAutoCommit(false);
      stale.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      DSL.using(stale).fetchSingle("SELECT pg_current_snapshot()");
      var ack =
          new AccountGameplayAdmissionOriginalCommitExecutor(context.admin())
              .execute(evidence, UUID.randomUUID());
      assertThatThrownBy(() -> insertAck(DSL.using(stale), ack))
          .satisfies(failure -> assertThat(sqlState(failure)).isIn("23514", "40001"));
      stale.rollback();
    }
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
  }

  @Test
  void changedCallerProofAndBindingsDenyAndRetainedReceiptRemainsImmutable() {
    var context = context();
    var ack = finalized(context, 15000);
    for (String field :
        List.of("account_uuid", "lease_id", "lease_fence", "expires_at_ms", "receipt_xid")) {
      String value =
          switch (field) {
            case "account_uuid", "lease_id" -> "'" + UUID.randomUUID() + "'::uuid";
            case "receipt_xid" -> "'1'";
            default -> "1";
          };
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(
                          status ->
                              context
                                  .dsl()
                                  .execute(
                                      "INSERT INTO "
                                          + RECEIPTS
                                          + "(request_id, evidence_sha256, binding_decision_id, finalization_xid, committed_before_ms, "
                                          + field
                                          + ") VALUES (?, ?, ?, ?, ?, "
                                          + value
                                          + ")",
                                      request(ack.evidence()),
                                      ack.evidence().sha256(),
                                      ack.decisionId(),
                                      ack.finalizationXid(),
                                      ack.committedBeforeMs())))
          .hasMessageContaining("binding is database derived");
    }
    for (String changed : List.of("digest", "decision", "xid", "bound")) {
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(
                          status ->
                              context
                                  .dsl()
                                  .execute(
                                      "INSERT INTO "
                                          + RECEIPTS
                                          + "(request_id, evidence_sha256, binding_decision_id, finalization_xid, committed_before_ms) VALUES (?, ?, ?, ?, ?)",
                                      request(ack.evidence()),
                                      changed.equals("digest")
                                          ? "b".repeat(64)
                                          : ack.evidence().sha256(),
                                      changed.equals("decision")
                                          ? UUID.randomUUID()
                                          : ack.decisionId(),
                                      changed.equals("xid") ? "1" : ack.finalizationXid(),
                                      changed.equals("bound")
                                          ? expiry(ack)
                                          : ack.committedBeforeMs())))
          .hasMessageContaining("Exact original Account COMMIT acknowledgement required");
    }
    var receipt =
        new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(context.admin()).confirm(ack);
    var before = receipt(context);
    for (String mutation :
        List.of(
            "UPDATE " + RECEIPTS + " SET committed_before_ms = committed_before_ms + 1",
            "UPDATE " + RECEIPTS + " SET expires_at_ms = expires_at_ms + 1",
            "UPDATE " + RECEIPTS + " SET evidence_sha256 = '" + "b".repeat(64) + "'",
            "DELETE FROM " + RECEIPTS,
            "TRUNCATE " + RECEIPTS)) {
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(status -> context.dsl().execute(mutation)))
          .hasMessageContaining("receipt is immutable");
    }
    assertThat(receipt(context)).isEqualTo(before);
    assertThat(receipt.committedBeforeMs()).isEqualTo(ack.committedBeforeMs());
  }

  @Test
  void originalLockWaitPastExpiryCannotMintAcknowledgementOrReceipt() throws Exception {
    var context = context();
    var evidence = pending(context, 1800);
    try (var lock = context.admin().getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      lock.setAutoCommit(false);
      var owner =
          DSL.using(lock)
              .fetchSingle(
                  "SELECT account_uuid FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
                  request(evidence))
              .get(0, UUID.class);
      DSL.using(lock)
          .fetchSingle(
              "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", owner);
      var started = new CountDownLatch(1);
      var backendPid = new java.util.concurrent.atomic.AtomicInteger();
      var waitingSource =
          new org.springframework.jdbc.datasource.DelegatingDataSource(context.admin()) {
            @Override
            public Connection getConnection() throws SQLException {
              Connection connection = super.getConnection();
              try {
                backendPid.set(
                    DSL.using(connection)
                        .fetchSingle("SELECT pg_backend_pid()")
                        .get(0, Integer.class));
                started.countDown();
                return connection;
              } catch (RuntimeException | Error failure) {
                try {
                  connection.close();
                } catch (SQLException closeFailure) {
                  failure.addSuppressed(closeFailure);
                }
                throw failure;
              }
            }
          };
      var attempt =
          executor.submit(
              () ->
                  new AccountGameplayAdmissionOriginalCommitExecutor(waitingSource)
                      .execute(evidence, UUID.randomUUID()));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      // Verify an actual independent lock wait before the original expiry is allowed to pass.
      boolean waiting = false;
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!waiting && System.nanoTime() < until) {
        waiting =
            context
                .dsl()
                .fetchSingle(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')",
                    backendPid.get())
                .get(0, Boolean.class);
        if (!waiting) Thread.sleep(10);
      }
      assertThat(waiting).isTrue();
      context
          .dsl()
          .fetchSingle(
              "SELECT pg_sleep(GREATEST(0, (? - ceil(extract(epoch FROM clock_timestamp()) * 1000) + 50) / 1000.0))",
              Long.parseLong((String) evidence.carrier().get("expiresAt")));
      lock.rollback();
      assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }
    assertThat(operation(context, evidence).get("status")).isEqualTo("PENDING");
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
  }

  @Test
  void originalUpdateBeforeExpiryButPhysicalCommitAfterExpiryCannotMintReceipt() {
    var context = context();
    var evidence = pending(context, 1000);
    var physicalCommits = new java.util.concurrent.atomic.AtomicInteger();
    var delayed =
        new org.springframework.jdbc.datasource.DelegatingDataSource(context.admin()) {
          @Override
          public Connection getConnection() throws SQLException {
            return delayedCommitConnection(super.getConnection());
          }

          private Connection delayedCommitConnection(Connection physical) {
            try {
              return (Connection)
                  java.lang.reflect.Proxy.newProxyInstance(
                      Connection.class.getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (proxy, method, arguments) -> {
                        if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                          DSL.using(physical)
                              .fetchSingle(
                                  "SELECT pg_sleep(GREATEST(0, (? - ceil(extract(epoch FROM clock_timestamp()) * 1000) + 50) / 1000.0))",
                                  Long.parseLong((String) evidence.carrier().get("expiresAt")));
                          physical.commit();
                          physicalCommits.incrementAndGet();
                          return null;
                        }
                        try {
                          return method.invoke(physical, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                      });
            } catch (RuntimeException | Error failure) {
              try {
                physical.close();
              } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
              }
              throw failure;
            }
          }
        };
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(delayed)
                    .execute(evidence, UUID.randomUUID()))
        .hasMessageContaining("post-COMMIT clock bound unavailable");
    assertThat(physicalCommits.get()).isOne();
    assertThat(operation(context, evidence).get("status")).isEqualTo("COMMITTED");
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
  }

  @Test
  void missingPendingRolledBackAndAbortedOriginalRemainUnproved() {
    var context = context();
    var evidence = pending(context, 15000);
    UUID decision = UUID.randomUUID();
    var original = operation(context, evidence);
    var executor = new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(context.admin());
    assertThatThrownBy(() -> executor.read(evidence, decision))
        .hasMessageContaining("Exact durable original Account acknowledgement receipt required");
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          new AccountGameplayAdmissionLeaseRepository(context.dsl())
                              .recordCommitted(evidence, decision);
                          throw new IllegalStateException("rollback original");
                        }))
        .hasMessageContaining("rollback original");
    assertThat(operation(context, evidence)).isEqualTo(original);
    assertThatThrownBy(() -> executor.read(evidence, decision))
        .hasMessageContaining("Exact durable original Account acknowledgement receipt required");
    context
        .transaction()
        .executeWithoutResult(
            status ->
                new AccountGameplayAdmissionLeaseRepository(context.dsl())
                    .recordAborted(evidence, null, UUID.randomUUID()));
    assertThatThrownBy(() -> executor.read(evidence, decision))
        .hasMessageContaining("Exact durable original Account acknowledgement receipt required");
    var missing = new LinkedHashMap<>(evidence.carrier());
    missing.put("requestId", UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                executor.read(AccountGameplayAdmissionLeaseEvidence.fromCarrier(missing), decision))
        .isInstanceOf(RuntimeException.class);
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
  }

  @Test
  void genuineAckSqlEntryRequiresWritableSerializableTransactionAndKeepsFinalizerStamp() {
    var context = context();
    var ack = finalized(context, 15000);
    for (int isolation :
        List.of(
            TransactionDefinition.ISOLATION_READ_COMMITTED,
            TransactionDefinition.ISOLATION_REPEATABLE_READ)) {
      var unsupported = new TransactionTemplate(new DataSourceTransactionManager(context.admin()));
      unsupported.setIsolationLevel(isolation);
      assertThatThrownBy(
              () -> unsupported.executeWithoutResult(status -> insertAck(context.dsl(), ack)))
          .hasMessageContaining("Writable SERIALIZABLE");
      assertThatThrownBy(
              () ->
                  unsupported.executeWithoutResult(
                      status ->
                          context
                              .dsl()
                              .fetch(
                                  "SELECT * FROM account_gameplay_admission_read_original_ack_receipt_durably(?, ?, ?)",
                                  request(ack.evidence()),
                                  ack.evidence().sha256(),
                                  ack.decisionId())))
          .hasMessageContaining("Writable SERIALIZABLE");
    }
    var readOnly = new TransactionTemplate(new DataSourceTransactionManager(context.admin()));
    readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.executeWithoutResult(
                    status ->
                        context
                            .dsl()
                            .fetch(
                                "SELECT * FROM account_gameplay_admission_read_original_ack_receipt_durably(?, ?, ?)",
                                request(ack.evidence()),
                                ack.evidence().sha256(),
                                ack.decisionId())))
        .hasMessageContaining("Writable SERIALIZABLE");
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    "UPDATE account_gameplay_admission_lease_operations SET finalization_xid = '1' WHERE request_id = ?",
                                    request(ack.evidence()))))
        .hasMessageContaining("finalization transaction is database stamped");
    assertThat(context.dsl().fetchCount(DSL.table(RECEIPTS))).isZero();
  }

  private static Context context() {
    String schema = "protected_" + UUID.randomUUID().toString().replace("-", "");
    var admin = source(schema, POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(admin)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(admin));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    return new Context(
        schema,
        admin,
        DSL.using(new TransactionAwareDataSourceProxy(admin), SQLDialect.POSTGRES),
        transaction);
  }

  private static DriverManagerDataSource source(String schema, String username, String password) {
    var source = new DriverManagerDataSource();
    source.setUrl(
        POSTGRES.getJdbcUrl()
            + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
            + "currentSchema="
            + schema);
    source.setUsername(username);
    source.setPassword(password);
    return source;
  }

  private static OriginalCommitAcknowledgement finalized(Context context, long lifetime) {
    return new AccountGameplayAdmissionOriginalCommitExecutor(context.admin())
        .execute(pending(context, lifetime), UUID.randomUUID());
  }

  private static AccountGameplayAdmissionLeaseEvidence pending(Context context, long lifetime) {
    return context
        .transaction()
        .execute(
            status -> {
              var authorities = new AccountAuthorityGenerationRepository(context.dsl());
              var sources =
                  new AccountAuthoritySourceEvidenceRepository(
                      context.dsl(),
                      authorities,
                      new AccountAuthorityOutboxRepository(context.dsl()));
              sources.initializeIssuerIfAbsent("firemud-account-service");
              var account = new Account();
              account.setUsername("protected-" + UUID.randomUUID());
              account.setEmail(UUID.randomUUID() + "@example.test");
              account.setPasswordHash("shape-fixture-only");
              account.setLoginAuthModes("PASSWORD");
              UUID accountId =
                  new AccountRepository(context.dsl(), sources).save(account).getAccountUuid();
              var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
              var allocation = repository.allocate(accountId, UUID.randomUUID(), UUID.randomUUID());
              var evidence = evidence(allocation, lifetime);
              repository.beginPending(evidence);
              return evidence;
            });
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence(
      AccountGameplayAdmissionLeaseRepository.Allocation allocation, long lifetime) {
    long now = Instant.now().toEpochMilli();
    String account = allocation.accountId().toString();
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", "test");
    carrier.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    carrier.put("requestId", allocation.requestId().toString());
    carrier.put("leaseId", allocation.leaseId().toString());
    carrier.put("leaseFence", Long.toString(allocation.leaseFence()));
    carrier.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, OTHER);
    scope.put("accountId", account);
    scope.put("tenantId", TENANT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "1");
    carrier.put("bindingScope", scope);
    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            Map.of(TENANT, "1"),
            "privateRealmGrantVersions",
            List.of()));
    carrier.put("issuanceFence", "7");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            "1"));
    carrier.put(
        "outboxCheckpoints",
        List.of(
                "account/" + account,
                "issuer/firemud-account-service",
                "membership/" + account + "/" + TENANT,
                "tenant/" + TENANT)
            .stream()
            .map(
                suffix ->
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:" + suffix,
                        "outboxSequence",
                        "1"))
            .toList());
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", account);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti"))
      token.put(key, OTHER);
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    token.put("tokenGeneration", "1");
    token.put("issuanceFence", "7");
    token.put("tokenIdentityFence", "9");
    token.put("issuedAt", Long.toString(now / 1000 - 1));
    token.put("notBefore", Long.toString(now / 1000 - 1));
    token.put("expiresAt", Long.toString(now / 1000 + 300));
    carrier.put("tokenIdentityEvidence", token);
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put("expiresAt", Long.toString(now + lifetime));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static AccountGameplayAdmissionOriginalAckReceiptRepository repository(Context context) {
    return new AccountGameplayAdmissionOriginalAckReceiptRepository(
        context.dsl(), new AccountGameplayAdmissionLeaseRepository(context.dsl()));
  }

  private static void insertAck(DSLContext dsl, OriginalCommitAcknowledgement ack) {
    dsl.execute(
        "INSERT INTO "
            + RECEIPTS
            + "(request_id, evidence_sha256, binding_decision_id, finalization_xid, committed_before_ms) VALUES (?, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING",
        request(ack.evidence()),
        ack.evidence().sha256(),
        ack.decisionId(),
        ack.finalizationXid(),
        ack.committedBeforeMs());
  }

  private static Map<String, Object> operation(
      Context context, AccountGameplayAdmissionLeaseEvidence evidence) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT * FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
            request(evidence))
        .intoMap();
  }

  private static Map<String, Object> sourceSnapshot(Context context) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (String table :
        List.of(
            "accounts",
            "account_gameplay_admission_lease_fences",
            "account_gameplay_admission_lease_allocations",
            "account_authority_generations",
            "account_authority_issuance_fences",
            "account_authority_source_records",
            "account_authority_outbox_streams",
            "account_authority_outbox_events")) {
      snapshot.put(table, context.dsl().fetch("SELECT * FROM " + table + " ORDER BY 1").intoMaps());
    }
    return snapshot;
  }

  private static Map<String, Object> receipt(Context context) {
    return context.dsl().fetchSingle("SELECT * FROM " + RECEIPTS).intoMap();
  }

  private static <T> T retrySerialization(java.util.function.Supplier<T> action) {
    for (int attempt = 0; ; attempt++) {
      try {
        return action.get();
      } catch (RuntimeException failure) {
        if (!"40001".equals(sqlState(failure)) || attempt >= 4) throw failure;
      }
    }
  }

  private static void waitPastExpiry(Context context, OriginalCommitAcknowledgement ack) {
    context
        .dsl()
        .fetchSingle(
            "SELECT pg_sleep(GREATEST(0, (? - ceil(extract(epoch FROM clock_timestamp()) * 1000) + 50) / 1000.0))",
            expiry(ack));
  }

  private static long expiry(OriginalCommitAcknowledgement ack) {
    return Long.parseLong((String) ack.evidence().carrier().get("expiresAt"));
  }

  private static UUID request(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("requestId"));
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql) return sql.getSQLState();
    return null;
  }

  private record Context(
      String schema,
      DriverManagerDataSource admin,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
