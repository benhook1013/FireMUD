package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceCaptureRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.ConnectTokenReceiptClassifier.Lookup;
import net.firedevops.firemud.accountservice.repository.ConnectTokenRequestFingerprint;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountConnectIssuanceFenceCapturePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void firstCaptureAndExactRetryPreserveTheOriginalReceipt() {
    DSLContext db = database("1051.1");
    UUID operation = UUID.randomUUID();
    UUID account = createAccount(db).getAccountUuid();
    Lookup identity = insertPending(db, operation, account);
    IssuanceFence baseline = new IssuanceFence(account, 1, 1);

    assertThat(read(db, operation, identity)).isEmpty();
    assertThatThrownBy(
            () ->
                new AccountConnectIssuanceFenceCaptureRepository(db)
                    .capture(operation, identity, baseline))
        .hasMessageContaining("existing Account transaction");
    assertThatThrownBy(() -> capture(db, operation, identity, new IssuanceFence(account, 1, 2)))
        .hasMessageContaining("differs or lacks provenance");
    assertThat(read(db, operation, identity)).isEmpty();

    var captured = capture(db, operation, identity, baseline);
    assertThat(captured.operationId()).isEqualTo(operation);
    assertThat(captured.identity()).isEqualTo(identity);
    assertThat(captured.fence()).isEqualTo(baseline);
    assertThat(captured.digest()).matches("[0-9a-f]{64}");
    String after = receipt(db, operation);
    assertThat(capture(db, operation, identity, baseline)).isEqualTo(captured);
    assertThat(read(db, operation, identity)).contains(captured);
    assertThat(receipt(db, operation)).isEqualTo(after);
    assertThat(db.fetchValue("SELECT count(*) FROM connect_token_response_envelope")).isEqualTo(0L);
  }

  @Test
  void concurrentExactCaptureWaitsForThePendingReceiptAndReadsTheWinner() throws Exception {
    DSLContext db = database("1051.1");
    UUID operation = UUID.randomUUID();
    UUID account = createAccount(db).getAccountUuid();
    Lookup identity = insertPending(db, operation, account);
    IssuanceFence fence = new IssuanceFence(account, 1, 1);
    String schema = db.fetchSingle("SELECT current_schema()").get(0, String.class);

    try (var executor = Executors.newSingleThreadExecutor()) {
      CompletableFuture<AccountConnectIssuanceFenceEvidence> retry =
          db.transactionResult(
              configuration -> {
                DSLContext tx = DSL.using(configuration);
                int blocker = tx.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
                new AccountConnectIssuanceFenceCaptureRepository(tx)
                    .capture(operation, identity, fence);
                CompletableFuture<AccountConnectIssuanceFenceEvidence> pending =
                    CompletableFuture.supplyAsync(
                        () -> capture(db, operation, identity, fence), executor);
                boolean blocked = false;
                for (int i = 0; i < 100 && !blocked; i++) {
                  blocked =
                      Boolean.TRUE.equals(
                          db.fetchSingle(
                                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                                      + "WHERE wait_event_type='Lock' AND application_name=? "
                                      + "AND query LIKE 'SELECT status, issuance_fence_capture_schema%' "
                                      + "AND ? = ANY(pg_blocking_pids(pid)))",
                                  schema, blocker)
                              .get(0, Boolean.class));
                  if (!blocked) Thread.sleep(20);
                }
                assertThat(blocked).as("exact retry waits for the receipt lock").isTrue();
                return pending;
              });
      assertThat(read(db, operation, identity)).contains(retry.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void changedCurrentFenceCannotReplaceTheOriginalAndOnlyExactHistoryReadsBack() {
    DSLContext db = database("1051.1");
    Account account = createAccount(db);
    UUID operation = UUID.randomUUID();
    Lookup identity = insertPending(db, operation, account.getAccountUuid());
    IssuanceFence baseline = new IssuanceFence(account.getAccountUuid(), 1, 1);
    var captured = capture(db, operation, identity, baseline);

    account.setEmailVerified(true);
    saveAccount(db, account);
    IssuanceFence current = new IssuanceFence(account.getAccountUuid(), 2, 2);
    String after = receipt(db, operation);
    assertThatThrownBy(() -> capture(db, operation, identity, baseline))
        .hasMessageContaining("differs or lacks provenance");
    assertThatThrownBy(() -> capture(db, operation, identity, current))
        .hasMessageContaining("captured with different evidence");

    Lookup changedDigest =
        new Lookup(
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeId(),
            identity.requestId(),
            identity.digestVersion(),
            "sha256:" + "1".repeat(64));
    Lookup changedScope =
        ConnectTokenRequestFingerprint.lookup(
            identity.accountId(), identity.tenantId(), "other scope", identity.requestId());
    assertThatThrownBy(() -> read(db, operation, changedDigest))
        .hasMessageContaining("identity differs");
    assertThatThrownBy(() -> capture(db, operation, changedScope, current))
        .hasMessageContaining("identity differs");
    assertThatThrownBy(() -> read(db, UUID.randomUUID(), identity))
        .hasMessageContaining("is missing");
    assertThat(read(db, operation, identity)).contains(captured);
    assertThat(receipt(db, operation)).isEqualTo(after);

    abort(db, operation);
    assertThat(read(db, operation, identity)).contains(captured);
    assertThatThrownBy(() -> capture(db, operation, identity, current))
        .hasMessageContaining("requires a pending operation");
    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_issuance_operation SET issuance_fence_capture_digest=? "
                        + "WHERE operation_id=?",
                    new byte[32],
                    operation))
        .hasMessageContaining("capture is immutable");
  }

  @Test
  void missingAuthorityCannotPopulateCapture() {
    DSLContext db = database("1051.1");
    UUID operation = UUID.randomUUID();
    UUID retainedAccount =
        db.fetchSingle(
                "INSERT INTO accounts(username,email,password_hash) "
                    + "VALUES ('retained-account','retained@example.test','fixture') RETURNING account_uuid")
            .get(0, UUID.class);
    Lookup identity = insertPending(db, operation, retainedAccount);

    assertThatThrownBy(
            () -> capture(db, operation, identity, new IssuanceFence(retainedAccount, 1, 1)))
        .hasMessageContaining("fence is unavailable");
    assertThat(read(db, operation, identity)).isEmpty();
  }

  @Test
  void migrationPreservesUncapturedPendingAndTerminalRowsAndExistingReplayEvidence() {
    DSLContext db = database("1051");
    UUID account = createAccount(db).getAccountUuid();
    UUID pending = UUID.randomUUID();
    UUID committed = UUID.randomUUID();
    Lookup pendingIdentity = insertPending(db, pending, account);
    Lookup committedIdentity = insertPending(db, committed, account);
    db.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute(
              "INSERT INTO connect_token_response_envelope VALUES (?, 'fixture-key',1,?,?)",
              committed,
              new byte[] {1},
              new byte[] {2});
          tx.execute(
              "UPDATE connect_token_issuance_operation SET status='COMMITTED',token_jti=?,"
                  + "token_hash=?,replay_admission_fence=77,issuance_evidence=? WHERE operation_id=?",
              UUID.randomUUID(),
              "sha256:" + "a".repeat(64),
              new byte[] {3},
              committed);
        });
    var receiptRows = previousReceiptRows(db);
    var envelopes = envelopeRows(db);

    String schema = db.fetchSingle("SELECT current_schema()").get(0, String.class);
    migration(schema).target("1051.1").load().migrate();
    assertThat(previousReceiptRows(db)).isEqualTo(receiptRows);
    assertThat(envelopeRows(db)).isEqualTo(envelopes);
    assertThat(read(db, pending, pendingIdentity)).isEmpty();
    assertThat(read(db, committed, committedIdentity)).isEmpty();
    IssuanceFence baseline = new IssuanceFence(account, 1, 1);
    assertThatThrownBy(() -> capture(db, committed, committedIdentity, baseline))
        .hasMessageContaining("requires a pending operation");

    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_issuance_operation SET "
                        + "issuance_fence_capture_schema='account-connect-issuance-fence/v1',"
                        + "issuance_fence_capture_account_uuid=account_uuid,issuance_fence_capture_value=1,"
                        + "issuance_fence_capture_source_version=1,issuance_fence_capture_digest=? "
                        + "WHERE operation_id=?",
                    new byte[32],
                    committed))
        .hasMessageContaining("requires an existing pending operation");
    assertThat(capture(db, pending, pendingIdentity, baseline).fence()).isEqualTo(baseline);
    assertThat(read(db, committed, committedIdentity)).isEmpty();
  }

  private static Account createAccount(DSLContext db) {
    String name = "capture-" + UUID.randomUUID();
    Account account = new Account();
    account.setUsername(name);
    account.setEmail(name + "@example.test");
    account.setPasswordHash("fixture-hash");
    return saveAccount(db, account);
  }

  private static Account saveAccount(DSLContext db, Account account) {
    String schema = db.fetchSingle("SELECT current_schema()").get(0, String.class);
    DriverManagerDataSource source = dataSource(schema);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    var ownerDsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    return transaction.execute(status -> new AccountRepository(ownerDsl).save(account));
  }

  private static Lookup insertPending(DSLContext db, UUID operation, UUID account) {
    Lookup identity =
        ConnectTokenRequestFingerprint.lookup(
            account, UUID.randomUUID(), "opaque-scope", "requête-雪");
    db.execute(
        "INSERT INTO connect_token_issuance_operation "
            + "(operation_id,account_uuid,tenant_uuid,connect_scope_id,request_id,request_digest_version,"
            + "request_digest,status,response_recovery_expires_at,receipt_retain_until) "
            + "VALUES (?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP + INTERVAL '1 minute',"
            + "CURRENT_TIMESTAMP + INTERVAL '1 hour')",
        operation,
        identity.accountId(),
        identity.tenantId(),
        identity.connectScopeId(),
        identity.requestId(),
        identity.digestVersion(),
        identity.requestDigest());
    return identity;
  }

  private static void abort(DSLContext db, UUID operation) {
    db.execute(
        "UPDATE connect_token_issuance_operation SET status='ABORTED',failure_code='UNKNOWN_COMMIT' "
            + "WHERE operation_id=?",
        operation);
  }

  private static AccountConnectIssuanceFenceEvidence capture(
      DSLContext db, UUID operation, Lookup identity, IssuanceFence fence) {
    return db.transactionResult(
        configuration ->
            new AccountConnectIssuanceFenceCaptureRepository(DSL.using(configuration))
                .capture(operation, identity, fence));
  }

  private static Optional<AccountConnectIssuanceFenceEvidence> read(
      DSLContext db, UUID operation, Lookup identity) {
    return db.transactionResult(
        configuration ->
            new AccountConnectIssuanceFenceCaptureRepository(DSL.using(configuration))
                .read(operation, identity));
  }

  private static String receipt(DSLContext db, UUID operation) {
    return db.fetchSingle(
            "SELECT xmin::text || ':' || to_jsonb(op)::text FROM connect_token_issuance_operation op "
                + "WHERE operation_id=?",
            operation)
        .get(0, String.class);
  }

  private static List<Map<String, Object>> previousReceiptRows(DSLContext db) {
    return db.fetch(
            "SELECT xmin::text, operation_id, account_uuid, tenant_uuid, connect_scope_id, request_id, "
                + "request_digest_version, request_digest, status, token_jti, token_hash, replay_admission_fence, "
                + "encode(issuance_evidence,'hex') AS issuance, failure_code, reconciliation_blocked, "
                + "encode(reconciliation_evidence,'hex') AS reconciliation, "
                + "response_recovery_expires_at, receipt_retain_until, created_at, terminal_at "
                + "FROM connect_token_issuance_operation ORDER BY operation_id")
        .intoMaps();
  }

  private static List<Map<String, Object>> envelopeRows(DSLContext db) {
    return db.fetch(
            "SELECT operation_id, encryption_key_id, encryption_version, "
                + "encode(encrypted_response,'hex') AS response, "
                + "encode(authenticated_binding,'hex') AS binding FROM connect_token_response_envelope")
        .intoMaps();
  }

  private static DSLContext database(String version) {
    String schema = "connect_capture_" + UUID.randomUUID().toString().replace("-", "");
    migration(schema).target(version).load().migrate();
    return DSL.using(dataSource(schema), SQLDialect.POSTGRES);
  }

  private static FluentConfiguration migration(String schema) {
    return Flyway.configure()
        .dataSource(dataSource(schema))
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration");
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Properties properties = new Properties();
    properties.setProperty("ApplicationName", schema);
    source.setConnectionProperties(properties);
    return source;
  }
}
