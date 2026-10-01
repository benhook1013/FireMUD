package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.AccountServiceApplication;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.controller.ProfileController;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
      "firemud.grpc.workload-namespace=account-auth-uuid-test"
    })
class AccountAuthenticationUuidIntegrationTest {
  private static final String PASSWORD = "account-auth-test-password";
  private static final long PROFILE_TENANT_ID = 98123L;
  private static final Pattern OTP_CODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

  private enum OtpAuthenticationEntryPoint {
    CONTROL_UI,
    GAMEPLAY,
    VERIFY_EMAIL_OTP,
    PLAYER_BOOTSTRAP
  }

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private AccountRepository accountRepository;
  @MockitoSpyBean private SessionService sessionService;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;
  @MockitoBean private NotificationService notificationService;
  @MockitoSpyBean private AccountEmailLoginChallengeRepository challengeRepositorySpy;

  @BeforeEach
  void cleanDatabaseAndObserveOwnerTransaction() {
    dsl.execute("TRUNCATE TABLE account_audit_outbox");
    dsl.execute("TRUNCATE TABLE accounts RESTART IDENTITY CASCADE");
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              invocation.callRealMethod();
              return null;
            })
        .when(challengeRepositorySpy)
        .lockAccountChallenge(anyLong());
  }

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void repositoryInsertAuthenticationUsesExactPersistedUuidAndPrivateStorageKey() {
    String suffix = UUID.randomUUID().toString();
    String username = "repo-" + suffix;
    String email = username + "@example.com";
    var created = accountService.createAccount(new CreateAccountRequest(username, email, PASSWORD));
    Account persisted = accountRepository.findById(created.id()).orElseThrow();

    assertThat(persisted.getAccountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(persisted.getAccountUuidSourceNumericId()).isEqualTo(created.id());

    assertAuthenticationAndPrivateLookup(username, created.id(), persisted.getAccountUuid());
  }

  @Test
  void databaseInsertAuthenticationUsesTriggerProvedUuidAndCannotRemapItsSource() {
    String suffix = UUID.randomUUID().toString();
    String username = "db-" + suffix;
    String email = username + "@example.com";
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash, login_auth_modes) "
                    + "VALUES (?, ?, ?, 'PASSWORD,EMAIL_OTP') RETURNING id",
                username,
                email,
                hashPassword(PASSWORD))
            .fetchOne(0, Long.class);
    Account persisted = accountRepository.findById(accountId).orElseThrow();
    UUID accountUuid = persisted.getAccountUuid();

    assertThat(accountUuid).isNotNull();
    assertThat(persisted.getAccountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    assertThat(persisted.getAccountUuidSourceNumericId()).isEqualTo(accountId);

    assertAuthenticationAndPrivateLookup(username, accountId, accountUuid);

    UUID attemptedReplacement = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE accounts SET account_uuid = ? WHERE id = ?",
                    attemptedReplacement,
                    accountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("Account identity cannot be reassigned");

    assertThat(accountUuidFor(accountId)).isEqualTo(accountUuid);
    assertThat(accountService.resolveAccountStorageId(accountUuid)).isEqualTo(accountId);
    assertThatThrownBy(() -> accountService.resolveAccountStorageId(attemptedReplacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account not found");
  }

  @Test
  void emailOtpAuthenticationIssuesPersistedUuidAndConsumesTheChallengeExactlyOnce() {
    Account persisted = createVerifiedAccount("otp");
    String email = persisted.getEmail();
    UUID expectedAccountUuid = persisted.getAccountUuid();
    assertThat(persisted.getAccountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(persisted.getAccountUuidSourceNumericId()).isEqualTo(persisted.getId());

    String deliveredCode = requestEmailLoginOtpAndCaptureCode(email);

    String wrongCode = wrongOtpCode(deliveredCode);
    assertThatThrownBy(() -> accountService.verifyEmailLoginOtp(email, wrongCode))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("Invalid credentials");
    var challengeAfterWrongCode =
        challengeRepositorySpy.findByAccountId(persisted.getId()).orElseThrow();
    assertThat(challengeAfterWrongCode.getInvalidAttemptCount()).isEqualTo(1);
    assertThat(challengeAfterWrongCode.getCodeHash()).isNotEqualTo(deliveredCode);
    assertThat(accountUuidFor(persisted.getId())).isEqualTo(expectedAccountUuid);

    var result = accountService.verifyEmailLoginOtp(email, deliveredCode);
    assertThat(jwtUtil.parseToken(result.authToken()).getPayload().getAudience())
        .containsOnly("account-service");
    assertAuthenticationAndPrivateLookup(persisted.getId(), expectedAccountUuid, result);
    assertThat(challengeRepositorySpy.findByAccountId(persisted.getId())).isEmpty();
    assertThat(emailLoginChallengeCount(persisted.getId())).isZero();

    assertThatThrownBy(() -> accountService.verifyEmailLoginOtp(email, deliveredCode))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("Invalid credentials");
    assertThat(accountUuidFor(persisted.getId())).isEqualTo(expectedAccountUuid);
    assertThat(emailLoginChallengeCount(persisted.getId())).isZero();
  }

  @Test
  void rejectedEmailOtpPersistsChallengeAttemptForControlUiAuthentication() {
    assertRejectedEmailOtpPersistsChallengeAttempt(OtpAuthenticationEntryPoint.CONTROL_UI);
  }

  @Test
  void rejectedEmailOtpPersistsChallengeAttemptForGameplayAuthentication() {
    assertRejectedEmailOtpPersistsChallengeAttempt(OtpAuthenticationEntryPoint.GAMEPLAY);
  }

  @Test
  void rejectedEmailOtpPersistsChallengeAttemptForOtpCompletion() {
    assertRejectedEmailOtpPersistsChallengeAttempt(OtpAuthenticationEntryPoint.VERIFY_EMAIL_OTP);
  }

  @Test
  void rejectedEmailOtpPersistsChallengeAttemptForPlayerBootstrap() {
    assertRejectedEmailOtpPersistsChallengeAttempt(OtpAuthenticationEntryPoint.PLAYER_BOOTSTRAP);
  }

  private void assertRejectedEmailOtpPersistsChallengeAttempt(
      OtpAuthenticationEntryPoint entryPoint) {
    Account persisted = createVerifiedAccount("otp-rejected");
    String deliveredCode = requestEmailLoginOtpAndCaptureCode(persisted.getEmail());
    String wrongCode = wrongOtpCode(deliveredCode);
    var challengeBeforeWrongCode =
        challengeRepositorySpy.findByAccountId(persisted.getId()).orElseThrow();

    assertThatThrownBy(
            () -> attemptEmailOtpAuthentication(entryPoint, persisted.getEmail(), wrongCode))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("Invalid credentials");

    var challengeAfterWrongCode =
        challengeRepositorySpy.findByAccountId(persisted.getId()).orElseThrow();
    assertThat(challengeAfterWrongCode.getInvalidAttemptCount()).isEqualTo(1);
    assertThat(challengeAfterWrongCode.getCodeHash())
        .isEqualTo(challengeBeforeWrongCode.getCodeHash())
        .isNotEqualTo(deliveredCode);
    assertThat(emailLoginChallengeCount(persisted.getId())).isEqualTo(1L);
    assertThat(accountUuidFor(persisted.getId())).isEqualTo(persisted.getAccountUuid());
    assertThat(accountService.resolveAccountStorageId(persisted.getAccountUuid()))
        .isEqualTo(persisted.getId());
    org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
        .storeAccountSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void nonAuthenticationSessionFailureRollsBackOtpConsumption() {
    Account persisted = createVerifiedAccount("otp-store");
    String deliveredCode = requestEmailLoginOtpAndCaptureCode(persisted.getEmail());
    var challengeBeforeFailure =
        challengeRepositorySpy.findByAccountId(persisted.getId()).orElseThrow();
    doAnswer(
            invocation -> {
              throw new IllegalStateException("simulated session storage failure");
            })
        .when(sessionService)
        .storeAccountSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());

    assertThatThrownBy(
            () -> accountService.verifyEmailLoginOtp(persisted.getEmail(), deliveredCode))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated session storage failure");

    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(sessionService)
        .storeAccountSession(
            org.mockito.ArgumentMatchers.anyLong(),
            tokenCaptor.capture(),
            org.mockito.ArgumentMatchers.anyLong());
    assertThat(sessionService.isAccountSessionActive(persisted.getId(), tokenCaptor.getValue()))
        .isFalse();
    assertThat(challengeRepositorySpy.findByAccountId(persisted.getId()))
        .contains(challengeBeforeFailure);
    assertThat(emailLoginChallengeCount(persisted.getId())).isEqualTo(1L);
    assertThat(accountUuidFor(persisted.getId())).isEqualTo(persisted.getAccountUuid());
  }

  @Test
  void unmappedUuidDeniesProfileMutationEvenWhenBodyNamesAnExistingPrivateRow() {
    String suffix = UUID.randomUUID().toString();
    String username = "profile-" + suffix;
    String email = username + "@example.com";
    var created = accountService.createAccount(new CreateAccountRequest(username, email, PASSWORD));
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, TRUE, 'ACTIVE', 1, 1, 'EXPLICIT_JOIN')",
        created.id(),
        PROFILE_TENANT_ID);
    dsl.execute(
        "INSERT INTO profiles (account_id, tenant_id, display_name, bio) VALUES (?, ?, ?, ?)",
        created.id(),
        PROFILE_TENANT_ID,
        "original-display-name",
        "original-bio");
    String profileBefore = profileRowJson(created.id());

    UUID unmappedUuid = UUID.randomUUID();
    String testToken =
        jwtUtil.generateToken(
            unmappedUuid.toString(), Map.of("accountId", unmappedUuid.toString()));
    String decodedAccountId =
        jwtUtil.parseToken(testToken).getPayload().get("accountId", String.class);
    SessionContext.setContext(decodedAccountId, List.of(), Map.of());
    ProfileController controller = new ProfileController(accountService);
    UpdateProfileRequest request =
        new UpdateProfileRequest(
            PROFILE_TENANT_ID,
            created.id(),
            "must-not-be-written",
            "must-not-be-written",
            ProfilePresenceVisibilityPolicy.PRIVATE);

    assertThatThrownBy(() -> controller.updateProfile(unmappedUuid.toString(), request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account not found");

    assertThat(profileRowJson(created.id())).isEqualTo(profileBefore);
  }

  private void assertAuthenticationAndPrivateLookup(
      String username, long accountId, UUID expectedAccountUuid) {
    AuthenticationResult result = accountService.authenticate(username, PASSWORD);
    assertAuthenticationAndPrivateLookup(accountId, expectedAccountUuid, result);
  }

  private void assertAuthenticationAndPrivateLookup(
      long accountId, UUID expectedAccountUuid, AuthenticationResult result) {
    var claims = jwtUtil.parseToken(result.authToken()).getPayload();

    assertThat(result.accountId()).isEqualTo(expectedAccountUuid.toString());
    assertThat(result.accountId()).isNotEqualTo(Long.toString(accountId));
    assertThat(claims.getSubject()).isEqualTo(expectedAccountUuid.toString());
    assertThat(claims.get("accountId", String.class)).isEqualTo(expectedAccountUuid.toString());
    assertThat(claims.get("tenantId")).isNull();
    assertThat(sessionService.isAccountSessionActive(accountId, result.authToken())).isTrue();

    Account found = accountRepository.findByAccountUuid(expectedAccountUuid).orElseThrow();
    assertThat(found.getId()).isEqualTo(accountId);
    assertThat(found.getAccountUuid()).isEqualTo(expectedAccountUuid);
    assertThat(found.getAccountUuidSourceNumericId()).isEqualTo(accountId);
    assertThat(accountService.resolveAccountStorageId(expectedAccountUuid)).isEqualTo(accountId);
    assertThat(accountUuidFor(accountId)).isEqualTo(expectedAccountUuid);
  }

  private UUID accountUuidFor(long accountId) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT account_uuid FROM accounts WHERE id = ?", accountId),
            "Expected persisted account_uuid row for account id " + accountId)
        .get(0, UUID.class);
  }

  private long emailLoginChallengeCount(long accountId) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_email_login_challenge WHERE account_id = ?",
                accountId)
            .fetchOne(0, Long.class),
        "Expected challenge count row for account id " + accountId);
  }

  private Account createVerifiedAccount(String prefix) {
    String username = prefix + "-" + UUID.randomUUID();
    String email = username + "@example.com";
    var created = accountService.createAccount(new CreateAccountRequest(username, email, PASSWORD));
    assertThat(dsl.execute("UPDATE accounts SET email_verified = TRUE WHERE id = ?", created.id()))
        .isEqualTo(1);
    return accountRepository.findById(created.id()).orElseThrow();
  }

  private String requestEmailLoginOtpAndCaptureCode(String email) {
    accountService.requestEmailLoginOtp(email);

    ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    org.mockito.Mockito.verify(mailSender).send(mailCaptor.capture());
    SimpleMailMessage deliveredMessage = mailCaptor.getValue();
    assertThat(deliveredMessage.getTo()).containsExactly(email);
    assertThat(deliveredMessage.getSubject()).isEqualTo("Your FireMUD login code");
    String deliveredBody =
        Objects.requireNonNull(deliveredMessage.getText(), "Expected delivered email OTP body");
    Matcher codeMatcher = OTP_CODE_PATTERN.matcher(deliveredBody);
    assertThat(codeMatcher.find()).isTrue();
    return codeMatcher.group(1);
  }

  private String wrongOtpCode(String deliveredCode) {
    return deliveredCode.equals("000000") ? "000001" : "000000";
  }

  private void attemptEmailOtpAuthentication(
      OtpAuthenticationEntryPoint entryPoint, String email, String wrongCode) {
    switch (entryPoint) {
      case CONTROL_UI -> accountService.authenticate(email, wrongCode);
      case GAMEPLAY -> accountService.authenticateForGameplay(email, wrongCode);
      case VERIFY_EMAIL_OTP -> accountService.verifyEmailLoginOtp(email, wrongCode);
      case PLAYER_BOOTSTRAP -> accountService.issuePlayerBootstrap(email, wrongCode);
    }
  }

  private String profileRowJson(long accountId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT to_jsonb(p)::text FROM profiles p WHERE account_id = ? AND tenant_id = ?",
                accountId,
                PROFILE_TENANT_ID),
            "Expected profile row for account id "
                + accountId
                + " and tenant id "
                + PROFILE_TENANT_ID)
        .get(0, String.class);
  }

  private static String hashPassword(String password) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }
}
