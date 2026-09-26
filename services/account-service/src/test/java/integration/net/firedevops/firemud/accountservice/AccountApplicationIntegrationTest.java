package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AccountServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH
    })
class AccountApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

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

  @LocalServerPort private int port;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }

  @Test
  void publicRegistrationCreatesGlobalIdentityAndDurablePlatformAuditOnly() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "global-" + suffix;
    String email = username + "@example.com";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/accounts"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\""
                        + username
                        + "\",\"email\":\""
                        + email
                        + "\",\"password\":\"password123\",\"tenantId\":999}"))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    Number accountId =
        dsl.resultQuery("SELECT id FROM accounts WHERE email = ?", email).fetchOne(0, Number.class);
    assertThat(accountId).isNotNull();
    UUID accountUuid =
        dsl.resultQuery("SELECT account_uuid FROM accounts WHERE email = ?", email)
            .fetchOne(0, UUID.class);
    assertThat(accountUuid).isNotNull();
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_authority_generations "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    accountUuid)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    Long generation =
        dsl.resultQuery(
                "SELECT generation FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class);
    Long sourceVersion =
        dsl.resultQuery(
                "SELECT source_version FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class);
    assertThat(generation).isPositive().isEqualTo(1L);
    assertThat(sourceVersion).isPositive().isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    accountUuid)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    Long issuanceFence =
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class);
    Long issuanceFenceSourceVersion =
        dsl.resultQuery(
                "SELECT source_version FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class);
    assertThat(issuanceFence).isPositive().isEqualTo(1L);
    assertThat(issuanceFenceSourceVersion).isPositive().isEqualTo(1L);
    assertThat(dsl.fetchValue("SELECT tenant_id FROM accounts WHERE id = ?", accountId.longValue()))
        .isNull();
    assertThat(dsl.fetchValue("SELECT role FROM accounts WHERE id = ?", accountId.longValue()))
        .isNull();
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ?",
                accountId.longValue()))
        .isEqualTo(0L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM profiles WHERE account_id = ?", accountId.longValue()))
        .isEqualTo(0L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_audit_outbox "
                    + "WHERE scope = 'platform' AND tenant_id IS NULL "
                    + "AND event_type = 'ACCOUNT_REGISTERED' AND payload = ?",
                "{\"accountId\":" + accountId.longValue() + "}"))
        .isEqualTo(1L);
  }

  @Test
  void concurrentPasswordResetAttemptsConsumeTokenExactlyOnce() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String email = "reset-" + suffix + "@example.com";
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "reset-" + suffix,
                email,
                "initial-hash")
            .fetchOne(0, Long.class);
    assertThat(accountId).isNotNull();
    String rawToken = UUID.randomUUID().toString();
    dsl.execute(
        "INSERT INTO password_reset_token (account_id, token, expires_at) VALUES (?, ?, ?)",
        accountId,
        rawToken,
        LocalDateTime.now().plusHours(1));

    CompletePasswordResetRequest request =
        new CompletePasswordResetRequest(rawToken, "new-password");
    assertExactlyOneConcurrentSuccess(
        () -> {
          accountService.completePasswordReset(request);
          return null;
        },
        () -> {
          accountService.completePasswordReset(request);
          return null;
        });

    assertThat(
            dsl.resultQuery("SELECT COUNT(*) FROM password_reset_token WHERE token = ?", rawToken)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery("SELECT password_hash FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, String.class))
        .isNotEqualTo("initial-hash");
  }

  @Test
  void passwordResetConsumptionRollsBackWithAccountMutation() {
    String suffix = UUID.randomUUID().toString();
    String email = "reset-rollback-" + suffix + "@example.com";
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "rr-" + suffix,
                email,
                "initial-hash")
            .fetchOne(0, Long.class);
    assertThat(accountId).isNotNull();
    String rawToken = UUID.randomUUID().toString();
    dsl.execute(
        "INSERT INTO password_reset_token (account_id, token, expires_at) VALUES (?, ?, ?)",
        accountId,
        rawToken,
        LocalDateTime.now().plusHours(1));
    CompletePasswordResetRequest request =
        new CompletePasswordResetRequest(rawToken, "new-password");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      accountService.completePasswordReset(request);
                      throw new IllegalStateException("force rollback after token consumption");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("force rollback after token consumption");

    assertThat(
            dsl.resultQuery("SELECT COUNT(*) FROM password_reset_token WHERE token = ?", rawToken)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery("SELECT password_hash FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, String.class))
        .isEqualTo("initial-hash");

    accountService.completePasswordReset(request);

    assertThat(
            dsl.resultQuery("SELECT COUNT(*) FROM password_reset_token WHERE token = ?", rawToken)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery("SELECT password_hash FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, String.class))
        .isNotEqualTo("initial-hash");
  }

  @Test
  void accountDeletionUnavailablePreservesTerminalSubscriptionAndPaymentEvidence() {
    String suffix = UUID.randomUUID().toString();
    String email = "delete-" + suffix + "@example.com";
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "del-" + suffix.substring(0, 12),
                email,
                "initial-hash")
            .fetchOne(0, Long.class);
    assertThat(accountId).isNotNull();
    LocalDateTime subscriptionEndedAt = LocalDateTime.now().minusDays(1);
    dsl.execute(
        "INSERT INTO subscription (account_id, plan_id, status, started_at, ended_at, tenant_id) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        accountId,
        "retained-plan",
        "canceled",
        subscriptionEndedAt.minusDays(30),
        subscriptionEndedAt,
        1L);
    String providerId = "pi-" + suffix.substring(0, 24);
    dsl.execute(
        "INSERT INTO payment_transaction "
            + "(account_id, amount_cents, currency, status, provider_id, tenant_id) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        accountId,
        2500L,
        "USD",
        "succeeded",
        providerId,
        1L);

    AccountLifecycleException exception =
        Assertions.assertThrows(
            AccountLifecycleException.class, () -> accountService.deleteAccount(accountId));

    assertThat(exception.getCode()).isEqualTo("ACCOUNT_DELETE_WORKFLOW_UNAVAILABLE");
    assertThat(
            dsl.resultQuery("SELECT COUNT(*) FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM subscription "
                        + "WHERE account_id = ? AND status = 'canceled' AND ended_at IS NOT NULL",
                    accountId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM payment_transaction "
                        + "WHERE account_id = ? AND provider_id = ? AND status = 'succeeded'",
                    accountId,
                    providerId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void concurrentEmailVerificationAttemptsConsumeTokenExactlyOnce() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String email = "verify-" + suffix + "@example.com";
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "verify-" + suffix,
                email,
                "initial-hash")
            .fetchOne(0, Long.class);
    assertThat(accountId).isNotNull();
    String rawToken = UUID.randomUUID().toString();
    dsl.execute(
        "INSERT INTO email_verification_token (account_id, token, expires_at) VALUES (?, ?, ?)",
        accountId,
        rawToken,
        LocalDateTime.now().plusHours(1));

    VerifyEmailRequest request = new VerifyEmailRequest(rawToken);
    assertExactlyOneConcurrentSuccess(
        () -> {
          accountService.verifyEmail(request);
          return null;
        },
        () -> {
          accountService.verifyEmail(request);
          return null;
        });

    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM email_verification_token WHERE token = ?", rawToken)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery("SELECT email_verified FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, Boolean.class))
        .isTrue();
  }

  @Test
  void exportAccountRejectsMalformedAccountIdWithInvalidArgumentEnvelope() throws Exception {
    String token =
        jwtUtil.generateToken(
            "operator", java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/accounts/not-a-number/export"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"accountId must be numeric\"");
  }

  @Test
  void linkExternalRouteIsUnavailableWithAuthenticatedRequest() throws Exception {
    String token = jwtUtil.generateToken("2", java.util.Map.of("accountId", "2"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/accounts/2/external"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"provider":"steam","externalId":"demo"}
                    """))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void updateProfileRejectsZeroTenantIdWithInvalidArgumentEnvelope() throws Exception {
    String token = jwtUtil.generateToken("2", java.util.Map.of("accountId", "2"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/profiles/2"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":0,"accountId":2,"displayName":"demo","bio":"bio","presenceVisibilityPolicy":"PRIVATE"}
                    """))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be positive\"");
  }

  private static void assertExactlyOneConcurrentSuccess(Callable<Void> first, Callable<Void> second)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> firstResult = executor.submit(() -> runWhenReleased(first, ready, start));
      Future<Boolean> secondResult = executor.submit(() -> runWhenReleased(second, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(firstResult.get(30, TimeUnit.SECONDS))
          .isNotEqualTo(secondResult.get(30, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static boolean runWhenReleased(
      Callable<Void> operation, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting to start concurrent recovery attempt");
    }
    try {
      operation.call();
      return true;
    } catch (IllegalArgumentException alreadyConsumed) {
      return false;
    }
  }
}
