package integration.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.AccountServiceApplication;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.config.PlatformAuthRateLimitProperties;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCredentialOperationRepository.CredentialOperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.OriginalCapture;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.security.AccountControlUiResponseEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.service.CredentialAttemptSource;
import net.firedevops.firemud.accountservice.service.PlatformAuthBucketStore;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiAuthenticationRequest;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiAuthenticationRequest.Purpose;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountPlatformAuthAbuseLimiter;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiCredentialAuthentication;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash.PlatformAuthPolicy;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real Account credential producer against PostgreSQL, Argon2 verifiers and Cache Redis.
 * The original V66 captures and FIRST_PARTY_WEB source descriptors are stipulated fixture inputs;
 * this does not prove authenticated upstream capture, a public route, JWT/registry issuance or
 * creator/gameplay authority.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = AccountServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "firemud.grpc.workload-namespace=account-control-ui-credential-producer-test"
    })
class AccountControlUiCredentialProducerPostgresRedisIntegrationTest {
  private static final String PASSWORD = "producer-integration-password";
  private static final String VALID_OTP = "482916";
  private static final String ABUSE_HMAC_KEY_ID = "credential-producer-test-key";
  private static final String ABUSE_HMAC_KEY_BASE64 =
      Base64.getEncoder()
          .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final AtomicInteger NEXT_SOURCE_OCTET = new AtomicInteger(20);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
    registry.add("firemud.account.platform-auth-abuse.cache-redis.host", redis::getHost);
    registry.add(
        "firemud.account.platform-auth-abuse.cache-redis.port", () -> redis.getMappedPort(6379));
    registry.add("firemud.account.platform-auth-abuse.hmac-key-id", () -> ABUSE_HMAC_KEY_ID);
    registry.add(
        "firemud.account.platform-auth-abuse.hmac-key-base64", () -> ABUSE_HMAC_KEY_BASE64);
    registry.add("firemud.account.platform-auth-abuse.subject-window-seconds", () -> 3600);
  }

  @TempDir Path tempDirectory;

  @Autowired private AccountServiceImpl accountService;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountEmailLoginChallengeRepository challengeRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PlatformAuthBucketStore authBuckets;
  @Autowired private PlatformAuthRateLimitProperties authRateLimitProperties;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  private AccountControlUiIssuanceOperationRepository issuanceOperations;
  private TransactionTemplate transaction;

  @BeforeEach
  void initializeFixtureOwners() {
    issuanceOperations = new AccountControlUiIssuanceOperationRepository(dsl);
    transaction = new TransactionTemplate(transactionManager);
  }

  @Test
  void actualPasswordRecordsFreshEvidenceAgainstExactPendingOperationWithoutJwtOutcome() {
    Fixture fixture = createAccountFixture();
    AccountControlUiIssuanceOperation original = pendingOperation(fixture.account());

    ControlUiCredentialAuthentication authentication =
        authenticate(fixture, original, PASSWORD, Purpose.INITIAL_ISSUANCE);

    assertThat(authentication.getClass()).isEqualTo(ControlUiCredentialAuthentication.class);
    assertThat(authentication.accountId()).isEqualTo(fixture.account().getAccountUuid());
    assertThat(authentication.requestId())
        .isEqualTo(UUID.fromString(original.request().requestId()));
    assertThat(authentication.operationId()).isEqualTo(original.operationId());
    assertThat(authentication.authenticationMethod()).isEqualTo("PASSWORD");
    assertThat(readOperation(original.request())).isEqualTo(original);
    assertThat(attemptCount(original.operationId())).isEqualTo(1L);
    assertThat(attemptMethod(authentication.authenticationAttemptId())).isEqualTo("PASSWORD");
    assertThat(challengeRepository.findByAccountId(fixture.account().getId())).isEmpty();
  }

  @Test
  void actualOtpConsumesExactChallengeOnceAndConsumedRetryAddsNoCredentialEvidence() {
    Fixture fixture = createAccountFixture();
    AccountControlUiIssuanceOperation original = pendingOperation(fixture.account());
    AccountEmailLoginChallenge challenge = createChallenge(fixture.account(), VALID_OTP);

    ControlUiCredentialAuthentication authentication =
        authenticate(fixture, original, VALID_OTP, Purpose.INITIAL_ISSUANCE);

    assertThat(authentication.authenticationMethod()).isEqualTo("EMAIL_OTP");
    assertThat(authentication.operationId()).isEqualTo(original.operationId());
    assertThat(attemptCount(original.operationId())).isEqualTo(1L);
    assertThat(attemptMethod(authentication.authenticationAttemptId())).isEqualTo("EMAIL_OTP");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT consumed_challenge_id FROM account_control_ui_credential_authentication_attempts "
                            + "WHERE attempt_id = ?",
                        authentication.authenticationAttemptId()))
                .get(0, Long.class))
        .isEqualTo(challenge.getId());
    assertThat(challengeRepository.findByAccountId(fixture.account().getId())).isEmpty();
    assertThat(readOperation(original.request())).isEqualTo(original);

    assertInvalidCredentials(
        () -> authenticate(fixture, original, VALID_OTP, Purpose.INITIAL_ISSUANCE));

    assertThat(attemptCount(original.operationId())).isEqualTo(1L);
    assertThat(readOperation(original.request())).isEqualTo(original);
    assertThat(challengeRepository.findByAccountId(fixture.account().getId())).isEmpty();
  }

  @Test
  void invalidPasswordAndOtpLeaveIssuanceAndEvidenceUnchangedAndUseRealFailureBuckets() {
    Fixture fixture = createAccountFixture();
    AccountControlUiIssuanceOperation original = pendingOperation(fixture.account());
    createChallenge(fixture.account(), VALID_OTP);
    long sourceFailuresBefore =
        failureCount(PlatformAuthPolicy.SOURCE_FAILURE, fixture.source().canonicalClientAddress());
    long candidateFailuresBefore =
        failureCount(
            PlatformAuthPolicy.CANDIDATE_FAILURE,
            AccountPlatformAuthAbuseLimiter.candidateIdentity(fixture.email()));

    assertInvalidCredentials(
        () -> authenticate(fixture, original, "incorrect-password", Purpose.INITIAL_ISSUANCE));
    assertInvalidCredentials(
        () -> authenticate(fixture, original, "incorrect-otp", Purpose.INITIAL_ISSUANCE));

    assertThat(readOperation(original.request())).isEqualTo(original);
    assertThat(attemptCount(original.operationId())).isZero();
    assertThat(challengeRepository.findByAccountId(fixture.account().getId()))
        .get()
        .satisfies(challenge -> assertThat(challenge.getInvalidAttemptCount()).isEqualTo(2));
    assertThat(
            failureCount(
                    PlatformAuthPolicy.SOURCE_FAILURE, fixture.source().canonicalClientAddress())
                - sourceFailuresBefore)
        .isEqualTo(2L);
    assertThat(
            failureCount(
                    PlatformAuthPolicy.CANDIDATE_FAILURE,
                    AccountPlatformAuthAbuseLimiter.candidateIdentity(fixture.email()))
                - candidateFailuresBefore)
        .isEqualTo(2L);
    assertThat(
            accountRepository
                .findByAccountUuid(fixture.account().getAccountUuid())
                .orElseThrow()
                .getLifecycleState())
        .isEqualTo(AccountLifecycleState.ACTIVE);
  }

  @Test
  void wrongOriginalOperationBindingPreservesEligibleOtpAndCredentialEvidence() {
    Fixture originalOwner = createAccountFixture();
    Fixture authenticatingOwner = createAccountFixture();
    AccountControlUiIssuanceOperation original = pendingOperation(originalOwner.account());
    createChallenge(authenticatingOwner.account(), VALID_OTP);
    AccountEmailLoginChallenge challengeBefore =
        challengeRepository.findByAccountId(authenticatingOwner.account().getId()).orElseThrow();

    assertThatThrownBy(
            () -> authenticate(authenticatingOwner, original, VALID_OTP, Purpose.INITIAL_ISSUANCE))
        .isInstanceOf(CredentialOperationConflictException.class);

    assertThat(
            challengeRepository
                .findByAccountId(authenticatingOwner.account().getId())
                .orElseThrow())
        .isEqualTo(challengeBefore);
    assertThat(readOperation(original.request())).isEqualTo(original);
    assertThat(attemptCount(original.operationId())).isZero();
    assertThat(accountEvidenceCount(authenticatingOwner.account().getAccountUuid())).isZero();
  }

  @Test
  void committedUnexpiredResponseRecoveryAuthenticatesAgainWithoutChangingOriginalResponse()
      throws Exception {
    Fixture fixture = createAccountFixture();
    AccountControlUiIssuanceOperation pending = pendingOperation(fixture.account());
    AccountControlUiIssuanceOperation committed = commitStipulatedOriginalResponse(pending);
    assertThat(committed.lifecycle())
        .isEqualTo(AccountControlUiIssuanceOperation.Lifecycle.COMMITTED);
    assertThat(committed.completedResponse()).isNotNull();
    assertThat(committed.completedResponse().binding().expiresAt()).isAfter(Instant.now());

    ControlUiCredentialAuthentication authentication =
        authenticate(fixture, committed, PASSWORD, Purpose.EXACT_RESPONSE_RECOVERY);

    assertThat(authentication.purpose()).isEqualTo(Purpose.EXACT_RESPONSE_RECOVERY);
    assertThat(authentication.authenticationMethod()).isEqualTo("PASSWORD");
    assertThat(authentication.operationId()).isEqualTo(committed.operationId());
    assertThat(readOperation(committed.request())).isEqualTo(committed);
    assertThat(attemptCount(committed.operationId())).isEqualTo(1L);
    assertThat(attemptMethod(authentication.authenticationAttemptId())).isEqualTo("PASSWORD");
  }

  private Fixture createAccountFixture() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String username = "control-ui-" + suffix.substring(0, 12);
    String email = "control-ui-" + suffix + "@example.test";
    accountService.createAccount(new CreateAccountRequest(username, email, PASSWORD));
    Account account = accountRepository.findByEmail(email).orElseThrow();
    return new Fixture(account, email, stipulatedWebSource());
  }

  private AccountControlUiIssuanceOperation pendingOperation(Account account) {
    AccountControlUiIssuanceRequest request =
        new AccountControlUiIssuanceRequest(
            UUID.randomUUID().toString(), account.getAccountUuid().toString());
    OriginalCapture stipulatedCapture =
        new OriginalCapture(
            account.getId(),
            account.getAccountUuidProvenance(),
            ("stipulated-original-authority-capture:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8),
            ("stipulated-original-issuance-fence-capture:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
    return inTransaction(
        () -> issuanceOperations.claim(request, Optional.of(stipulatedCapture)).operation());
  }

  private AccountEmailLoginChallenge createChallenge(Account account, String otp) {
    LocalDateTime now = LocalDateTime.now();
    AccountEmailLoginChallenge challenge = new AccountEmailLoginChallenge();
    challenge.setAccountId(account.getId());
    challenge.setCodeHash(argon2Verifier(otp));
    challenge.setExpiresAt(now.plusMinutes(5));
    challenge.setResendAvailableAt(now);
    challenge.setCreatedAt(now);
    challenge.setUpdatedAt(now);
    return inTransaction(() -> challengeRepository.save(challenge));
  }

  private ControlUiCredentialAuthentication authenticate(
      Fixture fixture,
      AccountControlUiIssuanceOperation operation,
      String credential,
      Purpose purpose) {
    return accountService.authenticateControlUiOperation(
        new AccountControlUiAuthenticationRequest(
            UUID.fromString(operation.request().requestId()),
            fixture.email(),
            credential,
            purpose,
            fixture.source()));
  }

  private AccountControlUiIssuanceOperation commitStipulatedOriginalResponse(
      AccountControlUiIssuanceOperation pending) throws Exception {
    byte[] response = "fixture-original-control-ui-response".getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant issuedAt = now.minusSeconds(60);
    Instant expiresAt = now.plusSeconds(300);
    byte[] tokenBytes = "fixture-original-control-ui-token".getBytes(StandardCharsets.UTF_8);
    AccountControlUiResponseEnvelopeBinding binding =
        new AccountControlUiResponseEnvelopeBinding(
            pending.request().accountUuid(),
            pending.operationId().toString(),
            pending.request().requestId(),
            pending.requestDigest(),
            HexFormat.of().formatHex(sha256(tokenBytes)),
            sha256(response),
            pending.originalCapture().authorityCaptureDigest(),
            pending.originalCapture().issuanceFenceDigest(),
            issuedAt,
            expiresAt);
    Path manifest = tempDirectory.resolve("control-ui-ring-" + UUID.randomUUID() + ".v1");
    writeFixtureKeyRing(manifest);
    var envelope = new AccountEnvelopeCrypto(manifest).encryptControlUiResponse(binding, response);
    return inTransaction(
        () ->
            issuanceOperations.complete(
                issuanceOperations.claim(pending.request(), Optional.empty()), binding, envelope));
  }

  private static void writeFixtureKeyRing(Path manifest) throws Exception {
    List<String> lines = new ArrayList<>(List.of("version=1", "activeKeyId=k1"));
    int seed = 1;
    for (String purpose :
        List.of("bare-login", "connect-token", "pending-reset", "control-ui-response")) {
      byte[] key = new byte[32];
      java.util.Arrays.fill(key, (byte) seed++);
      lines.add(
          "key:k1:" + purpose + "=" + Base64.getUrlEncoder().withoutPadding().encodeToString(key));
    }
    Files.writeString(manifest, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
  }

  private AccountControlUiIssuanceOperation readOperation(AccountControlUiIssuanceRequest request) {
    return inTransaction(() -> issuanceOperations.findByRequest(request).orElseThrow());
  }

  private long attemptCount(UUID operationId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM account_control_ui_credential_authentication_attempts "
                    + "WHERE operation_id = ?",
                operationId))
        .get(0, Long.class);
  }

  private long accountEvidenceCount(UUID accountUuid) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM account_control_ui_credential_authentication_attempts "
                    + "WHERE account_uuid = ?",
                accountUuid))
        .get(0, Long.class);
  }

  private String attemptMethod(UUID attemptId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT authentication_method FROM account_control_ui_credential_authentication_attempts "
                    + "WHERE attempt_id = ?",
                attemptId))
        .get(0, String.class);
  }

  private long failureCount(PlatformAuthPolicy policy, byte[] subject) {
    var digest =
        RateLimitSubjectHash.platformAuthSubject(
            authRateLimitProperties.activeHmacKey(), policy, subject);
    long window =
        Math.floorDiv(
            Instant.now().getEpochSecond(), authRateLimitProperties.getSubjectWindowSeconds());
    return authBuckets.count(
        RateLimitSubjectHash.platformAuthBucketKey(digest, window), digest.collisionFingerprint());
  }

  private static void assertInvalidCredentials(Runnable attempt) {
    assertThatThrownBy(attempt::run)
        .isInstanceOf(AuthenticationException.class)
        .satisfies(
            failure ->
                assertThat(((AuthenticationException) failure).getCode())
                    .isEqualTo(AuthenticationErrorCodes.INVALID_CREDENTIALS));
  }

  private static CredentialAttemptSource stipulatedWebSource() {
    int sourceOctet = NEXT_SOURCE_OCTET.getAndIncrement();
    if (sourceOctet > 254) {
      throw new IllegalStateException("Test source address pool is exhausted");
    }
    return new CredentialAttemptSource(
        "198.51.100." + sourceOctet, CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);
  }

  private static String argon2Verifier(String value) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = value.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private <T> T inTransaction(Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  private record Fixture(Account account, String email, CredentialAttemptSource source) {}
}
