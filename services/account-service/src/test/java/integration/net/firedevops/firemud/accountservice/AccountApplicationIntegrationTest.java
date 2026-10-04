package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
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
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  private static final ObjectMapper JSON = new ObjectMapper();

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
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountAuthorityGenerationRepository accountAuthorityGenerationRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
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
    JsonNode responseData = JSON.readTree(response.body()).path("data");
    assertThat(responseData.path("id").isTextual()).isTrue();
    assertThat(responseData.path("id").asText()).isEqualTo(accountUuid.toString());
    assertThat(responseData.path("id").asText()).isNotEqualTo(accountId.toString());
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
                "{\"accountId\":\"" + accountUuid + "\"}"))
        .isEqualTo(1L);
  }

  @Test
  void concurrentPasswordResetAttemptsRecoverOneExactCommit() throws Exception {
    PasswordResetFixture fixture = createPasswordResetFixture("reset");
    CompletePasswordResetRequest request =
        new CompletePasswordResetRequest(fixture.rawToken(), "new-password");
    assertBothConcurrentCallsSucceed(
        () -> {
          accountService.completePasswordReset(request);
          return null;
        },
        () -> {
          accountService.completePasswordReset(request);
          return null;
        });

    assertPasswordResetCommittedExactlyOnce(fixture);
  }

  @Test
  void passwordResetRollbackPreservesEveryAtomicSourceComponent() {
    PasswordResetFixture fixture = createPasswordResetFixture("reset-rollback");
    CompletePasswordResetRequest request =
        new CompletePasswordResetRequest(fixture.rawToken(), "new-password");
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

    assertPasswordResetUnchanged(fixture);

    accountService.completePasswordReset(request);

    assertPasswordResetCommittedExactlyOnce(fixture);
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
            "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a",
            java.util.Map.of(
                "accountId",
                "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a",
                "globalRoles",
                java.util.List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/accounts/not-a-number/export"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body())
        .contains("\"message\":\"accountId must be a canonical non-nil UUID\"");
  }

  @Test
  void linkExternalRouteIsUnavailableWithAuthenticatedRequest() throws Exception {
    String accountId = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
    String token = jwtUtil.generateToken(accountId, java.util.Map.of("accountId", accountId));
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
    assertThat(response.body()).contains("\"status\":\"ERROR\"");
    assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
  }

  @Test
  void updateProfileRejectsZeroTenantIdWithInvalidArgumentEnvelope() throws Exception {
    String accountUuid = "457336d4-63d7-4a3a-a481-ceecb1c8c296";
    String token = jwtUtil.generateToken(accountUuid, java.util.Map.of("accountId", accountUuid));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
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

  private PasswordResetFixture createPasswordResetFixture(String usernamePrefix) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              String suffix = UUID.randomUUID().toString().substring(0, 20);
              Account account = new Account();
              account.setUsername(usernamePrefix + "-" + suffix);
              account.setEmail(usernamePrefix + "-" + suffix + "@example.com");
              account.setPasswordHash("initial-hash");
              Account saved = accountRepository.save(account);
              assertThat(saved.getAccountUuidProvenance())
                  .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
              assertThat(saved.getAccountUuidSourceNumericId()).isEqualTo(saved.getId());

              var authority =
                  accountAuthorityGenerationRepository.initialize(
                      AuthorityScope.account(saved.getAccountUuid()));
              assertThat(authority.generation()).isEqualTo(1L);
              assertThat(authority.sourceVersion()).isEqualTo(1L);
              assertThat(authority.issuanceFence().value()).isEqualTo(1L);
              assertThat(authority.issuanceFence().sourceVersion()).isEqualTo(1L);

              String rawToken = "reset-token-" + suffix;
              LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
              PasswordResetToken token = new PasswordResetToken();
              token.setAccount(saved);
              token.setToken(rawToken);
              token.setExpiresAt(expiresAt);
              passwordResetTokenRepository.save(token);
              return new PasswordResetFixture(
                  saved.getId(), saved.getAccountUuid(), rawToken, "initial-hash");
            });
  }

  private void assertPasswordResetUnchanged(PasswordResetFixture fixture) {
    String streamKey = passwordResetStreamKey(fixture.accountUuid());
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM password_reset_token WHERE token = ?", fixture.rawToken())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery("SELECT password_hash FROM accounts WHERE id = ?", fixture.accountId())
                .fetchOne(0, String.class))
        .isEqualTo(fixture.originalPasswordHash());
    assertThat(
            dsl.resultQuery(
                    "SELECT generation, source_version FROM account_authority_generations "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT source_version FROM account_authority_generations "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT source_version FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(countEvents(streamKey)).isZero();
    assertThat(countReceipts(fixture)).isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    streamKey)
                .fetchOne(0, Long.class))
        .isZero();
  }

  private void assertPasswordResetCommittedExactlyOnce(PasswordResetFixture fixture) {
    String streamKey = passwordResetStreamKey(fixture.accountUuid());
    String tokenHash = HexFormat.of().formatHex(sha256(fixture.rawToken()));
    String expectedRequestId = "account-password-reset-request-v1:" + tokenHash;
    String expectedEventId = "account-password-reset-event-v1:" + tokenHash;
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM password_reset_token WHERE token = ?", fixture.rawToken())
                .fetchOne(0, Long.class))
        .isZero();
    String passwordHash =
        dsl.resultQuery("SELECT password_hash FROM accounts WHERE id = ?", fixture.accountId())
            .fetchOne(0, String.class);
    assertThat(passwordHash).startsWith("$argon2").isNotEqualTo(fixture.originalPasswordHash());
    assertThat(
            dsl.resultQuery(
                    "SELECT generation FROM account_authority_generations "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            dsl.resultQuery(
                    "SELECT source_version FROM account_authority_generations "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            dsl.resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            dsl.resultQuery(
                    "SELECT source_version FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(countEvents(streamKey)).isEqualTo(1L);
    assertThat(countReceipts(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    streamKey)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT request_id FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                    streamKey)
                .fetchOne(0, String.class))
        .isEqualTo(expectedRequestId);
    assertThat(
            dsl.resultQuery(
                    "SELECT event_id FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                    streamKey)
                .fetchOne(0, String.class))
        .isEqualTo(expectedEventId);
    byte[] payloadBytes =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT payload FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                    streamKey)
                .fetchOne(0, byte[].class),
            "Password-reset source-event payload readback is missing");
    String payload = new String(payloadBytes, StandardCharsets.UTF_8);
    assertThat(payload)
        .doesNotContain(fixture.rawToken(), passwordHash, fixture.originalPasswordHash());
  }

  private long countEvents(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Password-reset event count readback is missing");
  }

  private long countReceipts(PasswordResetFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_password_reset_operation_receipts "
                    + "WHERE token_hash = ?",
                sha256(fixture.rawToken()))
            .fetchOne(0, Long.class),
        "Password-reset receipt count readback is missing");
  }

  private static String passwordResetStreamKey(UUID accountUuid) {
    return "account:auth-authority:v1:account/" + accountUuid;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void assertBothConcurrentCallsSucceed(Callable<Void> first, Callable<Void> second)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Void> firstResult =
          executor.submit(() -> runSuccessfulWhenReleased(first, ready, start));
      Future<Void> secondResult =
          executor.submit(() -> runSuccessfulWhenReleased(second, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(firstResult.get(30, TimeUnit.SECONDS)).isNull();
      assertThat(secondResult.get(30, TimeUnit.SECONDS)).isNull();
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static Void runSuccessfulWhenReleased(
      Callable<Void> operation, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting to start concurrent password reset");
    }
    operation.call();
    return null;
  }

  private record PasswordResetFixture(
      Long accountId, UUID accountUuid, String rawToken, String originalPasswordHash) {}

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
