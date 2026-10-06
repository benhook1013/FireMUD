package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCredentialOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCredentialOperationRepository.CredentialOperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.OriginalCapture;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperationRepository;
import net.firedevops.firemud.accountservice.security.AccountControlUiResponseEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiAuthenticationRequest.Purpose;
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

/**
 * Isolated PostgreSQL repository and migration proof. Credential verification and original source
 * captures are stipulated fixtures, not authenticated LOGIN, a complete producer, token signing,
 * registry activation or creator authorization. The repository performs the real operation binding,
 * challenge predicates, consumption and evidence writes under real transactions.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiCredentialAuthenticationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir Path temp;

  @Test
  void v67ToV68UpgradePreservesNonemptyPendingCommittedAndEncryptedOriginalImages()
      throws Exception {
    Fixture fixture = fixture("67");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    var committed = committed(fixture, account, false);
    List<String> before = originalImages(fixture);
    assertThat(before).hasSize(3);

    migrate(fixture.dataSource(), fixture.schema(), "68");

    assertThat(originalImages(fixture)).containsExactlyElementsOf(before);
    assertThat(tx(fixture, () -> fixture.issuance().findByRequest(pending.request()).orElseThrow()))
        .isEqualTo(pending);
    assertThat(
            tx(fixture, () -> fixture.issuance().findByRequest(committed.request()).orElseThrow()))
        .isEqualTo(committed);
    assertThat(attemptCount(fixture)).isZero();
  }

  @Test
  void exactPasswordAndOtpEvidenceBindOriginalOperationAndFreshRecoveryRemainsSeparate()
      throws Exception {
    Fixture fixture = fixture("68");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    List<String> originals = originalImages(fixture);
    var password = record(fixture, pending, account, Purpose.INITIAL_ISSUANCE, Optional.empty());
    AccountEmailLoginChallenge challenge = challenge(fixture, account);
    var otp = record(fixture, pending, account, Purpose.INITIAL_ISSUANCE, Optional.of(challenge));

    assertThat(password.operationId()).isEqualTo(pending.operationId());
    assertThat(password.method()).isEqualTo("PASSWORD");
    assertThat(otp.operationId()).isEqualTo(pending.operationId());
    assertThat(otp.method()).isEqualTo("EMAIL_OTP");
    assertThat(challengeImage(fixture, challenge.getId())).isNull();
    assertThat(originalImages(fixture)).containsExactlyElementsOf(originals);
    assertExactAttempt(fixture, password.attemptId(), pending, account, null, "PASSWORD");
    assertExactAttempt(fixture, otp.attemptId(), pending, account, challenge.getId(), "EMAIL_OTP");

    var committed = committed(fixture, account, false);
    var recovery =
        record(fixture, committed, account, Purpose.EXACT_RESPONSE_RECOVERY, Optional.empty());
    var freshRecovery =
        record(fixture, committed, account, Purpose.EXACT_RESPONSE_RECOVERY, Optional.empty());
    assertThat(recovery.attemptId()).isNotEqualTo(freshRecovery.attemptId());
    assertThat(recovery.operationId()).isEqualTo(committed.operationId());
    assertThat(freshRecovery.operationId()).isEqualTo(committed.operationId());
    assertThat(attemptCount(fixture)).isEqualTo(4L);
    assertThat(evidenceImage(fixture))
        .doesNotContain(
            "fixture-verified-otp-hash",
            "fixture-original-response",
            "password_hash",
            "code_hash",
            "credential",
            "ciphertext",
            "authority_capture");
  }

  @Test
  void wrongAccountRequestDigestAndMissingOriginalDenyWithoutCredentialEvidence() {
    Fixture fixture = fixture("68");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    Account other = account(fixture);
    assertThatThrownBy(
            () -> record(fixture, pending, other, Purpose.INITIAL_ISSUANCE, Optional.empty()))
        .isInstanceOf(CredentialOperationConflictException.class);
    assertThatThrownBy(
            () ->
                tx(
                    fixture,
                    () ->
                        fixture
                            .credentials()
                            .recordVerifiedCredential(
                                UUID.randomUUID(),
                                account,
                                Purpose.INITIAL_ISSUANCE,
                                Optional.empty())))
        .isInstanceOf(CredentialOperationConflictException.class);

    // Deliberately malformed historical request digest; the fixture copies a retained row's
    // structure, not the production semantic-digest algorithm or authorization decisions.
    UUID wrongDigestRequest = UUID.randomUUID();
    fixture
        .dsl()
        .execute(
            "INSERT INTO account_control_ui_issuance_operations "
                + "(operation_id, request_id, account_uuid, account_id, account_provenance, profile, audience, "
                + "request_digest_version, request_digest, authority_capture, authority_capture_digest, "
                + "issuance_fence_capture, issuance_fence_digest, status) "
                + "SELECT ?, ?, account_uuid, account_id, account_provenance, profile, audience, "
                + "request_digest_version, request_digest, authority_capture, authority_capture_digest, "
                + "issuance_fence_capture, issuance_fence_digest, 'PENDING' "
                + "FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            UUID.randomUUID(),
            wrongDigestRequest,
            pending.operationId());
    List<String> originals = originalImages(fixture);
    assertThatThrownBy(
            () ->
                tx(
                    fixture,
                    () ->
                        fixture
                            .credentials()
                            .recordVerifiedCredential(
                                wrongDigestRequest,
                                account,
                                Purpose.INITIAL_ISSUANCE,
                                Optional.empty())))
        .isInstanceOf(CredentialOperationConflictException.class);

    // Direct fixture attacks separately prove the database's operation/request/account binding.
    assertThatThrownBy(
            () -> rawPasswordAttempt(fixture, pending.operationId(), UUID.randomUUID(), account))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("exact original operation");
    assertThatThrownBy(
            () ->
                rawPasswordAttempt(
                    fixture,
                    pending.operationId(),
                    UUID.fromString(pending.request().requestId()),
                    other))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("exact original operation");
    assertThatThrownBy(
            () ->
                rawPasswordAttempt(
                    fixture,
                    UUID.randomUUID(),
                    UUID.fromString(pending.request().requestId()),
                    account))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("exact original operation");
    assertThat(attemptCount(fixture)).isZero();
    assertThat(originalImages(fixture)).containsExactlyElementsOf(originals);
  }

  @Test
  void recoveryLifecycleAndExactLiveChallengePredicatesDenyWithoutConsumption() throws Exception {
    Fixture fixture = fixture("68");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    var expired = committed(fixture, account, true);
    AccountEmailLoginChallenge challenge = challenge(fixture, account);
    String originalChallenge = challengeImage(fixture, challenge.getId());
    for (var operation : List.of(pending, expired)) {
      assertThatThrownBy(
              () ->
                  record(
                      fixture,
                      operation,
                      account,
                      Purpose.EXACT_RESPONSE_RECOVERY,
                      Optional.of(challenge)))
          .isInstanceOf(CredentialOperationConflictException.class);
      assertThat(challengeImage(fixture, challenge.getId())).isEqualTo(originalChallenge);
    }

    String verifiedHash = challenge.getCodeHash();
    challenge.setCodeHash("different-verified-hash");
    assertChallengeDenied(fixture, pending, account, challenge);
    challenge.setCodeHash(verifiedHash);
    Long actualId = challenge.getId();
    challenge.setId(actualId + 1L);
    assertChallengeDenied(fixture, pending, account, challenge);
    challenge.setId(actualId);
    Account other = account(fixture);
    fixture
        .dsl()
        .execute(
            "UPDATE account_email_login_challenge SET account_id = ? WHERE id = ?",
            other.getId(),
            actualId);
    assertChallengeDenied(fixture, pending, account, challenge);
    fixture
        .dsl()
        .execute(
            "UPDATE account_email_login_challenge SET account_id = ? WHERE id = ?",
            account.getId(),
            actualId);
    fixture
        .dsl()
        .execute(
            "UPDATE account_email_login_challenge SET expires_at = CAST(? AS timestamp) WHERE id = ?",
            LocalDateTime.now().minusSeconds(1),
            actualId);
    assertChallengeDenied(fixture, pending, account, challenge);
    fixture
        .dsl()
        .execute(
            "UPDATE account_email_login_challenge SET expires_at = CAST(? AS timestamp), invalid_attempt_count = 5 WHERE id = ?",
            LocalDateTime.now().plusMinutes(5),
            actualId);
    assertChallengeDenied(fixture, pending, account, challenge);
    assertThat(attemptCount(fixture)).isZero();
  }

  @Test
  void lateEvidenceInsertFailureRollsBackMatchingOtpConsumptionAndOriginalOperation() {
    Fixture fixture = fixture("68");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    AccountEmailLoginChallenge challenge = challenge(fixture, account);
    String before = challengeImage(fixture, challenge.getId());
    List<String> originals = originalImages(fixture);
    // A fixture-only late database failure, reached after the real DELETE succeeds.
    fixture
        .dsl()
        .execute(
            "ALTER TABLE account_control_ui_credential_authentication_attempts "
                + "ADD CONSTRAINT fixture_reject_otp_evidence CHECK (authentication_method <> 'EMAIL_OTP')");

    assertThatThrownBy(
            () ->
                record(fixture, pending, account, Purpose.INITIAL_ISSUANCE, Optional.of(challenge)))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("fixture_reject_otp_evidence");

    assertThat(challengeImage(fixture, challenge.getId())).isEqualTo(before);
    assertThat(originalImages(fixture)).containsExactlyElementsOf(originals);
    assertThat(attemptCount(fixture)).isZero();
    fixture
        .dsl()
        .execute(
            "ALTER TABLE account_control_ui_credential_authentication_attempts DROP CONSTRAINT fixture_reject_otp_evidence");
    assertThat(
            record(fixture, pending, account, Purpose.INITIAL_ISSUANCE, Optional.of(challenge))
                .method())
        .isEqualTo("EMAIL_OTP");
    assertThat(challengeImage(fixture, challenge.getId())).isNull();
    assertThat(attemptCount(fixture)).isEqualTo(1L);
  }

  @Test
  void simultaneousMatchedOtpClaimsBlockOnOriginalOperationAndConsumeOnlyOnce() throws Exception {
    Fixture fixture = fixture("68");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    AccountEmailLoginChallenge challenge = challenge(fixture, account);
    List<String> originals = originalImages(fixture);
    CountDownLatch firstRecorded = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger();
    AtomicInteger secondPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  tx(
                      fixture,
                      () -> {
                        firstPid.set(backendPid(fixture));
                        var attempt =
                            fixture
                                .credentials()
                                .recordVerifiedCredential(
                                    UUID.fromString(pending.request().requestId()),
                                    account,
                                    Purpose.INITIAL_ISSUANCE,
                                    Optional.of(challenge));
                        firstRecorded.countDown();
                        await(releaseFirst);
                        return attempt;
                      }));
      assertThat(firstRecorded.await(10, TimeUnit.SECONDS)).isTrue();
      var second =
          executor.submit(
              () -> {
                try {
                  tx(
                      fixture,
                      () -> {
                        secondPid.set(backendPid(fixture));
                        secondEntered.countDown();
                        return fixture
                            .credentials()
                            .recordVerifiedCredential(
                                UUID.fromString(pending.request().requestId()),
                                account,
                                Purpose.INITIAL_ISSUANCE,
                                Optional.of(challenge));
                      });
                  return true;
                } catch (CredentialOperationConflictException expected) {
                  return false;
                }
              });
      assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(waitForDatabaseBlock(fixture, secondPid.get(), firstPid.get())).isTrue();
      releaseFirst.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).method()).isEqualTo("EMAIL_OTP");
      assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
      assertThat(challengeImage(fixture, challenge.getId())).isNull();
      assertThat(attemptCount(fixture)).isEqualTo(1L);
      assertThat(originalImages(fixture)).containsExactlyElementsOf(originals);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void credentialEvidenceRejectsUpdateDeleteAndTruncateAndPreservesExactStoredRows() {
    Fixture fixture = fixture("68");
    Account account = account(fixture);
    var pending = pending(fixture, account);
    var attempt = record(fixture, pending, account, Purpose.INITIAL_ISSUANCE, Optional.empty());
    String original = evidenceImage(fixture);
    for (String sql :
        List.of(
            "UPDATE account_control_ui_credential_authentication_attempts SET purpose = 'EXACT_RESPONSE_RECOVERY' WHERE attempt_id = ?",
            "DELETE FROM account_control_ui_credential_authentication_attempts WHERE attempt_id = ?")) {
      assertThatThrownBy(() -> tx(fixture, () -> fixture.dsl().execute(sql, attempt.attemptId())))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("evidence is immutable");
      assertThat(evidenceImage(fixture)).isEqualTo(original);
    }
    assertThatThrownBy(
            () ->
                tx(
                    fixture,
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "TRUNCATE account_control_ui_credential_authentication_attempts")))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThat(evidenceImage(fixture)).isEqualTo(original);
  }

  private Fixture fixture(String version) {
    String schema = "control_ui_credential_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(
        postgres.getJdbcUrl()
            + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
            + "currentSchema="
            + schema);
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    migrate(source, schema, version);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    return new Fixture(
        schema,
        source,
        dsl,
        new AccountControlUiIssuanceOperationRepository(dsl),
        new AccountControlUiCredentialOperationRepository(dsl),
        new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  private static void migrate(DriverManagerDataSource source, String schema, String version) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target(MigrationVersion.fromVersion(version))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static Account account(Fixture fixture) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    Record row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                        + "RETURNING id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id",
                    "credential_" + unique.substring(0, 12),
                    unique + "@example.test",
                    "fixture-account-password-hash"));
    Account account = new Account();
    account.setId(row.get("id", Long.class));
    account.setAccountUuid(row.get("account_uuid", UUID.class));
    account.setAccountUuidSourceNumericId(row.get("account_uuid_source_numeric_id", Long.class));
    account.setAccountUuidProvenance(
        AccountIdentityProvenance.fromStorageValue(
            row.get("account_uuid_provenance", String.class)));
    return account;
  }

  private static AccountControlUiIssuanceOperation pending(Fixture fixture, Account account) {
    var request =
        new AccountControlUiIssuanceRequest(
            UUID.randomUUID().toString(), account.getAccountUuid().toString());
    OriginalCapture stipulatedCapture =
        new OriginalCapture(
            account.getId(),
            account.getAccountUuidProvenance(),
            "stipulated-original-source-capture".getBytes(StandardCharsets.UTF_8),
            "stipulated-original-fence-capture".getBytes(StandardCharsets.UTF_8));
    return tx(
        fixture,
        () -> fixture.issuance().claim(request, Optional.of(stipulatedCapture)).operation());
  }

  private AccountControlUiIssuanceOperation committed(
      Fixture fixture, Account account, boolean expired) throws Exception {
    var operation = pending(fixture, account);
    byte[] response = "fixture-original-response".getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant issued = now.minusSeconds(120);
    Instant expiry = expired ? now.minusSeconds(60) : now.plusSeconds(300);
    var binding =
        new AccountControlUiResponseEnvelopeBinding(
            account.getAccountUuid().toString(),
            operation.operationId().toString(),
            operation.request().requestId(),
            operation.requestDigest(),
            java.util.HexFormat.of()
                .formatHex(sha256("fixture-original-token".getBytes(StandardCharsets.UTF_8))),
            sha256(response),
            operation.originalCapture().authorityCaptureDigest(),
            operation.originalCapture().issuanceFenceDigest(),
            issued,
            expiry);
    Path manifest = temp.resolve("ring_" + UUID.randomUUID() + ".v1");
    var lines = new java.util.ArrayList<String>(List.of("version=1", "activeKeyId=k1"));
    int seed = 1;
    for (String purpose :
        List.of("bare-login", "connect-token", "pending-reset", "control-ui-response")) {
      byte[] key = new byte[32];
      java.util.Arrays.fill(key, (byte) seed++);
      lines.add(
          "key:k1:" + purpose + "=" + Base64.getUrlEncoder().withoutPadding().encodeToString(key));
    }
    Files.writeString(manifest, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
    var envelope = new AccountEnvelopeCrypto(manifest).encryptControlUiResponse(binding, response);
    return tx(
        fixture,
        () ->
            fixture
                .issuance()
                .complete(
                    fixture.issuance().claim(operation.request(), Optional.empty()),
                    binding,
                    envelope));
  }

  private static AccountEmailLoginChallenge challenge(Fixture fixture, Account account) {
    Record row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "INSERT INTO account_email_login_challenge (account_id, code_hash, expires_at, resend_available_at, created_at, updated_at) "
                        + "VALUES (?, ?, CAST(? AS timestamp), CAST(? AS timestamp), "
                        + "CAST(? AS timestamp), CAST(? AS timestamp)) "
                        + "RETURNING id, account_id, code_hash, expires_at",
                    account.getId(),
                    "fixture-verified-otp-hash",
                    LocalDateTime.now().plusMinutes(5),
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    LocalDateTime.now()));
    AccountEmailLoginChallenge challenge = new AccountEmailLoginChallenge();
    challenge.setId(row.get("id", Long.class));
    challenge.setAccountId(row.get("account_id", Long.class));
    challenge.setCodeHash(row.get("code_hash", String.class));
    challenge.setExpiresAt(row.get("expires_at", LocalDateTime.class));
    return challenge;
  }

  private static AccountControlUiCredentialOperationRepository.RecordedAttempt record(
      Fixture fixture,
      AccountControlUiIssuanceOperation operation,
      Account account,
      Purpose purpose,
      Optional<AccountEmailLoginChallenge> challenge) {
    return tx(
        fixture,
        () ->
            fixture
                .credentials()
                .recordVerifiedCredential(
                    UUID.fromString(operation.request().requestId()), account, purpose, challenge));
  }

  private static void assertChallengeDenied(
      Fixture fixture,
      AccountControlUiIssuanceOperation operation,
      Account account,
      AccountEmailLoginChallenge challenge) {
    String before = challengeTableImage(fixture);
    assertThatThrownBy(
            () ->
                record(
                    fixture, operation, account, Purpose.INITIAL_ISSUANCE, Optional.of(challenge)))
        .isInstanceOf(CredentialOperationConflictException.class);
    assertThat(challengeTableImage(fixture)).isEqualTo(before);
  }

  private static void assertExactAttempt(
      Fixture fixture,
      UUID attemptId,
      AccountControlUiIssuanceOperation operation,
      Account account,
      Long consumedChallengeId,
      String method) {
    Record row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT * FROM account_control_ui_credential_authentication_attempts WHERE attempt_id = ?",
                    attemptId));
    assertThat(row.get("operation_id", UUID.class)).isEqualTo(operation.operationId());
    assertThat(row.get("request_id", UUID.class).toString())
        .isEqualTo(operation.request().requestId());
    assertThat(row.get("account_uuid", UUID.class)).isEqualTo(account.getAccountUuid());
    assertThat(row.get("account_id", Long.class)).isEqualTo(account.getId());
    assertThat(row.get("account_provenance", String.class))
        .isEqualTo(account.getAccountUuidProvenance().name());
    assertThat(row.get("consumed_challenge_id", Long.class)).isEqualTo(consumedChallengeId);
    assertThat(row.get("authentication_method", String.class)).isEqualTo(method);
  }

  private static void rawPasswordAttempt(
      Fixture fixture, UUID operationId, UUID requestId, Account account) {
    tx(
        fixture,
        () ->
            fixture
                .dsl()
                .execute(
                    "INSERT INTO account_control_ui_credential_authentication_attempts "
                        + "(attempt_id, operation_id, request_id, account_uuid, account_id, account_provenance, purpose, authentication_method) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'INITIAL_ISSUANCE', 'PASSWORD')",
                    UUID.randomUUID(),
                    operationId,
                    requestId,
                    account.getAccountUuid(),
                    account.getId(),
                    account.getAccountUuidProvenance().name()));
  }

  private static List<String> originalImages(Fixture fixture) {
    return fixture
        .dsl()
        .fetch(
            "SELECT image FROM ("
                + "SELECT 'operation:' || operation_id::text AS identity, to_jsonb(row_value)::text AS image "
                + "FROM account_control_ui_issuance_operations row_value UNION ALL "
                + "SELECT 'envelope:' || operation_id::text, to_jsonb(row_value)::text "
                + "FROM account_control_ui_issuance_response_envelopes row_value) images ORDER BY identity")
        .getValues("image", String.class);
  }

  private static String challengeImage(Fixture fixture, Long id) {
    Record row =
        fixture
            .dsl()
            .fetchOne(
                "SELECT to_jsonb(row_value)::text AS image FROM account_email_login_challenge row_value WHERE id = ?",
                id);
    return row == null ? null : row.get("image", String.class);
  }

  private static String challengeTableImage(Fixture fixture) {
    return Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY id), '[]'::jsonb)::text AS image "
                        + "FROM account_email_login_challenge row_value"))
        .get("image", String.class);
  }

  private static String evidenceImage(Fixture fixture) {
    return Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY attempt_id), '[]'::jsonb)::text AS image "
                        + "FROM account_control_ui_credential_authentication_attempts row_value"))
        .get("image", String.class);
  }

  private static long attemptCount(Fixture fixture) {
    return Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT count(*) AS count FROM account_control_ui_credential_authentication_attempts"))
        .get("count", Long.class);
  }

  private static int backendPid(Fixture fixture) {
    return Objects.requireNonNull(fixture.dsl().fetchOne("SELECT pg_backend_pid() AS pid"))
        .get("pid", Integer.class);
  }

  private static boolean waitForDatabaseBlock(Fixture fixture, int waiting, int blocking)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          Objects.requireNonNull(
                  fixture
                      .dsl()
                      .fetchOne(
                          "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blocking, waiting))
              .get("blocked", Boolean.class);
      if (Boolean.TRUE.equals(blocked)) return true;
      Thread.sleep(20);
    }
    return false;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS))
        throw new IllegalStateException("Credential race fixture timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Credential race fixture interrupted", failure);
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static <T> T tx(Fixture fixture, Supplier<T> operation) {
    return fixture.transaction().execute(status -> operation.get());
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      AccountControlUiIssuanceOperationRepository issuance,
      AccountControlUiCredentialOperationRepository credentials,
      TransactionTemplate transaction) {}
}
