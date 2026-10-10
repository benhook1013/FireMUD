package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test-only protected-entry feasibility, never production privilege adoption or admission proof.
 *
 * <p>The original operation is finalized in unchanged Flyway storage. Upstream carrier fields are
 * shape fixtures only. Quiet cold/warm successes are requirements, not assumptions: catalog WAL
 * preventing coverage fails visibly without a marker, forced flush, retry warming or deadline
 * extension. Async-original unflushed-COMMIT and guaranteed catalog-PRUNE provenance are not
 * claimed by this fixture; establishing those exact conditions needs separate physical evidence.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class AccountGameplayAdmissionProtectedEntryPrototypeIntegrationTest {
  // Never consumes the shared fixture's external database override.
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");

  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";

  @Test
  void coldQuietEntryProvesExactOriginalThenIndependentReceiptWithoutAuxiliaryWrites()
      throws Exception {
    var context = context();
    var original = finalized(context, 15000);
    JSONB created = call(context, original, "confirm");
    assertProof(context, original, created);
    assertThat(call(context, original, "read_receipt")).isEqualTo(created);
    assertProductionReceiptTablesEmpty(context);
  }

  @Test
  void warmedEntryStillRequiresQuietCoverageAndLostResponseRecoveryPreservesExpiry()
      throws Exception {
    var context = context();
    // Warm entry/catalog resolution on the SAME backend before original finalization. The
    // missing-operation branch does not claim to warm every lazily planned PL/pgSQL statement.
    try (var warm = context.caller().getConnection()) {
      warm.setAutoCommit(false);
      warm.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      assertThatThrownBy(
              () ->
                  invoke(
                      warm,
                      context,
                      "confirm",
                      UUID.randomUUID(),
                      "a".repeat(64),
                      UUID.randomUUID()))
          .hasMessageContaining("prototype exact original required");
      warm.rollback();
      var original = finalized(context, 1500);
      JSONB discardedResponse =
          invoke(
              warm,
              context,
              "confirm",
              request(original),
              original.evidence().sha256(),
              original.decision());
      warm.commit();
      waitPastExpiry(context, original);
      assertThat(call(context, original, "read_receipt")).isEqualTo(discardedResponse);
      assertThat(call(context, original, "confirm")).isEqualTo(discardedResponse);
      assertProof(context, original, discardedResponse);
    }
  }

  @Test
  void callerCannotForgeCaptureWriteTablesInvokePrivateWriterOrEscalate() throws Exception {
    var context = context();
    var original = finalized(context, 15000);
    String diagnosticStart = observeWal(context, "after-original-finalization");
    List<String> denied =
        List.of(
            "INSERT INTO "
                + context.proofSchema()
                + ".receipts(request_id) VALUES ('"
                + request(original)
                + "')",
            "UPDATE " + context.proofSchema() + ".receipts SET committed_before_ms = 1",
            "DELETE FROM " + context.proofSchema() + ".receipts",
            "TRUNCATE " + context.proofSchema() + ".receipts",
            "SELECT "
                + context.proofSchema()
                + ".write_receipt(NULL::"
                + context.proofSchema()
                + ".receipts)",
            "SELECT " + context.proofSchema() + ".immutable()",
            "SET ROLE " + context.proofSchema() + "_owner",
            "ALTER FUNCTION " + context.proofSchema() + ".confirm(uuid,text,uuid) RESET ALL",
            "ALTER TABLE " + context.proofSchema() + ".receipts DISABLE TRIGGER ALL",
            "CREATE TABLE " + context.proofSchema() + ".shadow(id integer)",
            "SET session_replication_role = replica",
            "SELECT "
                + context.proofSchema()
                + ".confirm('"
                + request(original)
                + "'::uuid, '"
                + original.evidence().sha256()
                + "', '"
                + original.decision()
                + "'::uuid, '0/1'::pg_lsn)");
    for (String sql : denied) {
      assertThatThrownBy(
              () ->
                  callerTransaction(
                      context,
                      connection -> {
                        try (var statement = connection.createStatement()) {
                          return statement.execute(sql);
                        }
                      }))
          .as(sql)
          .isInstanceOf(SQLException.class)
          .satisfies(
              failure ->
                  assertThat(sqlState(failure))
                      .isEqualTo(sql.endsWith("'0/1'::pg_lsn)") ? "42883" : "42501"));
    }
    observeWal(context, "after-denial-loop");
    // A hostile session search path cannot replace fully qualified owner objects.
    JSONB created;
    try {
      created =
          callerTransaction(
              context,
              connection -> {
                DSL.using(connection).execute("SET LOCAL search_path = pg_temp, public");
                // Observe from the administrator only; do not warm this new caller with SQL.
                observeWal(context, "before-fresh-caller-confirm");
                return invoke(
                    connection,
                    context,
                    "confirm",
                    request(original),
                    original.evidence().sha256(),
                    original.decision());
              });
    } catch (SQLException failure) {
      retainFailureWal(context, diagnosticStart, failure);
      throw failure;
    }
    assertProof(context, original, created);
    assertThat(call(context, original, "read_receipt")).isEqualTo(created);
  }

  private static String observeWal(Context context, String phase) {
    var observation =
        context
            .dsl()
            .fetchSingle(
                "SELECT pg_current_wal_insert_lsn()::text AS insert_lsn, pg_current_wal_flush_lsn()::text AS flush_lsn, ceil(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS observed_ms");
    System.out.println(
        "Protected-entry administrator WAL observation " + phase + " " + observation);
    return observation.get("flush_lsn", String.class);
  }

  private static void retainFailureWal(Context context, String start, SQLException failure) {
    // Failure diagnostics must never replace the original failure or force WAL durability.
    try {
      if (!(failure instanceof PSQLException postgres)) {
        System.out.println("Protected-entry WAL diagnostic unavailable: no PostgreSQL detail");
        return;
      }
      var serverError = postgres.getServerErrorMessage();
      String detail = serverError == null ? null : serverError.getDetail();
      if (detail == null) {
        System.out.println("Protected-entry WAL diagnostic unavailable: no PostgreSQL detail");
        return;
      }
      var upper = Pattern.compile("(?:^| )upper_lsn=([0-9A-F]+/[0-9A-F]+)(?: |$)").matcher(detail);
      if (!upper.find()) {
        System.out.println("Protected-entry WAL diagnostic unavailable: no captured upper LSN");
        return;
      }
      String end = upper.group(1);
      var bounds =
          context
              .dsl()
              .fetchSingle(
                  "SELECT pg_wal_lsn_diff(?::pg_lsn, ?::pg_lsn)::bigint AS bytes, (pg_walfile_name_offset(?::pg_lsn)).file_offset AS start_offset, pg_size_bytes(current_setting('wal_segment_size')) AS segment_bytes",
                  end,
                  start,
                  start);
      long bytes = bounds.get("bytes", Long.class);
      long offset = bounds.get("start_offset", Long.class);
      long segmentBytes = bounds.get("segment_bytes", Long.class);
      if (bytes <= 0 || bytes > 2 * segmentBytes - offset) {
        System.out.println(
            "Protected-entry WAL diagnostic unavailable: empty range or more than two segments "
                + start
                + ".."
                + end);
        return;
      }
      // This container belongs only to this test class. Retain decoded output, never an archive.
      var dump =
          POSTGRES.execInContainer(
              "pg_waldump",
              "--path=/var/lib/postgresql/data/pg_wal",
              "--start=" + start,
              "--end=" + end,
              "--limit=128");
      String output = dump.getStdout() + dump.getStderr();
      System.out.println(
          "Protected-entry WAL diagnostic "
              + start
              + ".."
              + end
              + " exit="
              + dump.getExitCode()
              + " (maximum two segments, 128 records, 16384 characters):\n"
              + output.substring(0, Math.min(output.length(), 16384)));
      System.out.println(
          "Protected-entry WAL diagnostic may be partial or unreadable before ordinary flushing; no flush, checkpoint, segment switch or retry was requested. Record/character limits may truncate provenance, and WAL records alone do not identify their backend.");
    } catch (Exception diagnosticFailure) {
      System.out.println("Protected-entry WAL diagnostic unavailable: " + diagnosticFailure);
    }
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
                                .fetchSingle(
                                    "SELECT " + context.proofSchema() + ".confirm(?, ?, ?)",
                                    request(evidence),
                                    evidence.sha256(),
                                    decision);
                          }))
          .hasMessageContaining("prototype independent original required");
      assertThat(context.dsl().fetchCount(DSL.table(context.proofSchema() + ".receipts"))).isZero();
    }
  }

  @Test
  void receiptCreatedInCurrentTransactionIsNotIndependentRecovery() throws Exception {
    var context = context();
    var original = finalized(context, 15000);
    assertThatThrownBy(
            () ->
                callerTransaction(
                    context,
                    connection -> {
                      invoke(
                          connection,
                          context,
                          "confirm",
                          request(original),
                          original.evidence().sha256(),
                          original.decision());
                      return invoke(
                          connection,
                          context,
                          "read_receipt",
                          request(original),
                          original.evidence().sha256(),
                          original.decision());
                    }))
        .hasMessageContaining("prototype independent receipt required");
    assertThat(context.dsl().fetchCount(DSL.table(context.proofSchema() + ".receipts"))).isZero();
  }

  @Test
  void lateAndChangedOriginalDenyWithoutReceiptOrDeadlineMutation() throws Exception {
    var context = context();
    var original = finalized(context, 1200);
    assertThatThrownBy(
            () ->
                callerTransaction(
                    context,
                    connection ->
                        invoke(
                            connection,
                            context,
                            "confirm",
                            request(original),
                            "b".repeat(64),
                            original.decision())))
        .hasMessageContaining("prototype exact original required");
    assertThatThrownBy(
            () ->
                callerTransaction(
                    context,
                    connection ->
                        invoke(
                            connection,
                            context,
                            "confirm",
                            request(original),
                            original.evidence().sha256(),
                            UUID.randomUUID())))
        .hasMessageContaining("prototype exact original required");
    waitPastExpiry(context, original);
    assertThatThrownBy(() -> call(context, original, "confirm"))
        .hasMessageContaining("prototype unchanged deadline expired");
    assertThat(context.dsl().fetchCount(DSL.table(context.proofSchema() + ".receipts"))).isZero();
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT expires_at_ms FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
                    request(original))
                .get(0, Long.class))
        .isEqualTo(expiry(original));
  }

  @Test
  void staleSerializableCreatorMustRetryAndRecoverOneExactWinner() throws Exception {
    var context = context();
    var original = finalized(context, 15000);
    try (var stale = context.caller().getConnection()) {
      stale.setAutoCommit(false);
      stale.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      DSL.using(stale).fetchSingle("SELECT pg_current_snapshot()");
      JSONB winner = call(context, original, "confirm");
      assertThatThrownBy(
              () ->
                  invoke(
                      stale,
                      context,
                      "confirm",
                      request(original),
                      original.evidence().sha256(),
                      original.decision()))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("40001"));
      stale.rollback();
      assertThat(call(context, original, "read_receipt")).isEqualTo(winner);
      assertThat(context.dsl().fetchCount(DSL.table(context.proofSchema() + ".receipts"))).isOne();
    }
  }

  @Test
  void snapshotBeforeIndependentFinalizationCannotSeeItsLaterCommit() throws Exception {
    var context = context();
    var evidence = pending(context, 15000);
    UUID decision = UUID.randomUUID();
    try (var stale = context.caller().getConnection()) {
      stale.setAutoCommit(false);
      stale.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      DSL.using(stale).fetchSingle("SELECT pg_current_snapshot()");
      new AccountGameplayAdmissionOriginalCommitExecutor(context.admin())
          .execute(evidence, decision);
      assertThatThrownBy(
              () ->
                  invoke(stale, context, "confirm", request(evidence), evidence.sha256(), decision))
          .hasMessageContaining("prototype exact original required");
      stale.rollback();
    }
    assertThat(context.dsl().fetchCount(DSL.table(context.proofSchema() + ".receipts"))).isZero();
  }

  @Test
  void lockWaitPastOriginalExpiryDeniesWithoutReceipt() throws Exception {
    var context = context();
    var original = finalized(context, 1800);
    try (var lock = context.admin().getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      lock.setAutoCommit(false);
      UUID account =
          DSL.using(lock)
              .fetchSingle(
                  "SELECT account_uuid FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
                  request(original))
              .get(0, UUID.class);
      DSL.using(lock)
          .fetchSingle(
              "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", account);
      var entered = new CountDownLatch(1);
      var pid = new AtomicInteger();
      var attempt =
          executor.submit(
              () ->
                  callerTransaction(
                      context,
                      connection -> {
                        pid.set(
                            DSL.using(connection)
                                .fetchSingle("SELECT pg_backend_pid()")
                                .get(0, Integer.class));
                        entered.countDown();
                        return invoke(
                            connection,
                            context,
                            "confirm",
                            request(original),
                            original.evidence().sha256(),
                            original.decision());
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      // Establish actual lock waiting, rather than assuming a thread-start latch proves it.
      boolean waiting = false;
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!waiting && System.nanoTime() < until) {
        waiting =
            Boolean.TRUE.equals(
                context
                    .dsl()
                    .fetchSingle(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')",
                        pid.get())
                    .get(0, Boolean.class));
        if (!waiting) Thread.sleep(10);
      }
      assertThat(waiting).isTrue();
      waitPastExpiry(context, original);
      lock.rollback();
      assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
          .satisfies(
              failure -> {
                assertThat(sqlState(failure)).isEqualTo("23514");
                assertThat(failure.getCause())
                    .hasMessageContaining("prototype unchanged deadline expired");
              });
    }
    assertThat(context.dsl().fetchCount(DSL.table(context.proofSchema() + ".receipts"))).isZero();
  }

  @Test
  void catalogChurnBeforeFinalizationDoesNotPermitWarmingAwayColdCoverageFailure()
      throws Exception {
    var context = context();
    // Real catalog churn creates dead catalog tuples. It does not prove PostgreSQL will prune a
    // specific pg_class/pg_attribute page at function entry; exact PRUNE coverage remains unproved.
    for (int index = 0; index < 30; index++) {
      context
          .dsl()
          .execute("CREATE TABLE prototype_catalog_" + index + "(id integer, payload text)");
      context.dsl().execute("DROP TABLE prototype_catalog_" + index);
    }
    var original = finalized(context, 15000);
    JSONB created = call(context, original, "confirm");
    assertProof(context, original, created);
    assertThat(call(context, original, "read_receipt")).isEqualTo(created);
  }

  private static Context context() {
    String schema = "protected_" + UUID.randomUUID().toString().replace("-", "");
    String proof = schema + "_proof";
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
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(admin), SQLDialect.POSTGRES);
    try (var resource =
        Objects.requireNonNull(
            AccountGameplayAdmissionProtectedEntryPrototypeIntegrationTest.class
                .getResourceAsStream("/account-admission-protected-entry-prototype.sql"))) {
      dsl.execute(
          new String(resource.readAllBytes(), StandardCharsets.UTF_8)
              .replace("__S__", schema)
              .replace("__P__", proof));
    } catch (IOException failure) {
      throw new IllegalStateException("Test-only protected entry fixture unavailable", failure);
    }
    assertThat(
            dsl.fetchSingle("SELECT current_setting('server_version_num')::integer")
                .get(0, Integer.class))
        .isBetween(160000, 169999);
    return new Context(
        proof, admin, source(null, proof + "_caller", "isolated-prototype-only"), dsl, transaction);
  }

  private static DriverManagerDataSource source(String schema, String username, String password) {
    var source = new DriverManagerDataSource();
    source.setUrl(
        POSTGRES.getJdbcUrl()
            + (schema == null
                ? ""
                : (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema));
    source.setUsername(username);
    source.setPassword(password);
    return source;
  }

  private static Original finalized(Context context, long lifetime) {
    var evidence = pending(context, lifetime);
    UUID decision = UUID.randomUUID();
    new AccountGameplayAdmissionOriginalCommitExecutor(context.admin()).execute(evidence, decision);
    return new Original(evidence, decision);
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

  private static JSONB call(Context context, Original original, String entry) throws SQLException {
    return callerTransaction(
        context,
        connection ->
            invoke(
                connection,
                context,
                entry,
                request(original),
                original.evidence().sha256(),
                original.decision()));
  }

  private static JSONB invoke(
      Connection connection, Context context, String entry, UUID request, String sha, UUID decision)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT " + context.proofSchema() + "." + entry + "(?, ?, ?)")) {
      statement.setObject(1, request);
      statement.setString(2, sha);
      statement.setObject(3, decision);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        JSONB receipt = JSONB.valueOf(result.getString(1));
        assertThat(result.next()).isFalse();
        return receipt;
      }
    }
  }

  private static <T> T callerTransaction(Context context, SqlAction<T> action) throws SQLException {
    try (var connection = context.caller().getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      try {
        T result = action.execute(connection);
        connection.commit();
        return result;
      } catch (Exception failure) {
        connection.rollback();
        if (failure instanceof SQLException sql) throw sql;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(failure);
      }
    }
  }

  private static void assertProof(Context context, Original original, JSONB receipt) {
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT (r.original_operation = to_jsonb(o)) AND r.finalization_xid = o.finalization_xid AND r.confirmation_xid <> o.finalization_xid AND pg_visible_in_snapshot(o.finalization_xid::xid8, r.snapshot::pg_snapshot) AND r.flush_lsn >= r.insert_lsn AND r.committed_before_ms < o.expires_at_ms AND r.expires_at_ms = o.expires_at_ms AND to_jsonb(r) = ?::jsonb FROM "
                        + context.proofSchema()
                        + ".receipts r JOIN account_gameplay_admission_lease_operations o USING(request_id) WHERE r.request_id = ?",
                    receipt.data(),
                    request(original))
                .get(0, Boolean.class))
        .isTrue();
  }

  private static void assertProductionReceiptTablesEmpty(Context context) {
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
    assertThat(
            context
                .dsl()
                .fetchCount(DSL.table("account_gameplay_admission_original_commit_ack_receipts")))
        .isZero();
  }

  private static void waitPastExpiry(Context context, Original original) {
    context
        .dsl()
        .fetchSingle(
            "SELECT pg_sleep(GREATEST(0, (? - ceil(extract(epoch FROM clock_timestamp()) * 1000) + 50) / 1000.0))",
            expiry(original));
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql) return sql.getSQLState();
    return null;
  }

  private static UUID request(Original original) {
    return request(original.evidence());
  }

  private static UUID request(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("requestId"));
  }

  private static long expiry(Original original) {
    return Long.parseLong((String) original.evidence().carrier().get("expiresAt"));
  }

  @FunctionalInterface
  private interface SqlAction<T> {
    T execute(Connection connection) throws SQLException;
  }

  private record Original(AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {}

  private record Context(
      String proofSchema,
      DriverManagerDataSource admin,
      DriverManagerDataSource caller,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
