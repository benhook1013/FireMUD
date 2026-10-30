package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AccountServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "firemud.mail.verification-url=https://account.test/verify-email?token=%s"
    })
class AccountApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @LocalServerPort private int port;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;

  @Autowired
  private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

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
    String responseAccountUuid =
        jsonMapper.readTree(response.body()).path("data").path("id").asText(null);
    assertThat(responseAccountUuid).isNotBlank();
    UUID accountUuid = UUID.fromString(responseAccountUuid);
    Number accountId =
        dsl.resultQuery("SELECT id FROM accounts WHERE email = ?", email).fetchOne(0, Number.class);
    assertThat(accountId).isNotNull();
    assertThat(accountUuid)
        .isEqualTo(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId.longValue())
                .fetchOne(0, UUID.class));
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?",
                    accountId.longValue())
                .fetchOne(0, Long.class))
        .isEqualTo(accountId.longValue());
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_provenance FROM accounts WHERE id = ?",
                    accountId.longValue())
                .fetchOne(0, String.class))
        .isEqualTo("ACCOUNT_REPOSITORY_INSERT");
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
                "{\"accountId\":\"" + responseAccountUuid + "\"}"))
        .isEqualTo(1L);

    var accountExport = accountService.exportAccountData(accountId.longValue());
    assertThat(accountExport.account().id()).isEqualTo(responseAccountUuid);
    assertThat(accountExport.profiles()).isEmpty();

    dsl.execute(
        "INSERT INTO account_tenant_membership (account_id, tenant_id, lifecycle_state, "
            + "gameplay_admission_allowed, membership_version, "
            + "membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, 'LEGACY_UNVERIFIED', FALSE, 1, 1, 'LEGACY_UNVERIFIED')",
        accountId.longValue(),
        999L);
    var tenantExport = accountService.exportTenantData(999L, accountId.longValue());
    assertThat(tenantExport.account().id()).isEqualTo(responseAccountUuid);
    assertThat(tenantExport.tenantId()).isEqualTo(999L);
    assertThat(tenantExport.profile()).isNull();
  }

  @Test
  void
      bootstrapUsesPersistedUuidAcrossPasswordOtpReadsAndInvalidationWhileOrdinaryLoginStaysNumeric()
          throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "auth-uuid-" + suffix;
    String email = username + "@example.com";
    HttpRequest registrationRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/accounts"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\""
                        + username
                        + "\",\"email\":\""
                        + email
                        + "\",\"password\":\"password123\"}"))
            .build();

    HttpResponse<String> registrationResponse =
        HTTP_CLIENT.send(registrationRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(registrationResponse.statusCode()).isEqualTo(200);
    Number accountId =
        dsl.resultQuery("SELECT id FROM accounts WHERE email = ?", email).fetchOne(0, Number.class);
    assertThat(accountId).isNotNull();
    long numericAccountId = accountId.longValue();
    UUID persistedAccountUuid =
        dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", numericAccountId)
            .fetchOne(0, UUID.class);
    Long sourceNumericId =
        dsl.resultQuery(
                "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?",
                numericAccountId)
            .fetchOne(0, Long.class);
    String provenance =
        dsl.resultQuery(
                "SELECT account_uuid_provenance FROM accounts WHERE id = ?", numericAccountId)
            .fetchOne(0, String.class);
    assertThat(persistedAccountUuid).isNotNull();
    assertThat(jsonMapper.readTree(registrationResponse.body()).path("data").path("id").asText())
        .isEqualTo(persistedAccountUuid.toString());
    assertThat(sourceNumericId).isEqualTo(numericAccountId);
    assertThat(provenance).isEqualTo("ACCOUNT_REPOSITORY_INSERT");

    HttpRequest authenticationRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/login"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
            .build();

    HttpResponse<String> authenticationResponse =
        HTTP_CLIENT.send(authenticationRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(authenticationResponse.statusCode()).isEqualTo(200);
    var data = jsonMapper.readTree(authenticationResponse.body()).path("data");
    assertThat(data.path("accountId").asLong()).isEqualTo(numericAccountId);
    String token = data.path("authToken").asText();
    assertThat(token).isNotBlank();
    var claims = jwtUtil.parseToken(token).getPayload();
    assertThat(claims.getSubject()).isEqualTo(Long.toString(numericAccountId));
    assertThat(((Number) claims.get("accountId")).longValue()).isEqualTo(numericAccountId);

    HttpRequest bootstrapRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/player-bootstrap"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        java.util.Map.of("accountIdentifier", username, "secret", "password123"))))
            .build();
    HttpResponse<String> bootstrapResponse =
        HTTP_CLIENT.send(bootstrapRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(bootstrapResponse.statusCode()).isEqualTo(200);
    var bootstrapData = jsonMapper.readTree(bootstrapResponse.body()).path("data");
    assertThat(bootstrapData.path("accountId").asText()).isEqualTo(persistedAccountUuid.toString());
    var bootstrapClaims =
        jwtUtil.parseToken(bootstrapData.path("bootstrapToken").asText()).getPayload();
    assertThat(bootstrapClaims.getSubject()).isEqualTo(persistedAccountUuid.toString());
    assertThat(bootstrapClaims.get("accountId", String.class))
        .isEqualTo(persistedAccountUuid.toString());
    org.mockito.Mockito.when(gameSessionClient.listGameplayWorlds())
        .thenReturn(java.util.List.of());
    String passwordBootstrap = bootstrapData.path("bootstrapToken").asText();
    assertThat(bootstrapWorlds(passwordBootstrap).statusCode()).isEqualTo(200);
    invalidateBootstrapSession(numericAccountId, passwordBootstrap);
    HttpResponse<String> expiredPassword = bootstrapWorlds(passwordBootstrap);
    assertThat(expiredPassword.statusCode()).isEqualTo(401);
    assertThat(expiredPassword.body()).contains("CONNECT_CONTEXT_INVALID");

    accountService.requestEmailVerification(email);
    String verificationToken =
        java.util.Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT token FROM email_verification_token WHERE account_id=?",
                    numericAccountId),
                "Expected persisted email-verification record")
            .get(0, String.class);
    accountService.verifyEmail(
        new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest(verificationToken));
    accountService.requestEmailLoginOtp(email);
    var messages =
        org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
    org.mockito.Mockito.verify(mailSender, org.mockito.Mockito.atLeastOnce())
        .send(messages.capture());
    String otpBody =
        messages.getAllValues().stream()
            .filter(message -> "Your FireMUD login code".equals(message.getSubject()))
            .filter(
                message -> {
                  String[] recipients = message.getTo();
                  return recipients != null && java.util.Arrays.asList(recipients).contains(email);
                })
            .map(
                message ->
                    java.util.Objects.requireNonNull(message.getText(), "Expected OTP email body"))
            .findFirst()
            .orElseThrow();
    var matcher = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(otpBody);
    assertThat(matcher.find()).isTrue();
    String otp = matcher.group(1);
    HttpRequest otpRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/player-bootstrap"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        java.util.Map.of("accountIdentifier", email, "secret", otp))))
            .build();
    HttpResponse<String> otpResponse =
        HTTP_CLIENT.send(otpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(otpResponse.statusCode()).isEqualTo(200);
    var otpData = jsonMapper.readTree(otpResponse.body()).path("data");
    assertThat(otpData.path("accountId").asText()).isEqualTo(persistedAccountUuid.toString());
    String otpBootstrap = otpData.path("bootstrapToken").asText();
    var otpClaims = jwtUtil.parseToken(otpBootstrap).getPayload();
    assertThat(otpClaims.getSubject()).isEqualTo(persistedAccountUuid.toString());
    assertThat(otpClaims.get("accountId", String.class)).isEqualTo(persistedAccountUuid.toString());
    assertThat(
            java.util.Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM account_email_login_challenge WHERE account_id=?",
                        numericAccountId),
                    "Expected challenge-count record")
                .get(0, Long.class))
        .isZero();
    assertThat(bootstrapWorlds(otpBootstrap).statusCode()).isEqualTo(200);
    invalidateBootstrapSession(numericAccountId, otpBootstrap);
    assertThat(bootstrapWorlds(otpBootstrap).statusCode()).isEqualTo(401);

    assertThat(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", numericAccountId)
                .fetchOne(0, UUID.class))
        .isEqualTo(persistedAccountUuid);
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?",
                    numericAccountId)
                .fetchOne(0, Long.class))
        .isEqualTo(sourceNumericId);
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_provenance FROM accounts WHERE id = ?", numericAccountId)
                .fetchOne(0, String.class))
        .isEqualTo(provenance);
  }

  private HttpResponse<String> bootstrapWorlds(String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/bootstrap/worlds"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void invalidateBootstrapSession(long accountId, String token) throws Exception {
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(redisTemplate.delete("session:auth:account:" + accountId + ":" + hash)).isTrue();
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
    assertThat(response.body()).contains("\"status\":\"ERROR\"");
    assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
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
}
