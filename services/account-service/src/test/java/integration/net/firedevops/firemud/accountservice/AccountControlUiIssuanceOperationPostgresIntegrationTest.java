package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.OriginalCapture;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperationRepository;
import net.firedevops.firemud.accountservice.security.AccountControlUiResponseEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiIssuanceOperationPostgresIntegrationTest {
  private static final List<String> RING_PURPOSES =
      List.of("bare-login", "connect-token", "pending-reset", "control-ui-response");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void claimRequiresAnActiveWritableOwnerTransaction() {
    TestContext context = newTestContext();
    AccountControlUiIssuanceRequest request = request(context.account().uuid());

    assertThatThrownBy(
            () -> context.repository().claim(request, Optional.of(capture(context.account()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable caller-owned Account transaction");
  }

  @Test
  void exactClaimsRecoverOriginalCaptureAndChangedAccountOrCaptureConflicts() {
    TestContext context = newTestContext();
    AccountControlUiIssuanceRequest request = request(context.account().uuid());
    OriginalCapture originalCapture = capture(context.account());

    var first =
        inTransaction(
            context.transaction(),
            () -> context.repository().claim(request, Optional.of(originalCapture)));
    var retry =
        inTransaction(
            context.transaction(), () -> context.repository().claim(request, Optional.empty()));

    assertThat(first.created()).isTrue();
    assertThat(retry.created()).isFalse();
    assertThat(retry.operation().operationId()).isEqualTo(first.operation().operationId());
    assertThat(retry.operation().lifecycle())
        .isEqualTo(AccountControlUiIssuanceOperation.Lifecycle.PENDING);
    assertThat(retry.operation().originalCapture()).isEqualTo(originalCapture);

    AccountControlUiIssuanceRequest changedAccount =
        new AccountControlUiIssuanceRequest(
            request.requestId(), insertAccount(context.dsl()).uuid().toString());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(changedAccount, Optional.empty())))
        .isInstanceOf(AccountControlUiIssuanceOperationRepository.OperationConflictException.class);

    OriginalCapture changedCapture =
        new OriginalCapture(
            originalCapture.accountId(),
            originalCapture.accountProvenance(),
            "changed authority capture".getBytes(StandardCharsets.UTF_8),
            originalCapture.issuanceFenceCapture());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(request, Optional.of(changedCapture))))
        .isInstanceOf(AccountControlUiIssuanceOperationRepository.OperationConflictException.class);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().findByRequest(request).orElseThrow()))
        .isEqualTo(first.operation());
  }

  @Test
  void simultaneousExactClaimsAllocateOneOperationAndRetainTheFirstCapture() throws Exception {
    TestContext context = newTestContext();
    AccountControlUiIssuanceRequest request = request(context.account().uuid());
    OriginalCapture originalCapture = capture(context.account());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<AccountControlUiIssuanceOperationRepository.Claim> attempt =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Control-UI claim race did not start");
            }
            return inTransaction(
                context.transaction(),
                () -> context.repository().claim(request, Optional.of(originalCapture)));
          };
      Future<AccountControlUiIssuanceOperationRepository.Claim> first = executor.submit(attempt);
      Future<AccountControlUiIssuanceOperationRepository.Claim> second = executor.submit(attempt);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<AccountControlUiIssuanceOperationRepository.Claim> claims =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(claims)
          .extracting(AccountControlUiIssuanceOperationRepository.Claim::created)
          .containsExactlyInAnyOrder(true, false);
      assertThat(claims)
          .extracting(claim -> claim.operation().operationId())
          .containsOnly(claims.get(0).operation().operationId());
      assertThat(claims)
          .extracting(claim -> claim.operation().originalCapture())
          .containsOnly(originalCapture);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void abandonedPendingClaimCannotBeReportedCommittedWithoutExactEnvelope() {
    TestContext context = newTestContext();
    AccountControlUiIssuanceRequest request = request(context.account().uuid());
    var claim =
        inTransaction(
            context.transaction(),
            () -> context.repository().claim(request, Optional.of(capture(context.account()))));

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED' "
                                    + "WHERE operation_id = ?",
                                claim.operation().operationId())))
        .isInstanceOf(RuntimeException.class);
    AccountControlUiIssuanceOperation stored =
        inTransaction(
            context.transaction(), () -> context.repository().findByRequest(request).orElseThrow());
    assertThat(stored.lifecycle()).isEqualTo(AccountControlUiIssuanceOperation.Lifecycle.PENDING);
    assertThat(stored.completedResponse()).isNull();
    Record envelopeCount =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .dsl()
                    .fetchOne(
                        "SELECT count(*) AS envelope_count "
                            + "FROM account_control_ui_issuance_response_envelopes "
                            + "WHERE operation_id = ?",
                        claim.operation().operationId()));
    assertThat(
            Objects.requireNonNull(
                    envelopeCount, "Pending encrypted-envelope count query returned no row")
                .get("envelope_count", Long.class))
        .isZero();
  }

  @Test
  void exactEncryptedResultSurvivesRestartRotationAndLaterTransactionRetry(@TempDir Path temp)
      throws Exception {
    TestContext context = newTestContext();
    AccountControlUiIssuanceRequest request = request(context.account().uuid());
    var claim =
        inTransaction(
            context.transaction(),
            () -> context.repository().claim(request, Optional.of(capture(context.account()))));
    byte[] exactResponse =
        "{\"accountId\":\""
            .concat(request.accountUuid())
            .concat("\",\"token\":\"synthetic-original-token\"}")
            .getBytes(StandardCharsets.UTF_8);
    String tokenHash = hex(sha256("synthetic-original-token".getBytes(StandardCharsets.UTF_8)));
    OriginalCapture originalCapture = claim.operation().originalCapture();
    AccountControlUiResponseEnvelopeBinding binding =
        new AccountControlUiResponseEnvelopeBinding(
            request.accountUuid(),
            claim.operation().operationId().toString(),
            request.requestId(),
            claim.operation().requestDigest(),
            tokenHash,
            sha256(exactResponse),
            originalCapture.authorityCaptureDigest(),
            originalCapture.issuanceFenceDigest(),
            Instant.parse("2030-05-06T07:08:09Z"),
            Instant.parse("2030-05-06T08:08:09Z"));
    Path manifest = temp.resolve("account-ring.v1");
    writeManifest(manifest, "k1", 1, 0);
    AccountEncryptedEnvelope envelope =
        new AccountEnvelopeCrypto(manifest).encryptControlUiResponse(binding, exactResponse);

    AccountControlUiIssuanceOperation completed =
        inTransaction(
            context.transaction(), () -> context.repository().complete(claim, binding, envelope));
    AccountControlUiIssuanceOperation exactReadback =
        inTransaction(
            context.transaction(), () -> context.repository().findByRequest(request).orElseThrow());
    assertThat(completed).isEqualTo(exactReadback);
    assertThat(exactReadback.lifecycle())
        .isEqualTo(AccountControlUiIssuanceOperation.Lifecycle.COMMITTED);
    assertThat(exactReadback.originalCapture()).isEqualTo(originalCapture);
    assertThat(exactReadback.completedResponse().binding()).isEqualTo(binding);
    assertThat(exactReadback.completedResponse().envelope()).isEqualTo(envelope);

    writeManifest(manifest, "k2", 2, 1);
    byte[] recovered =
        new AccountEnvelopeCrypto(manifest)
            .decryptControlUiResponse(exactReadback.completedResponse().envelope(), binding);
    assertThat(recovered).containsExactly(exactResponse);
    assertThat(exactReadback.completedResponse().envelope().nonce())
        .containsExactly(envelope.nonce());
    assertThat(exactReadback.completedResponse().envelope().ciphertext())
        .containsExactly(envelope.ciphertext());

    AccountControlUiIssuanceOperation replayedCommit =
        inTransaction(
            context.transaction(), () -> context.repository().complete(claim, binding, envelope));
    assertThat(replayedCommit).isEqualTo(exactReadback);
    String storedWire =
        inTransaction(
            context.transaction(),
            () ->
                Objects.requireNonNull(
                        context
                            .dsl()
                            .fetchOne(
                                "SELECT to_jsonb(operation_row)::text || to_jsonb(envelope_row)::text AS wire "
                                    + "FROM account_control_ui_issuance_operations operation_row "
                                    + "JOIN account_control_ui_issuance_response_envelopes envelope_row "
                                    + "USING (operation_id) WHERE operation_row.operation_id = ?",
                                claim.operation().operationId()),
                        "Committed operation/envelope wire query returned no row")
                    .get("wire", String.class));
    assertThat(storedWire)
        .doesNotContain(
            "synthetic-original-token", new String(exactResponse, StandardCharsets.UTF_8));

    var changedBinding =
        new AccountControlUiResponseEnvelopeBinding(
            binding.accountId(),
            binding.operationId(),
            binding.requestId(),
            binding.requestDigest(),
            "ff".repeat(32),
            binding.responseDigest(),
            binding.authorityCaptureDigest(),
            binding.issuanceFenceDigest(),
            binding.issuedAt(),
            binding.expiresAt());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().complete(claim, changedBinding, envelope)))
        .isInstanceOf(AccountControlUiIssuanceOperationRepository.OperationConflictException.class);
  }

  @Test
  void lateRollbackLeavesOriginalPendingCaptureAndNoEncryptedResult() throws Exception {
    TestContext context = newTestContext();
    AccountControlUiIssuanceRequest request = request(context.account().uuid());
    var claim =
        inTransaction(
            context.transaction(),
            () -> context.repository().claim(request, Optional.of(capture(context.account()))));
    byte[] response =
        "synthetic response held only by this fixture".getBytes(StandardCharsets.UTF_8);
    AccountControlUiResponseEnvelopeBinding binding = binding(claim.operation(), response);
    AccountEncryptedEnvelope envelope =
        new AccountEncryptedEnvelope(
            AccountEncryptedEnvelope.CURRENT_FORMAT_VERSION,
            "test-key",
            AccountEnvelopePurpose.CONTROL_UI_RESPONSE,
            new byte[AccountEncryptedEnvelope.NONCE_LENGTH_BYTES],
            new byte[AccountEncryptedEnvelope.AUTHENTICATION_TAG_LENGTH_BYTES]);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      context.repository().complete(claim, binding, envelope);
                      throw new IllegalStateException("synthetic late owner rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic late owner rollback");
    AccountControlUiIssuanceOperation stored =
        inTransaction(
            context.transaction(), () -> context.repository().findByRequest(request).orElseThrow());
    assertThat(stored.lifecycle()).isEqualTo(AccountControlUiIssuanceOperation.Lifecycle.PENDING);
    assertThat(stored.originalCapture()).isEqualTo(claim.operation().originalCapture());
    assertThat(stored.completedResponse()).isNull();
  }

  @Test
  void additiveV66MigrationPreservesV64ReceiptAndExistingV65Structure() {
    TestContext context = newTestContext();
    Record receipt =
        Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT request_id, source_snapshot_payload, status "
                        + "FROM account_tenant_creation_bootstrap_operations WHERE request_id = ?",
                    context.retainedV64RequestId()),
            "Retained V64 receipt lookup returned no row");
    assertThat(receipt).isNotNull();
    assertThat(receipt.get("status", String.class)).isEqualTo("IN_PROGRESS");
    assertThat(receipt.get("source_snapshot_payload", byte[].class))
        .containsExactly("retained-v64-source-snapshot".getBytes(StandardCharsets.UTF_8));
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT count(*) AS table_count FROM information_schema.tables "
                                + "WHERE table_schema = current_schema() "
                                + "AND table_name = 'account_security_state_operations'"),
                    "V65 security-state table existence query returned no row")
                .get("table_count", Long.class))
        .isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT count(*) AS operation_count FROM account_security_state_operations"),
                    "V65 security-state operation count query returned no row")
                .get("operation_count", Long.class))
        .isZero();
  }

  private TestContext newTestContext() {
    String schema = "account_control_ui_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target(MigrationVersion.fromVersion("65"))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    AccountSeed account = insertAccount(dsl);
    UUID retainedRequestId = UUID.randomUUID();
    insertV64PendingReceipt(dsl, account, retainedRequestId);

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return new TestContext(
        dsl,
        new AccountControlUiIssuanceOperationRepository(dsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        account,
        retainedRequestId);
  }

  private static AccountSeed insertAccount(DSLContext dsl) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    Record row =
        dsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING id, account_uuid, account_uuid_provenance",
            "ctrlui_" + unique.substring(0, 12),
            unique + "@example.test",
            "test-password-hash");
    if (row == null) throw new IllegalStateException("Account fixture was not inserted");
    return new AccountSeed(
        row.get("id", Long.class),
        row.get("account_uuid", UUID.class),
        AccountIdentityProvenance.fromStorageValue(
            row.get("account_uuid_provenance", String.class)));
  }

  private static void insertV64PendingReceipt(DSLContext dsl, AccountSeed account, UUID requestId) {
    UUID tenant = UUID.randomUUID();
    UUID creationRequest = UUID.randomUUID();
    UUID creationOperation = UUID.randomUUID();
    String digest = "sha256:" + "a".repeat(64);
    int inserted =
        dsl.execute(
            "INSERT INTO account_tenant_creation_bootstrap_operations "
                + "(request_id, schema_version, initiating_account_uuid, tenant_uuid, "
                + "creation_request_id, creation_operation_id, account_authorization_operation_id, "
                + "account_authorization_digest, creator_evidence_digest, creator_evidence_payload, "
                + "source_snapshot_payload, source_snapshot_digest, request_payload, request_digest, "
                + "baseline_membership_version, baseline_membership_authority_generation, "
                + "baseline_event_sequence, status) "
                + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 'IN_PROGRESS')",
            requestId,
            account.uuid(),
            tenant,
            creationRequest,
            creationOperation,
            requestId,
            digest,
            digest,
            "retained-v64-creator-evidence".getBytes(StandardCharsets.UTF_8),
            "retained-v64-source-snapshot".getBytes(StandardCharsets.UTF_8),
            digest,
            "retained-v64-request".getBytes(StandardCharsets.UTF_8),
            digest);
    if (inserted != 1) throw new IllegalStateException("V64 retention fixture was not inserted");
  }

  private static AccountControlUiIssuanceRequest request(UUID account) {
    return new AccountControlUiIssuanceRequest(UUID.randomUUID().toString(), account.toString());
  }

  private static OriginalCapture capture(AccountSeed account) {
    return new OriginalCapture(
        account.id(),
        account.provenance(),
        "fixture-authority-capture-bytes".getBytes(StandardCharsets.UTF_8),
        "fixture-issuance-fence-capture-bytes".getBytes(StandardCharsets.UTF_8));
  }

  private static AccountControlUiResponseEnvelopeBinding binding(
      AccountControlUiIssuanceOperation operation, byte[] response) {
    return new AccountControlUiResponseEnvelopeBinding(
        operation.request().accountUuid(),
        operation.operationId().toString(),
        operation.request().requestId(),
        operation.requestDigest(),
        hex(sha256("synthetic-token".getBytes(StandardCharsets.UTF_8))),
        sha256(response),
        operation.originalCapture().authorityCaptureDigest(),
        operation.originalCapture().issuanceFenceDigest(),
        Instant.parse("2030-05-06T07:08:09Z"),
        Instant.parse("2030-05-06T08:08:09Z"));
  }

  private static void writeManifest(Path path, String activeKeyId, int activeSeed, int retainedSeed)
      throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("version=1");
    lines.add("activeKeyId=" + activeKeyId);
    for (String purpose : RING_PURPOSES) {
      if (retainedSeed > 0) lines.add(keyLine("k1", purpose, retainedSeed));
    }
    for (String purpose : RING_PURPOSES) lines.add(keyLine(activeKeyId, purpose, activeSeed));
    Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
  }

  private static String keyLine(String keyId, String purpose, int seed) {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) (seed * 32 + RING_PURPOSES.indexOf(purpose) + 1));
    return "key:"
        + keyId
        + ":"
        + purpose
        + "="
        + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record AccountSeed(long id, UUID uuid, AccountIdentityProvenance provenance) {}

  private record TestContext(
      DSLContext dsl,
      AccountControlUiIssuanceOperationRepository repository,
      TransactionTemplate transaction,
      AccountSeed account,
      UUID retainedV64RequestId) {}
}
