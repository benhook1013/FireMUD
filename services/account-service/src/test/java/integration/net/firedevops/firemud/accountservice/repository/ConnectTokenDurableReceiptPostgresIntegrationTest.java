package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ConnectTokenDurableReceiptPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void committedReceiptAndCiphertextAreImmutableAndCannotBeReminted() {
    DSLContext db = database();
    UUID id = claim(db, UUID.randomUUID(), "original", 60);
    envelope(db, id);
    db.execute(
        "UPDATE connect_token_issuance_operation SET status='COMMITTED', token_jti=?,"
            + "token_hash=?, replay_admission_fence=1, issuance_evidence=? WHERE operation_id=?",
        UUID.randomUUID(),
        digest('b'),
        new byte[] {1, 2},
        id);
    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_issuance_operation SET request_digest=? WHERE operation_id=?",
                    digest('c'),
                    id))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_issuance_operation SET token_jti=? WHERE operation_id=?",
                    UUID.randomUUID(),
                    id))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_response_envelope SET encrypted_response=? WHERE operation_id=?",
                    new byte[] {9},
                    id))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .hasMessageContaining("held");
    assertThatThrownBy(
            () ->
                db.execute("DELETE FROM connect_token_issuance_operation WHERE operation_id=?", id))
        .hasMessageContaining("safe-watermark");
  }

  @Test
  void terminalOutcomeWithoutEnvelopeRollsBackAndAbortedEvidenceRemainsHeld() {
    DSLContext db = database();
    UUID id = claim(db, UUID.randomUUID(), "missing", 60);
    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_issuance_operation SET status='FAILED',failure_code='JOIN_REQUIRED' WHERE operation_id=?",
                    id))
        .hasMessageContaining("durably recorded");
    assertThat(
            db.fetchValue(
                "SELECT status FROM connect_token_issuance_operation WHERE operation_id=?", id))
        .isEqualTo("PENDING");
    envelope(db, id);
    db.execute(
        "UPDATE connect_token_issuance_operation SET status='ABORTED',failure_code='RECONCILIATION_REQUIRED' WHERE operation_id=?",
        id);
    assertThatThrownBy(
            () ->
                db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .hasMessageContaining("held");
  }

  @Test
  void expiredFailureEnvelopeCanBeRemovedOnlyAfterReconciliationAndNeverRecreated() {
    DSLContext db = database();
    UUID id = claim(db, UUID.randomUUID(), "failure", 1);
    envelope(db, id);
    db.execute(
        "UPDATE connect_token_issuance_operation SET status='FAILED',failure_code='JOIN_REQUIRED' WHERE operation_id=?",
        id);
    assertThatThrownBy(
            () ->
                db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .hasMessageContaining("held");
    db.execute("SELECT pg_sleep(1.1)");
    assertThatThrownBy(
            () ->
                db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .hasMessageContaining("held");
    db.execute(
        "UPDATE connect_token_issuance_operation SET reconciliation_blocked=FALSE,reconciliation_evidence=? WHERE operation_id=?",
        new byte[] {3},
        id);
    assertThat(db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .isEqualTo(1);
    assertThat(
            db.fetchValue(
                "SELECT request_digest FROM connect_token_issuance_operation WHERE operation_id=?",
                id))
        .isEqualTo(digest('a'));
    assertThatThrownBy(() -> envelope(db, id)).hasMessageContaining("cannot be replaced");
    assertThatThrownBy(
            () ->
                db.execute(
                    "UPDATE connect_token_issuance_operation SET status='PENDING',terminal_at=NULL,failure_code=NULL WHERE operation_id=?",
                    id))
        .hasMessageContaining("immutable");
  }

  @Test
  void concurrentClaimsSerializeOnExactIdentityAndRetainOneDigest() throws Exception {
    DSLContext db = database();
    UUID account = account(db);
    UUID tenant = UUID.randomUUID();
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                db.transaction(
                    configuration -> {
                      insert(
                          DSL.using(configuration),
                          UUID.randomUUID(),
                          account,
                          tenant,
                          "same",
                          digest('a'),
                          60);
                      claimed.countDown();
                      try {
                        if (!release.await(10, TimeUnit.SECONDS))
                          throw new IllegalStateException("test release timeout");
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                      }
                    }));
    assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Integer> second =
        CompletableFuture.supplyAsync(
            () -> insert(db, UUID.randomUUID(), account, tenant, "same", digest('b'), 60));
    try {
      boolean blocked = false;
      for (int i = 0; i < 100 && !blocked; i++) {
        blocked =
            Boolean.TRUE.equals(
                Objects.requireNonNull(
                        db.fetchOne(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' "
                                + "AND query LIKE 'INSERT INTO connect_token_issuance_operation%')"))
                    .get(0, Boolean.class));
        if (!blocked) Thread.sleep(20);
      }
      assertThat(blocked).as("the second claim actually waits on the first transaction").isTrue();
      release.countDown();
      first.get(15, TimeUnit.SECONDS);
      assertThat(second.get(15, TimeUnit.SECONDS)).isZero();
      assertThat(db.fetchValue("SELECT count(*) FROM connect_token_issuance_operation"))
          .isEqualTo(1L);
      assertThat(db.fetchValue("SELECT request_digest FROM connect_token_issuance_operation"))
          .isEqualTo(digest('a'));
    } finally {
      release.countDown();
    }
  }

  @Test
  void successfulEnvelopeExpiryPreservesReceiptAndRejectsRemint() {
    DSLContext db = database();
    UUID id = claim(db, UUID.randomUUID(), "success", 1);
    db.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          envelope(tx, id);
          tx.execute(
              "UPDATE connect_token_issuance_operation SET status='COMMITTED',token_jti=?,"
                  + "token_hash=?,replay_admission_fence=1,issuance_evidence=?,reconciliation_blocked=FALSE,"
                  + "reconciliation_evidence=? WHERE operation_id=?",
              UUID.randomUUID(),
              digest('b'),
              new byte[] {1},
              new byte[] {2},
              id);
        });
    assertThatThrownBy(
            () ->
                db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .hasMessageContaining("held");
    db.execute("SELECT pg_sleep(1.1)");
    assertThat(db.execute("DELETE FROM connect_token_response_envelope WHERE operation_id=?", id))
        .isEqualTo(1);
    assertThat(
            db.fetchValue(
                "SELECT status FROM connect_token_issuance_operation WHERE operation_id=?", id))
        .isEqualTo("COMMITTED");
    assertThatThrownBy(() -> envelope(db, id)).hasMessageContaining("cannot be replaced");
  }

  @Test
  void missingResponseAtTransactionCommitRollsBackClaimAndOutcomeTogether() {
    DSLContext db = database();
    UUID account = account(db);
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                db.transaction(
                    configuration -> {
                      DSLContext tx = DSL.using(configuration);
                      insert(tx, id, account, UUID.randomUUID(), "rollback", digest('a'), 60);
                      tx.execute(
                          "UPDATE connect_token_issuance_operation SET status='FAILED',failure_code='JOIN_REQUIRED' WHERE operation_id=?",
                          id);
                    }))
        .rootCause()
        .hasMessageContaining("durably recorded");
    assertThat(
            db.fetchValue(
                "SELECT count(*) FROM connect_token_issuance_operation WHERE operation_id=?", id))
        .isEqualTo(0L);
  }

  private static UUID claim(DSLContext db, UUID tenant, String request, int horizonSeconds) {
    UUID id = UUID.randomUUID();
    assertThat(insert(db, id, account(db), tenant, request, digest('a'), horizonSeconds))
        .isEqualTo(1);
    return id;
  }

  private static int insert(
      DSLContext db,
      UUID id,
      UUID account,
      UUID tenant,
      String request,
      String digest,
      int horizonSeconds) {
    return db.execute(
        "INSERT INTO connect_token_issuance_operation "
            + "(operation_id,account_uuid,tenant_uuid,connect_scope_id,request_id,request_digest_version,request_digest,status,"
            + "response_recovery_expires_at,receipt_retain_until) VALUES (?,?,?,'scope',?,1,?,'PENDING',"
            + "CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),CURRENT_TIMESTAMP + INTERVAL '1 hour') "
            + "ON CONFLICT (account_uuid,tenant_uuid,connect_scope_id,request_id) DO NOTHING",
        id,
        account,
        tenant,
        request,
        digest,
        horizonSeconds);
  }

  private static void envelope(DSLContext db, UUID id) {
    db.execute(
        "INSERT INTO connect_token_response_envelope VALUES (?, 'test-key',1,?,?)",
        id,
        new byte[] {4, 5},
        new byte[] {6, 7});
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID account(DSLContext db) {
    String value = UUID.randomUUID().toString();
    return Objects.requireNonNull(
            db.fetchOne(
                "INSERT INTO accounts (username,email,password_hash) VALUES (?,?,?) RETURNING account_uuid",
                value,
                value + "@example.test",
                "test-hash"))
        .get(0, UUID.class);
  }

  private static DSLContext database() {
    String schema = "connect_receipt_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return DSL.using(source, SQLDialect.POSTGRES);
  }
}
