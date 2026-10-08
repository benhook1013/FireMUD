package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Isolated investigation, not production extension adoption or admission proof.
 *
 * <p>Actual unchanged original/receipt transactions retain test-only lower search locators.
 * Synthetic classifier cases explicitly do not prove physical wraparound, promotion, recycle,
 * unrelated unflushed tails or insufficient-flush behavior. Production V92 remains unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class AccountGameplayAdmissionWalBoundaryProofIntegrationTest {
  // Deliberately owns its container: never honors the shared fixture's external database override.
  // PG18 is rejected by the unchanged production executors and V92.
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");

  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";
  private static final String RECORD =
      "{\"start_lsn\":\"0/1000020\",\"end_lsn\":\"0/1000048\","
          + "\"xid\":\"100\",\"resource_manager\":\"Transaction\",\"record_type\":\"COMMIT\"}";

  @BeforeAll
  static void installIsolatedExtension() {
    var admin =
        DSL.using(
            source(null, POSTGRES.getUsername(), POSTGRES.getPassword()), SQLDialect.POSTGRES);
    assertThat(
            admin
                .fetchSingle("SELECT current_setting('server_version_num')::integer")
                .get(0, Integer.class))
        .isBetween(160000, 169999);
    // Missing image module fails visibly; do not silently skip or provision another image.
    assertThat(
            admin
                .fetchSingle(
                    "SELECT EXISTS(SELECT 1 FROM pg_available_extensions WHERE name = 'pg_walinspect')")
                .get(0, Boolean.class))
        .isTrue();
    admin.execute("CREATE SCHEMA wal_proof_extension");
    admin.execute("REVOKE ALL ON SCHEMA wal_proof_extension FROM PUBLIC");
    admin.execute("CREATE EXTENSION pg_walinspect WITH SCHEMA wal_proof_extension VERSION '1.1'");
  }

  @Test
  void locatorsCommitAndRollBackWithTheirExactOriginalAndReceiptTransactions() {
    var context = context();
    var evidence = pending(context);
    UUID decision = UUID.randomUUID();
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              repository.recordCommitted(evidence, decision);
              assertThat(context.dsl().fetchCount(DSL.table("wal_proof_locators"))).isEqualTo(1);
              assertThat(independent(context).fetchCount(DSL.table("wal_proof_locators"))).isZero();
              assertThat(operation(independent(context), evidence).get("status", String.class))
                  .isEqualTo("PENDING");
              status.setRollbackOnly();
            });
    assertThat(independent(context).fetchCount(DSL.table("wal_proof_locators"))).isZero();
    var acknowledgement =
        new AccountGameplayAdmissionOriginalCommitExecutor(context.source())
            .execute(evidence, decision);
    var originalLocator = locator(context, evidence, "original");
    assertThat(originalLocator.get("target_xid", String.class))
        .isEqualTo(acknowledgement.finalizationXid());
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              new AccountGameplayAdmissionOriginalAckReceiptRepository(context.dsl(), repository)
                  .create(acknowledgement);
              assertThat(context.dsl().fetchCount(DSL.table("wal_proof_locators"))).isEqualTo(2);
              assertThat(independent(context).fetchCount(DSL.table("wal_proof_locators")))
                  .isEqualTo(1);
              assertThat(
                      independent(context)
                          .fetchCount(
                              DSL.table("account_gameplay_admission_original_commit_ack_receipts")))
                  .isZero();
              status.setRollbackOnly();
            });
    assertThat(independent(context).fetchCount(DSL.table("wal_proof_locators"))).isEqualTo(1);
    assertThat(locator(context, evidence, "original").intoMap())
        .isEqualTo(originalLocator.intoMap());
    // No receipt COMMIT or original-bound receipt has been manufactured by this experiment.
    var proof = read(context, evidence, decision, "original");
    assertThat(proof.get("original_bound", Long.class)).isNull();
    assertCovered(context, proof);
  }

  @Test
  void freshConnectionLocatesExactReceiptCommitAfterLostAcknowledgementWithoutRestamping() {
    var context = context();
    var evidence = pending(context);
    UUID decision = UUID.randomUUID();
    var loseNextCommit = new AtomicBoolean();
    var source = lostAcknowledgementSource(context.source(), loseNextCommit);
    var acknowledgement =
        new AccountGameplayAdmissionOriginalCommitExecutor(source).execute(evidence, decision);
    loseNextCommit.set(true);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(source)
                    .confirm(acknowledgement))
        .hasMessageContaining("Account receipt physical COMMIT unavailable");
    var operationBefore = operation(independent(context), evidence).intoMap();
    var receiptBefore = receipt(context, evidence).intoMap();
    var locatorsBefore =
        independent(context).fetch("SELECT * FROM wal_proof_locators ORDER BY phase").intoMaps();
    assertThat(locatorsBefore).hasSize(2);
    assertThat(locator(context, evidence, "receipt").get("target_xid", String.class))
        .isEqualTo(receiptBefore.get("receipt_xid"))
        .isNotEqualTo(acknowledgement.finalizationXid());
    // The probe uses its restricted independent connection, not V92 or a fresh confirming write.
    var receiptProof = read(context, evidence, decision, "receipt");
    var originalProof = read(context, evidence, decision, "original");
    assertCovered(context, receiptProof);
    assertCovered(context, originalProof);
    assertThat(receiptProof.get("original_bound", Long.class))
        .isEqualTo(acknowledgement.committedBeforeMs());
    assertThat(receiptProof.get("unchanged_expiry", Long.class)).isEqualTo(expiry(evidence));
    assertThat(operation(independent(context), evidence).intoMap()).isEqualTo(operationBefore);
    assertThat(receipt(context, evidence).intoMap()).isEqualTo(receiptBefore);
    assertThat(
            independent(context)
                .fetch("SELECT * FROM wal_proof_locators ORDER BY phase")
                .intoMaps())
        .isEqualTo(locatorsBefore);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(source)
                    .execute(evidence, decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
  }

  @Test
  void physicalOriginalCommitAndLocatorCannotReplaceLostOriginalAcknowledgement() {
    var context = context();
    var evidence = pending(context);
    UUID decision = UUID.randomUUID();
    var source = lostAcknowledgementSource(context.source(), new AtomicBoolean(true));
    var executor = new AccountGameplayAdmissionOriginalCommitExecutor(source);
    assertThatThrownBy(() -> executor.execute(evidence, decision))
        .hasMessageContaining("Original Account physical COMMIT unavailable");
    assertThat(operation(independent(context), evidence).get("status", String.class))
        .isEqualTo("COMMITTED");
    assertThat(locator(context, evidence, "original")).isNotNull();
    var diagnostic = read(context, evidence, decision, "original");
    assertCovered(context, diagnostic);
    assertThat(diagnostic.get("original_bound", Long.class)).isNull();
    assertThat(
            independent(context)
                .fetchCount(DSL.table("account_gameplay_admission_original_commit_ack_receipts")))
        .isZero();
    assertThatThrownBy(() -> executor.execute(evidence, decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
    assertThatThrownBy(() -> read(context, evidence, decision, "receipt"))
        .hasMessageContaining("locator unavailable");
  }

  @Test
  void restrictedReaderCannotUseRawWalOrMutateLocatorsAndRevocationDeniesProof() {
    var context = context();
    var evidence = pending(context);
    UUID decision = UUID.randomUUID();
    new AccountGameplayAdmissionOriginalCommitExecutor(context.source())
        .execute(evidence, decision);
    var reader = DSL.using(context.reader(), SQLDialect.POSTGRES);
    assertThat(
            reader
                .fetchSingle("SELECT pg_has_role(current_user, 'pg_read_server_files', 'MEMBER')")
                .get(0, Boolean.class))
        .isFalse();
    assertThatThrownBy(
            () ->
                reader.fetch(
                    "SELECT * FROM wal_proof_extension.pg_get_wal_records_info('0/1000000','0/1000048')"))
        .hasMessageContaining("permission denied");
    // Schema USAGE alone does not grant direct function EXECUTE.
    context
        .dsl()
        .execute("GRANT USAGE ON SCHEMA wal_proof_extension TO " + context.schema() + "_reader");
    assertThatThrownBy(
            () ->
                reader.fetch(
                    "SELECT * FROM wal_proof_extension.pg_get_wal_records_info('0/1000000','0/1000048')"))
        .hasMessageContaining("permission denied for function");
    assertThatThrownBy(() -> reader.fetch("SELECT * FROM wal_proof_locators"))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> reader.execute("UPDATE wal_proof_locators SET target_xid = '1'"))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> reader.execute("SET ROLE " + context.schema() + "_owner"))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                reader.execute(
                    "ALTER TABLE account_gameplay_admission_lease_operations DISABLE TRIGGER wal_proof_original_capture"))
        .hasMessageContaining("must be owner");
    assertCovered(context, read(context, evidence, decision, "original"));
    assertThatThrownBy(() -> read(context, evidence, UUID.randomUUID(), "original"))
        .hasMessageContaining("identity unavailable");
    assertThatThrownBy(() -> read(context, changedDigest(evidence), decision, "original"))
        .hasMessageContaining("identity unavailable");
    context
        .dsl()
        .execute(
            "REVOKE EXECUTE ON FUNCTION wal_proof_extension.pg_get_wal_records_info(pg_lsn, pg_lsn) FROM "
                + context.schema()
                + "_owner");
    assertThatThrownBy(() -> read(context, evidence, decision, "original"))
        .hasMessageContaining("permission denied for function");
  }

  @Test
  void realSearchEndingBeforeTargetCommitHasNoCandidateAndMissingExtensionDenies() {
    var context = context();
    var evidence = pending(context);
    UUID decision = UUID.randomUUID();
    new AccountGameplayAdmissionOriginalCommitExecutor(context.source())
        .execute(evidence, decision);
    String lower = locator(context, evidence, "original").get("search_lower_lsn", String.class);
    var records =
        context
            .dsl()
            .fetchOne(
                "SELECT coalesce(jsonb_agg(to_jsonb(r)), '[]'::jsonb) AS records "
                    + "FROM wal_proof_extension.pg_get_wal_records_info(?::pg_lsn, ?::pg_lsn) r",
                lower,
                lower);
    assertThat(records.get("records", JSONB.class).data()).isEqualTo("[]");
    assertThatThrownBy(() -> classify(context, "100", "101", lower, lower, lower, "[]"))
        .hasMessageContaining("COMMIT unavailable");
    // Class-local owned container only; restored for subsequent tests. No live database option.
    context.dsl().execute("DROP EXTENSION pg_walinspect");
    try {
      assertThatThrownBy(() -> read(context, evidence, decision, "original"))
          .hasMessageContaining("environment unavailable");
    } finally {
      context
          .dsl()
          .execute("CREATE EXTENSION pg_walinspect WITH SCHEMA wal_proof_extension VERSION '1.1'");
    }
  }

  @Test
  void syntheticRecordClassifierRejectsEpochCollisionAmbiguityUncoveredAndUnboundedEvidence() {
    var context = context();
    var accepted =
        classify(context, "100", "101", "0/1000000", "0/1000080", "0/1000080", "[" + RECORD + "]");
    assertThat(accepted.get("record_end", String.class)).isEqualTo("0/1000048");
    for (String records :
        List.of(
            "[]",
            "[" + RECORD + "," + RECORD + "]",
            "[" + RECORD.replace("\"COMMIT\"", "\"ABORT\"") + "]",
            "[" + RECORD.replace("\"100\"", "\"101\"") + "]",
            "[" + RECORD.replace("\"Transaction\"", "\"Heap\"") + "]",
            "[{\"xid\":\"100\"}]",
            "[" + String.join(",", java.util.Collections.nCopies(257, RECORD)) + "]")) {
      assertThatThrownBy(
              () -> classify(context, "100", "101", "0/1000000", "0/1000080", "0/1000080", records))
          .hasMessageContaining("WAL proof");
    }
    assertThatThrownBy(
            () ->
                classify(
                    context,
                    "4294967396",
                    "101",
                    "0/1000000",
                    "0/1000080",
                    "0/1000080",
                    "[" + RECORD + "]"))
        .hasMessageContaining("full XID unavailable");
    assertThatThrownBy(
            () ->
                classify(
                    context,
                    "100",
                    "4294967397",
                    "0/1000000",
                    "0/1000080",
                    "0/1000080",
                    "[" + RECORD + "]"))
        .hasMessageContaining("full XID unavailable");
    assertThatThrownBy(
            () ->
                classify(
                    context,
                    "100",
                    "101",
                    "0/1000000",
                    "0/1200000",
                    "0/1200000",
                    "[" + RECORD + "]"))
        .hasMessageContaining("search bound");
    assertThatThrownBy(
            () ->
                classify(
                    context,
                    "100",
                    "101",
                    "0/1000000",
                    "0/1000080",
                    "0/1000040",
                    "[" + RECORD + "]"))
        .hasMessageContaining("COMMIT unavailable");
    assertThatThrownBy(
            () ->
                classify(
                    context,
                    "100",
                    "101",
                    "0/1000000",
                    "0/1000040",
                    "0/1000080",
                    "[" + RECORD + "]"))
        .hasMessageContaining("outside search bound");
    // Actual WAL read error on an unsupported bootstrap location, not physical recycle proof.
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .fetch("SELECT * FROM wal_proof_extension.pg_get_wal_record_info('0/0')"))
        .hasMessageContaining("could not read WAL");
  }

  @Test
  void syntheticEnvironmentClassifierRejectsVersionsCapabilitiesAndClusterTimelineChanges() {
    var context = context();
    for (Object[] values :
        List.of(
            new Object[] {180000, false, "on", "1.1", "cluster", "cluster", 1L, 1L},
            new Object[] {150000, false, "on", "1.1", "cluster", "cluster", 1L, 1L},
            new Object[] {160000, true, "on", "1.1", "cluster", "cluster", 1L, 1L},
            new Object[] {160000, false, "off", "1.1", "cluster", "cluster", 1L, 1L},
            new Object[] {160000, false, "on", "1.0", "cluster", "cluster", 1L, 1L},
            new Object[] {160000, false, "on", "1.1", "cluster", "other", 1L, 1L},
            new Object[] {160000, false, "on", "1.1", "cluster", "cluster", 1L, 2L})) {
      assertThatThrownBy(
              () ->
                  context
                      .dsl()
                      .fetch(
                          "SELECT wal_proof_environment(?::integer, ?::boolean, ?::text, ?::text, ?::text, ?::text, ?::bigint, ?::bigint)",
                          values))
          .hasMessageContaining("environment unavailable");
    }
  }

  private static Record classify(
      Context context,
      String xid,
      String xmax,
      String lower,
      String upper,
      String flush,
      String records) {
    return context
        .dsl()
        .fetchOne(
            "SELECT * FROM wal_proof_classify(?, ?, ?::pg_lsn, ?::pg_lsn, ?::pg_lsn, ?::jsonb)",
            xid,
            xmax,
            lower,
            upper,
            flush,
            records);
  }

  private static Record read(
      Context context,
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID decision,
      String phase) {
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(context.reader()));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(true);
    return transaction.execute(
        ignored -> {
          var dsl =
              DSL.using(new TransactionAwareDataSourceProxy(context.reader()), SQLDialect.POSTGRES);
          // Set before the proof statement: function-local GUC changes alone are not a timeout
          // proof.
          dsl.execute("SET LOCAL statement_timeout = '2s'");
          return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT * FROM wal_proof_read(?, ?, ?, ?)",
                  request(evidence),
                  phase,
                  evidence.sha256(),
                  decision));
        });
  }

  private static void assertCovered(Context context, Record proof) {
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT ?::pg_lsn < ?::pg_lsn AND ?::pg_lsn <= ?::pg_lsn",
                    proof.get("commit_start", String.class),
                    proof.get("commit_end", String.class),
                    proof.get("commit_end", String.class),
                    proof.get("observed_flush", String.class))
                .get(0, Boolean.class))
        .isTrue();
  }

  private static Record locator(
      Context context, AccountGameplayAdmissionLeaseEvidence evidence, String phase) {
    return independent(context)
        .fetchOne(
            "SELECT * FROM wal_proof_locators WHERE request_id = ? AND phase = ?",
            request(evidence),
            phase);
  }

  private static Record receipt(Context context, AccountGameplayAdmissionLeaseEvidence evidence) {
    return independent(context)
        .fetchOne(
            "SELECT * FROM account_gameplay_admission_original_commit_ack_receipts WHERE request_id = ?",
            request(evidence));
  }

  private static Record operation(DSLContext dsl, AccountGameplayAdmissionLeaseEvidence evidence) {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT * FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
            request(evidence)));
  }

  private static UUID request(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("requestId"));
  }

  private static long expiry(AccountGameplayAdmissionLeaseEvidence evidence) {
    return Long.parseLong((String) evidence.carrier().get("expiresAt"));
  }

  private static AccountGameplayAdmissionLeaseEvidence changedDigest(
      AccountGameplayAdmissionLeaseEvidence original) {
    var carrier = new LinkedHashMap<>(original.carrier());
    carrier.put(
        "evaluatedAt", Long.toString(Long.parseLong((String) carrier.get("evaluatedAt")) + 1));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static DSLContext independent(Context context) {
    return DSL.using(context.source(), SQLDialect.POSTGRES);
  }

  private static DataSource lostAcknowledgementSource(DataSource source, AtomicBoolean armed) {
    return new DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
      }

      private Connection wrap(Connection physical) {
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                    physical.commit();
                    if (armed.compareAndSet(true, false))
                      throw new SQLException("Test lost actual COMMIT acknowledgement", "08006");
                    return null;
                  }
                  try {
                    return method.invoke(physical, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  private static Context context() {
    String schema = "walproof_" + UUID.randomUUID().toString().replace("-", "");
    var source = source(schema, POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try (var resource =
        Objects.requireNonNull(
            AccountGameplayAdmissionWalBoundaryProofIntegrationTest.class.getResourceAsStream(
                "/account-admission-wal-boundary-proof.sql"))) {
      dsl.execute(
          new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("__S__", schema));
    } catch (IOException failure) {
      throw new IllegalStateException("Test-only WAL proof fixture unavailable", failure);
    }
    return new Context(
        schema,
        source,
        source(schema, schema + "_reader", "isolated-proof-only"),
        dsl,
        transaction);
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

  private static AccountGameplayAdmissionLeaseEvidence pending(Context context) {
    return tx(
        context,
        () -> {
          var authorities = new AccountAuthorityGenerationRepository(context.dsl());
          var sources =
              new AccountAuthoritySourceEvidenceRepository(
                  context.dsl(), authorities, new AccountAuthorityOutboxRepository(context.dsl()));
          sources.initializeIssuerIfAbsent("firemud-account-service");
          var account = new Account();
          account.setUsername("walproof-" + UUID.randomUUID());
          account.setEmail(UUID.randomUUID() + "@example.test");
          account.setPasswordHash("storage-fixture-only");
          account.setLoginAuthModes("PASSWORD");
          UUID accountId =
              new AccountRepository(context.dsl(), sources).save(account).getAccountUuid();
          var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
          var allocation = repository.allocate(accountId, UUID.randomUUID(), UUID.randomUUID());
          var evidence = evidence(allocation);
          repository.beginPending(evidence);
          return evidence;
        });
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence(
      AccountGameplayAdmissionLeaseRepository.Allocation allocation) {
    String account = allocation.accountId().toString();
    long now = Instant.now().toEpochMilli();
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
    carrier.put("expiresAt", Long.toString(now + 15000));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static <T> T tx(Context context, Supplier<T> action) {
    return context.transaction().execute(ignored -> action.get());
  }

  private record Context(
      String schema,
      DriverManagerDataSource source,
      DriverManagerDataSource reader,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
