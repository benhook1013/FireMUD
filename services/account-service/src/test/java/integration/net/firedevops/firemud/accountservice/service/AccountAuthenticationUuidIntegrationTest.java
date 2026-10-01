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
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
  @Autowired private SessionService sessionService;
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
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("Account identity cannot be reassigned");

    assertThat(accountUuidFor(accountId)).isEqualTo(accountUuid);
    assertThat(accountService.resolveAccountStorageId(accountUuid)).isEqualTo(accountId);
    assertThatThrownBy(() -> accountService.resolveAccountStorageId(attemptedReplacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account not found");
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
