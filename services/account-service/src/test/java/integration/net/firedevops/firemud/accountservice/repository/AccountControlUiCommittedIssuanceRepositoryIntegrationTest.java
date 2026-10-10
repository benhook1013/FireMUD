package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical PostgreSQL proof for the exact, read-only committed-issuance repository.
 *
 * <p>The SQL fixture advances only the actual V74 row through its guarded storage states. Its
 * opaque payloads establish neither control-ui token authority nor genuine authorization issuance,
 * protected-response encryption, or registry activation.
 */
@SuppressWarnings("resource")
class AccountControlUiCommittedIssuanceRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();
  private final Set<String> schemas = new HashSet<>();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void dropTestOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(postgres.dataSource());
    for (String schema : schemas) {
      if (!schema.matches("account_control_ui_committed_[a-f0-9]{32}")) {
        throw new IllegalStateException("Refusing to dispose an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void readsOnlyExactCommittedEvidenceInsideTheOwnerTransaction() {
    Context context = context();
    IssuanceSeed seed = context.insertCandidate();

    assertThatThrownBy(() -> context.repository.readCommitted(seed.tokenHash()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Committed Account issuance read requires an Account transaction");

    assertThatThrownBy(() -> context.commitCandidate(seed))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Protected original control-ui response is absent");
    assertThat(context.status(seed.requestId())).isEqualTo("CANDIDATE");

    context.insertProtectedResponse(seed);
    context.commitCandidate(seed);
    var evidence =
        context.inTransaction(
            () -> context.repository.readCommitted(seed.tokenHash()).orElseThrow());

    assertThat(evidence.requestId()).isEqualTo(seed.requestId());
    assertThat(evidence.operationId()).isEqualTo(seed.operationId());
    assertThat(evidence.tokenJti()).isEqualTo(seed.tokenJti());
    assertThat(evidence.accountId()).isEqualTo(context.owner().accountId());
    assertThat(evidence.tenantId()).isEqualTo(context.owner().tenantId());
    assertThat(evidence.callerWorkload()).isEqualTo(seed.callerWorkload());
    assertThat(evidence.callerContextId()).isEqualTo(seed.callerContextId());
    assertThat(evidence.requestDigest()).isEqualTo(seed.requestDigest());
    assertThat(evidence.claims()).containsExactly(seed.claims());
    assertThat(evidence.source()).containsExactly(seed.source());
    assertThat(evidence.bundle()).containsExactly(seed.bundle());
    assertThat(evidence.signerReceipt()).containsExactly(seed.signerReceipt());
    assertThat(evidence.issuedAtEpochSecond()).isEqualTo(seed.issuedAtEpochSecond());
    assertThat(evidence.expiresAtEpochSecond()).isEqualTo(seed.expiresAtEpochSecond());
    assertThat(evidence.status()).isEqualTo("COMMITTED");
    assertThat(evidence.tokenHash()).isEqualTo(seed.tokenHash());
    assertThat(evidence.pendingRegistry()).containsExactly(seed.pendingRegistry());
    assertThat(evidence.activeRegistry()).containsExactly(seed.activeRegistry());
    assertThat(evidence.pendingReceipt()).containsExactly(seed.pendingReceipt());
    assertThat(evidence.committedAt()).isNotNull();
    assertThat(evidence.recoveryFailedAt()).isNull();
    assertThat(
            context.inTransaction(
                () ->
                    Objects.requireNonNull(
                            context.transactionDsl.fetchOne(
                                "SELECT encrypted_response FROM account_control_ui_response_envelopes "
                                    + "WHERE operation_id = ?",
                                seed.operationId()),
                            "Missing committed control-ui response envelope row for operation "
                                + seed.operationId())
                        .get(0, byte[].class)))
        .containsExactly(seed.encryptedResponse());
    assertThat(
            context.inTransaction(
                () ->
                    Objects.requireNonNull(
                            context.transactionDsl.fetchOne(
                                "SELECT owner_binding FROM account_control_ui_response_envelopes "
                                    + "WHERE operation_id = ?",
                                seed.operationId()),
                            "Missing committed control-ui owner-binding row for operation "
                                + seed.operationId())
                        .get(0, byte[].class)))
        .containsExactly(seed.ownerBinding());

    byte[] exposedClaims = evidence.claims();
    exposedClaims[0] ^= 0x7f;
    assertThat(
            context.inTransaction(
                () -> context.repository.readCommitted(seed.tokenHash()).orElseThrow().claims()))
        .containsExactly(seed.claims());
  }

  @Test
  void missingCandidateAndRevokedIssuancesDoNotReadAsCommitted() {
    Context context = context();
    IssuanceSeed candidate = context.insertCandidate();
    IssuanceSeed revoked = context.insertCommitted();
    context.revokeCommitted(revoked);

    assertThat(context.inTransaction(() -> context.repository.readCommitted("f".repeat(64))))
        .isEmpty();
    assertThatThrownBy(
            () ->
                context.inTransaction(
                    () -> context.repository.readCommitted(candidate.tokenHash())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact committed Account control-ui issuance evidence unavailable");
    assertThat(context.status(candidate.requestId())).isEqualTo("CANDIDATE");
    assertThat(context.status(revoked.requestId())).isEqualTo("REVOKED");
    assertThatThrownBy(
            () ->
                context.inTransaction(() -> context.repository.readCommitted(revoked.tokenHash())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact committed Account control-ui issuance evidence unavailable");
  }

  @Test
  void importedPostgresGuardsRejectRewritingOrDeletingCommittedEvidence() {
    Context context = context();
    IssuanceSeed seed = context.insertCommitted();

    assertThatThrownBy(
            () ->
                context.inTransaction(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_control_ui_issuance_operations "
                                + "SET source_payload = ? WHERE request_id = ?",
                            bytes("test-only-rewritten-source"),
                            seed.requestId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context.inTransaction(
                    () ->
                        context.transactionDsl.execute(
                            "DELETE FROM account_control_ui_issuance_operations WHERE request_id = ?",
                            seed.requestId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context.inTransaction(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_control_ui_response_envelopes "
                                + "SET encrypted_response = ? WHERE operation_id = ?",
                            bytes("test-only-rewritten-envelope"),
                            seed.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context.inTransaction(
                    () ->
                        context.transactionDsl.execute(
                            "DELETE FROM account_control_ui_response_envelopes WHERE operation_id = ?",
                            seed.operationId())))
        .isInstanceOf(DataAccessException.class);

    var retained =
        context.inTransaction(
            () -> context.repository.readCommitted(seed.tokenHash()).orElseThrow());
    assertThat(retained.source()).containsExactly(seed.source());
    assertThat(
            context.inTransaction(
                () ->
                    Objects.requireNonNull(
                            context.transactionDsl.fetchOne(
                                "SELECT encrypted_response FROM account_control_ui_response_envelopes "
                                    + "WHERE operation_id = ?",
                                seed.operationId()),
                            "Missing retained control-ui response envelope row for operation "
                                + seed.operationId())
                        .get(0, byte[].class)))
        .containsExactly(seed.encryptedResponse());
  }

  @Test
  void committedReadShareLockLinearizesBeforeConcurrentRevocation() throws Exception {
    Context context = context();
    IssuanceSeed seed = context.insertCommitted();
    CountDownLatch readerHasLoadedEvidence = new CountDownLatch(1);
    CountDownLatch releaseReaderTransaction = new CountDownLatch(1);
    CountDownLatch revocationIsAboutToUpdate = new CountDownLatch(1);
    AtomicInteger readerPid = new AtomicInteger();
    AtomicInteger revokerPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<AccountControlUiCommittedIssuanceRepository.CommittedIssuance> reader =
        executor.submit(
            () ->
                context.inTransaction(
                    () -> {
                      var evidence =
                          context.repository.readCommitted(seed.tokenHash()).orElseThrow();
                      readerPid.set(
                          Objects.requireNonNull(
                                  context.transactionDsl.fetchOne("SELECT pg_backend_pid()"),
                                  "Missing PostgreSQL backend pid row for committed-read reader")
                              .get(0, Integer.class));
                      readerHasLoadedEvidence.countDown();
                      await(releaseReaderTransaction, "reader transaction release");
                      return evidence;
                    }));
    Future<?> revocation;
    try {
      assertThat(readerHasLoadedEvidence.await(5, TimeUnit.SECONDS)).isTrue();
      revocation =
          executor.submit(
              () ->
                  context.inTransaction(
                      () -> {
                        revokerPid.set(
                            Objects.requireNonNull(
                                    context.transactionDsl.fetchOne("SELECT pg_backend_pid()"),
                                    "Missing PostgreSQL backend pid row for concurrent revoker")
                                .get(0, Integer.class));
                        revocationIsAboutToUpdate.countDown();
                        context.transactionDsl.execute(
                            "UPDATE account_control_ui_issuance_operations "
                                + "SET status = 'REVOKING', recovery_failed_at = clock_timestamp() "
                                + "WHERE request_id = ? AND status = 'COMMITTED'",
                            seed.requestId());
                        return null;
                      }));
      assertThat(revocationIsAboutToUpdate.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(
              waitUntilBlockedBy(
                  context, revokerPid.get(), readerPid.get(), TimeUnit.SECONDS.toNanos(5)))
          .as("the revocation UPDATE waits on the repository's FOR SHARE row lock")
          .isTrue();
      assertThat(revocation.isDone()).isFalse();
    } finally {
      releaseReaderTransaction.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
            .as("reader and revocation transactions terminate after releasing the row lock")
            .isTrue();
      }
    }

    assertThat(reader.get(5, TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
    revocation.get(5, TimeUnit.SECONDS);
    assertThat(context.status(seed.requestId())).isEqualTo("REVOKING");
    assertThatThrownBy(
            () -> context.inTransaction(() -> context.repository.readCommitted(seed.tokenHash())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact committed Account control-ui issuance evidence unavailable");
  }

  private static boolean waitUntilBlockedBy(
      Context context, int waitingPid, int blockingPid, long timeoutNanos)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutNanos;
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          Objects.requireNonNull(
                  context.adminDsl.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                          + "WHERE pid = ? AND wait_event_type = 'Lock' "
                          + "AND ? = ANY(pg_blocking_pids(pid)))",
                      waitingPid,
                      blockingPid),
                  "Missing PostgreSQL lock-wait status readback")
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static void await(CountDownLatch latch, String purpose) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + purpose);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + purpose, interrupted);
    }
  }

  private Context context() {
    String schema = "account_control_ui_committed_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DSLContext adminDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    return new Context(transactionDsl, adminDsl, dataSource);
  }

  private static FreshTenantCreationEvidence tenantEvidence(UUID tenantId) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String tenantKey = "committed-issuance-" + requestId.toString().substring(0, 12);
    String requestDigest =
        GameTenantCreationDigest.requestDigest("prod", requestId, tenantKey, "test tenant", null);
    long sourceGameRowId = 7001L;
    String provenance = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        tenantId,
        sourceGameRowId,
        tenantKey,
        provenance,
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            tenantId,
            sourceGameRowId,
            tenantKey,
            provenance));
  }

  private static String tokenHash() {
    return UUID.randomUUID().toString().replace("-", "").repeat(2);
  }

  private static String digest() {
    return UUID.randomUUID().toString().replace("-", "").repeat(2);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private record OwnerIdentity(UUID accountId, UUID tenantId) {}

  private record IssuanceSeed(
      UUID requestId,
      UUID operationId,
      UUID tokenJti,
      String callerWorkload,
      UUID callerContextId,
      String requestDigest,
      byte[] claims,
      byte[] source,
      byte[] bundle,
      byte[] signerReceipt,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond,
      OffsetDateTime recoveryExpiresAt,
      String tokenHash,
      byte[] pendingRegistry,
      byte[] activeRegistry,
      byte[] pendingReceipt,
      byte[] encryptedResponse,
      byte[] ownerBinding) {}

  private static final class Context {
    private final DSLContext transactionDsl;
    private final DSLContext adminDsl;
    private final TransactionTemplate transaction;
    private final AccountControlUiCommittedIssuanceRepository repository;
    private final OwnerIdentity owner;

    Context(DSLContext transactionDsl, DSLContext adminDsl, DriverManagerDataSource dataSource) {
      this.transactionDsl = transactionDsl;
      this.adminDsl = adminDsl;
      transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      repository = new AccountControlUiCommittedIssuanceRepository(transactionDsl);
      owner =
          inTransaction(
              () -> {
                String suffix = UUID.randomUUID().toString();
                Account account = new Account();
                account.setUsername("cui-" + suffix.replace("-", ""));
                account.setEmail("control-ui-committed-" + suffix + "@example.test");
                account.setPasswordHash("test-only-account-password-hash");
                Account saved = new AccountRepository(transactionDsl).save(account);
                UUID tenantId = UUID.randomUUID();
                new FreshTenantIdentityAssociationRepository(transactionDsl, "prod")
                    .importVerified(tenantEvidence(tenantId));
                return new OwnerIdentity(saved.getAccountUuid(), tenantId);
              });
    }

    OwnerIdentity owner() {
      return owner;
    }

    <T> T inTransaction(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    void inTransactionWithoutResult(Runnable action) {
      transaction.executeWithoutResult(ignored -> action.run());
    }

    IssuanceSeed insertCandidate() {
      IssuanceSeed seed = newSeed();
      inTransactionWithoutResult(() -> insertPrepared(seed));
      inTransactionWithoutResult(() -> advanceToCandidate(seed));
      return seed;
    }

    IssuanceSeed insertCommitted() {
      IssuanceSeed seed = insertCandidate();
      insertProtectedResponse(seed);
      commitCandidate(seed);
      return seed;
    }

    void insertProtectedResponse(IssuanceSeed seed) {
      inTransactionWithoutResult(
          () ->
              transactionDsl.execute(
                  "INSERT INTO account_control_ui_response_envelopes "
                      + "(operation_id, encrypted_response, owner_binding, recovery_expires_at) "
                      + "VALUES (?, ?, ?, ?)",
                  seed.operationId(),
                  seed.encryptedResponse(),
                  seed.ownerBinding(),
                  seed.recoveryExpiresAt()));
    }

    void commitCandidate(IssuanceSeed seed) {
      inTransactionWithoutResult(
          () ->
              transactionDsl.execute(
                  "UPDATE account_control_ui_issuance_operations "
                      + "SET status = 'COMMITTED', pending_receipt = ?, "
                      + "committed_at = clock_timestamp() "
                      + "WHERE request_id = ? AND status = 'CANDIDATE'",
                  seed.pendingReceipt(),
                  seed.requestId()));
    }

    void revokeCommitted(IssuanceSeed seed) {
      inTransactionWithoutResult(
          () ->
              transactionDsl.execute(
                  "UPDATE account_control_ui_issuance_operations "
                      + "SET status = 'REVOKING', recovery_failed_at = clock_timestamp() "
                      + "WHERE request_id = ? AND status = 'COMMITTED'",
                  seed.requestId()));
      inTransactionWithoutResult(
          () ->
              transactionDsl.execute(
                  "UPDATE account_control_ui_issuance_operations "
                      + "SET status = 'REVOKED', revocation_receipt = ? "
                      + "WHERE request_id = ? AND status = 'REVOKING'",
                  bytes("test-only-revocation-receipt"),
                  seed.requestId()));
    }

    String status(UUID requestId) {
      return inTransaction(
          () ->
              Objects.requireNonNull(
                      transactionDsl.fetchOne(
                          "SELECT status FROM account_control_ui_issuance_operations WHERE request_id = ?",
                          requestId),
                      "Missing Account control-ui issuance status row for request " + requestId)
                  .get(0, String.class));
    }

    private void insertPrepared(IssuanceSeed seed) {
      transactionDsl.execute(
          "INSERT INTO account_control_ui_issuance_operations ("
              + "request_id, operation_id, token_jti, account_uuid, tenant_uuid, caller_workload, "
              + "caller_context_id, request_mac_key_id, request_digest, claims_payload, "
              + "source_payload, bundle_payload, signer_receipt, issued_at_epoch_second, "
              + "expires_at_epoch_second, recovery_expires_at, status) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED')",
          seed.requestId(),
          seed.operationId(),
          seed.tokenJti(),
          owner.accountId(),
          owner.tenantId(),
          seed.callerWorkload(),
          seed.callerContextId(),
          "test-mac1",
          seed.requestDigest(),
          seed.claims(),
          seed.source(),
          seed.bundle(),
          seed.signerReceipt(),
          seed.issuedAtEpochSecond(),
          seed.expiresAtEpochSecond(),
          seed.recoveryExpiresAt());
    }

    private void advanceToCandidate(IssuanceSeed seed) {
      transactionDsl.execute(
          "UPDATE account_control_ui_issuance_operations "
              + "SET status = 'CANDIDATE', token_hash = ?, pending_registry = ?, "
              + "active_registry = ? WHERE request_id = ? AND status = 'PREPARED'",
          seed.tokenHash(),
          seed.pendingRegistry(),
          seed.activeRegistry(),
          seed.requestId());
    }

    private static IssuanceSeed newSeed() {
      UUID requestId = UUID.randomUUID();
      long issuedAt = Instant.now().getEpochSecond();
      return new IssuanceSeed(
          requestId,
          UUID.randomUUID(),
          UUID.randomUUID(),
          "spiffe://test-only/logging-admin",
          UUID.randomUUID(),
          digest(),
          bytes("test-only-claims-" + requestId),
          bytes("test-only-source-" + requestId),
          bytes("test-only-authority-bundle-" + requestId),
          bytes("test-only-signer-receipt-" + requestId),
          issuedAt,
          issuedAt + 300,
          OffsetDateTime.ofInstant(Instant.ofEpochSecond(issuedAt + 60), ZoneOffset.UTC),
          tokenHash(),
          bytes("test-only-pending-registry-" + requestId),
          bytes("test-only-active-registry-" + requestId),
          bytes("test-only-pending-receipt-" + requestId),
          bytes("opaque-test-only-encrypted-response-fixture-" + requestId),
          bytes("opaque-test-only-owner-binding-" + requestId));
    }
  }
}
