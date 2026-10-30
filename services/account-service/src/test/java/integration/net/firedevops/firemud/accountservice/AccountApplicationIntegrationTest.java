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
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthRoleRequirement;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
      "spring.config.import=classpath:account-main-application.yml",
      "spring.grpc.server.port=0",
      "firemud.grpc.workload-namespace=test",
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
  @Autowired private HttpAuthProperties httpAuthProperties;
  @Autowired private ObjectProvider<HttpJwtAuthInterceptor> httpJwtAuthInterceptorProvider;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private net.firedevops.firemud.accountservice.repository.AccountRepository accountRepository;

  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
  void ordinaryUuidPasswordOtpBootstrapAndScopeJourneysPreservePersistedIdentity()
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
    assertThat(data.path("accountId").asText()).isEqualTo(persistedAccountUuid.toString());
    String token = data.path("authToken").asText();
    assertThat(token).isNotBlank();
    var claims = jwtUtil.parseToken(token).getPayload();
    assertThat(claims.getSubject()).isEqualTo(persistedAccountUuid.toString());
    assertThat(claims.get("accountId", String.class)).isEqualTo(persistedAccountUuid.toString());
    String otherAccountUuid =
        accountService
            .createAccount(
                new net.firedevops.firemud.accountservice.dto.CreateAccountRequest(
                    "guild-other-" + suffix,
                    "guild-other-" + suffix + "@example.com",
                    "password123"))
            .id();
    assertOrdinaryCredentialPreservesSocialGuildSelf(
        token, persistedAccountUuid.toString(), otherAccountUuid);

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

    // Obtain a fresh real challenge: bootstrap issuance consumed the preceding one.
    accountService.requestEmailLoginOtp(email);
    var loginMessages =
        org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
    org.mockito.Mockito.verify(mailSender, org.mockito.Mockito.atLeastOnce())
        .send(loginMessages.capture());
    String loginOtpBody =
        loginMessages.getAllValues().stream()
            .filter(message -> "Your FireMUD login code".equals(message.getSubject()))
            .filter(
                message -> {
                  String[] recipients = message.getTo();
                  return recipients != null && java.util.Arrays.asList(recipients).contains(email);
                })
            .map(
                message ->
                    java.util.Objects.requireNonNull(message.getText(), "Expected login OTP body"))
            .reduce((earlier, latest) -> latest)
            .orElseThrow();
    var loginMatcher = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(loginOtpBody);
    assertThat(loginMatcher.find()).isTrue();
    HttpRequest otpLoginRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/login"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        java.util.Map.of("username", email, "password", loginMatcher.group(1)))))
            .build();
    HttpResponse<String> otpLoginResponse =
        HTTP_CLIENT.send(otpLoginRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(otpLoginResponse.statusCode()).isEqualTo(200);
    var loginData = jsonMapper.readTree(otpLoginResponse.body()).path("data");
    assertThat(loginData.path("accountId").asText()).isEqualTo(persistedAccountUuid.toString());
    var loginClaims = jwtUtil.parseToken(loginData.path("authToken").asText()).getPayload();
    assertThat(loginClaims.getSubject()).isEqualTo(persistedAccountUuid.toString());
    assertThat(loginClaims.get("accountId", String.class))
        .isEqualTo(persistedAccountUuid.toString());
    assertThat(loginClaims.getAudience()).containsExactly("control-ui");
    assertOrdinaryCredentialPreservesSocialGuildSelf(
        loginData.path("authToken").asText(), persistedAccountUuid.toString(), otherAccountUuid);
    assertThat(
            java.util.Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM account_email_login_challenge WHERE account_id=?",
                        numericAccountId),
                    "Expected consumed ordinary-login challenge count")
                .get(0, Long.class))
        .isZero();

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
    // Controlled persisted role assignment, not a synthetic privileged JWT or scoped-role issuer.
    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .execute(
            status -> {
              var owner = accountRepository.findByIdForUpdate(numericAccountId).orElseThrow();
              owner.setRole("moderator");
              accountRepository.save(owner);
              return null;
            });
    HttpResponse<String> moderatorResponse =
        HTTP_CLIENT.send(authenticationRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(moderatorResponse.statusCode()).isEqualTo(200);
    String moderatorToken =
        jsonMapper.readTree(moderatorResponse.body()).path("data").path("authToken").asText();
    assertOrdinaryModeratorPreservesGuildTenantAccess(
        moderatorToken, persistedAccountUuid.toString(), otherAccountUuid);

    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "controlled owner readback outage"))
        .when(accountRepository)
        .findByAccountUuid(persistedAccountUuid);
    HttpResponse<String> unavailable =
        HTTP_CLIENT.send(authenticationRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(jsonMapper.readTree(unavailable.body()).path("error").path("code").asText())
        .isEqualTo("AUTH_UNAVAILABLE");
    assertThat(jsonMapper.readTree(unavailable.body()).path("data").path("authToken").asText())
        .isEmpty();
  }

  @Test
  void browserAndControlledDirectScopesRetainUuidClaimsAndPrivateJoinRows() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "scope-" + suffix;
    var account =
        accountService.createAccount(
            new net.firedevops.firemud.accountservice.dto.CreateAccountRequest(
                username, username + "@example.com", "password123"));
    var persistedAccount =
        java.util.Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id FROM accounts WHERE account_uuid=?", UUID.fromString(account.id())),
            "Expected persisted Account");
    long row =
        java.util.Objects.requireNonNull(
            persistedAccount.get(0, Long.class), "Expected persisted Account storage key");
    long tenant = 500000000L + Integer.toUnsignedLong(suffix.hashCode());
    dsl.execute(
        "INSERT INTO subscription(account_id,plan_id,status,tenant_id,entitlement_version) VALUES(?,'scope-proof','active',?,1)",
        row,
        tenant);
    String realm = UUID.randomUUID().toString();
    String namespace = UUID.randomUUID().toString();
    var target =
        net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
            .setWorldSlug("scope-proof")
            .setRealmSlug("production")
            .setDisplayName("Scope proof")
            .setTenantId(Long.toString(tenant))
            .setGameInstanceId("44")
            .setRealmId(realm)
            .setPlayableStateNamespaceId(namespace)
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .build();
    org.mockito.Mockito.when(gameSessionClient.listGameplayRealms("scope-proof"))
        .thenReturn(java.util.List.of(target));
    org.mockito.Mockito.when(
            gameSessionClient.getAdmissionPointer(tenant, "scope-proof", "production"))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                .setWorldSlug("scope-proof")
                .setRealmSlug("production")
                .setTenantId(Long.toString(tenant))
                .setGameInstanceId("44")
                .setRealmId(realm)
                .setPlayableStateNamespaceId(namespace)
                .setCatalogRevision(23L)
                .setPointerVersion(17L)
                .setVisible(true)
                .setPublicProductionRealm(true)
                .setStateScope("SHARED")
                .setCharacterCreationPolicy("ALLOW_NEW")
                .build());
    var bootstrap = accountService.issuePlayerBootstrap(username, "password123");
    String browser =
        accountService
            .listBootstrapRealms(bootstrap.bootstrapToken(), "scope-proof")
            .getFirst()
            .connectScopeId();
    var player =
        net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
            .setAccountId(account.id())
            .setTenantId(Long.toString(tenant))
            .setRealmId(realm)
            .setPlayableStateNamespaceId(namespace)
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("44")
            .setSessionId("controlled-session")
            .setRequestId("scope-direct-" + suffix)
            .build();
    var request =
        net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest.newBuilder()
            .setPlayerContext(player)
            .setTenantId(Long.toString(tenant))
            .setRealmId(realm)
            .setWorldSlug("scope-proof")
            .setRealmSlug("production")
            .setPlayableStateNamespaceId(namespace)
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("44")
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .build();
    var response =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse>();
    var receiver =
        new net.firedevops.firemud.accountservice.service.impl.AccountGrpcService(
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.service.PingService.class),
            accountService,
            null,
            "test");
    var context =
        io.grpc.Context.ROOT.withValue(
            net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
            new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
                "spiffe://firemud/ns/test/sa/game-session-service",
                "test",
                "game-session-service"));
    context.run(
        () ->
            receiver.issueDirectTextConnectScope(
                request,
                new io.grpc.stub.StreamObserver<>() {
                  @Override
                  public void onNext(
                      net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse value) {
                    response.set(value);
                  }

                  @Override
                  public void onError(Throwable error) {
                    org.junit.jupiter.api.Assertions.fail(error);
                  }

                  @Override
                  public void onCompleted() {}
                }));
    var directResponse =
        java.util.Objects.requireNonNull(response.get(), "Expected controlled direct response");
    assertThat(directResponse.hasError()).isFalse();
    String direct = directResponse.getConnectScopeId();
    var scopes =
        new net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository(dsl);
    for (String token : java.util.List.of(browser, direct)) {
      var claims = jwtUtil.parseToken(token).getPayload();
      assertThat(claims.getSubject()).isEqualTo(account.id());
      assertThat(claims.get("accountId", String.class)).isEqualTo(account.id());
      var retained = scopes.find(token).orElseThrow();
      assertThat(retained.accountId()).isEqualTo(row);
      assertThat(retained.snapshotDigest())
          .isEqualTo(net.firedevops.firemud.accountservice.dto.AccountJoinDigest.scope(retained));
      var held =
          org.junit.jupiter.api.Assertions.assertThrows(
              net.firedevops.firemud.accountservice.service.exception.AuthenticationException.class,
              () ->
                  accountService.issueConnectToken(
                      bootstrap.bootstrapToken(),
                      new net.firedevops.firemud.accountservice.dto.ConnectTokenRequest(
                          token, "held-" + suffix)));
      assertThat(held.getCode()).isEqualTo("AUTH_UNAVAILABLE");
      assertThat(scopes.find(token)).contains(retained);
    }
    var browserJoin =
        new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
            browser, "scope-browser-" + suffix);
    var first = accountService.joinPublicProduction(bootstrap.bootstrapToken(), browserJoin);
    assertThat(first.success()).isTrue();
    assertThat(first.accountId()).isEqualTo(account.id());
    assertThat(
            accountService.joinPublicProduction(bootstrap.bootstrapToken(), browserJoin).replayed())
        .isTrue();
    var caller =
        new net.firedevops.firemud.accountservice.dto.DirectTextCallerContext(
            row,
            tenant,
            UUID.fromString(realm),
            namespace,
            "SHARED",
            44L,
            "controlled-session",
            "scope-direct-" + suffix);
    var directJoin =
        new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
            direct, caller.requestId());
    assertThat(accountService.joinPublicProductionFromGameSession(caller, directJoin).success())
        .isTrue();
    assertThat(accountService.joinPublicProductionFromGameSession(caller, directJoin).replayed())
        .isTrue();
    assertThat(accountService.joinPublicProductionFromGameSession(caller, directJoin).accountId())
        .isEqualTo(account.id());
    dsl.execute(
        "INSERT INTO profiles(account_id,tenant_id,display_name,bio) VALUES(?,?,?,?)",
        row,
        tenant,
        "owner profile",
        "initial");
    assertThat(accountService.getProfile(tenant, row).accountId()).isEqualTo(account.id());
    assertThat(
            accountService
                .updateProfile(
                    new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                        tenant,
                        account.id(),
                        "updated profile",
                        "updated",
                        net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy
                            .PRIVATE))
                .accountId())
        .isEqualTo(account.id());
    assertThat(accountService.exportAccountData(row).profiles())
        .allSatisfy(profile -> assertThat(profile.accountId()).isEqualTo(account.id()));
    assertThat(accountService.exportTenantData(tenant, row).profile().accountId())
        .isEqualTo(account.id());
    assertThat(httpAuthProperties.isEnabled()).isTrue();
    assertThat(httpAuthProperties.getRoleRequirement())
        .isEqualTo(HttpAuthRoleRequirement.AUTHENTICATED);
    assertThat(httpJwtAuthInterceptorProvider.getIfAvailable()).isNotNull();
    String ordinary = accountService.authenticate(username, "password123").authToken();
    HttpResponse<String> profileResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/profiles/"
                            + account.id()
                            + "?tenantId="
                            + tenant))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(profileResponse.statusCode())
        .withFailMessage(
            "Profile HTTP status %s, body: %s",
            profileResponse.statusCode(), profileResponse.body())
        .isEqualTo(200);
    assertThat(jsonMapper.readTree(profileResponse.body()).path("data").path("accountId").asText())
        .isEqualTo(account.id());
    assertThat(
            java.util.Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT account_id FROM profiles WHERE account_id=? AND tenant_id=?",
                        row,
                        tenant),
                    "Expected retained private profile owner row")
                .get(0, Long.class))
        .isEqualTo(row);
    assertThat(scopes.find(browser)).isPresent();
    assertThat(scopes.find(direct)).isPresent();
  }

  private void assertOrdinaryCredentialPreservesSocialGuildSelf(
      String token, String accountUuid, String otherAccountUuid) {
    var guild =
        org.mockito.Mockito.mock(net.firedevops.firemud.socialgroups.service.GuildService.class);
    org.mockito.Mockito.when(guild.createGuild(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              net.firedevops.firemud.socialgroups.dto.CreateGuildRequest request =
                  invocation.getArgument(0);
              return new net.firedevops.firemud.socialgroups.dto.GuildDto(
                  91L,
                  request.tenantId(),
                  request.name(),
                  request.ownerAccountId(),
                  java.time.Instant.now());
            });
    assertThat(jwtUtil.parseToken(token).getPayload().get("globalRoles"))
        .isEqualTo(java.util.List.of());
    var same = invokeSocialCreateGuild(token, accountUuid, guild);
    assertThat(same.hasError()).isFalse();
    assertThat(same.getGuildId()).isEqualTo("91");
    org.mockito.Mockito.verify(guild)
        .createGuild(
            new net.firedevops.firemud.socialgroups.dto.CreateGuildRequest(
                7L, accountUuid, "credential guild"));
    org.mockito.Mockito.clearInvocations(guild);
    var other = invokeSocialCreateGuild(token, otherAccountUuid, guild);
    assertThat(other.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    for (String malformed :
        java.util.List.of(
            "11", "00000000-0000-0000-0000-000000000000", "10000000-0000-4000-8000-00000000000A")) {
      assertThat(invokeSocialCreateGuild(token, malformed, guild).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
    }
    org.mockito.Mockito.verifyNoInteractions(guild);
  }

  private void assertOrdinaryModeratorPreservesGuildTenantAccess(
      String token, String accountUuid, String other) {
    var guild =
        org.mockito.Mockito.mock(net.firedevops.firemud.socialgroups.service.GuildService.class);
    org.mockito.Mockito.when(guild.createGuild(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              net.firedevops.firemud.socialgroups.dto.CreateGuildRequest request =
                  invocation.getArgument(0);
              return new net.firedevops.firemud.socialgroups.dto.GuildDto(
                  92L,
                  request.tenantId(),
                  request.name(),
                  request.ownerAccountId(),
                  java.time.Instant.now());
            });
    assertThat(jwtUtil.parseToken(token).getPayload().get("globalRoles"))
        .isEqualTo(java.util.List.of("moderator"));
    assertThat(invokeSocialCreateGuild(token, other, guild).hasError()).isFalse();
    org.mockito.Mockito.verify(guild)
        .createGuild(
            new net.firedevops.firemud.socialgroups.dto.CreateGuildRequest(
                7L, other, "credential guild"));
    net.firedevops.firemud.common.security.SessionContext.clear();
    try {
      net.firedevops.firemud.common.security.SessionClaims.fromJwt(jwtUtil.parseToken(token))
          .applyToSession();
      var guard = new net.firedevops.firemud.socialgroups.security.SocialAccessGuard();
      assertThat(guard.hasAccountAccess(7L, accountUuid)).isTrue();
      assertThat(guard.hasAccountAccess(7L, other)).isFalse();
      assertThat(guard.hasGuildOwnerAccess(7L, other)).isTrue();
    } finally {
      net.firedevops.firemud.common.security.SessionContext.clear();
    }
  }

  private net.firedevops.firemud.socialgroups.v1.CreateGuildResponse invokeSocialCreateGuild(
      String token, String owner, net.firedevops.firemud.socialgroups.service.GuildService guild) {
    var response =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.socialgroups.v1.CreateGuildResponse>();
    var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    try {
      var receiver =
          new net.firedevops.firemud.socialgroups.service.impl.SocialGroupsGrpcService(
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.socialgroups.service.PingService.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.socialgroups.service.ChatService.class),
              guild,
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.socialgroups.service.FriendService.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.socialgroups.service.MailService.class),
              new net.firedevops.firemud.socialgroups.security.SocialAccessGuard(),
              metrics);
      net.firedevops.firemud.common.security.SessionContext.clear();
      try {
        net.firedevops.firemud.common.security.SessionClaims.fromJwt(jwtUtil.parseToken(token))
            .applyToSession();
        receiver.createGuild(
            net.firedevops.firemud.socialgroups.v1.CreateGuildRequest.newBuilder()
                .setTenantId("7")
                .setOwnerAccountId(owner)
                .setName("credential guild")
                .build(),
            new io.grpc.stub.StreamObserver<>() {
              @Override
              public void onNext(net.firedevops.firemud.socialgroups.v1.CreateGuildResponse value) {
                response.set(value);
              }

              @Override
              public void onError(Throwable error) {
                org.junit.jupiter.api.Assertions.fail(error);
              }

              @Override
              public void onCompleted() {}
            });
      } finally {
        net.firedevops.firemud.common.security.SessionContext.clear();
      }
    } finally {
      metrics.close();
    }
    return java.util.Objects.requireNonNull(response.get(), "Expected Social CreateGuild response");
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
    String accountUuid = java.util.UUID.randomUUID().toString();
    String token =
        jwtUtil.generateToken(
            accountUuid,
            java.util.Map.of(
                "accountId", accountUuid, "globalRoles", java.util.List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/accounts/not-a-uuid/export"))
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
    String accountUuid = "6bb95a5e-3b0a-4eac-85d0-5c1b83467ad2";
    String token = jwtUtil.generateToken(accountUuid, java.util.Map.of("accountId", accountUuid));
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
    String accountUuid = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";
    String token = jwtUtil.generateToken(accountUuid, java.util.Map.of("accountId", accountUuid));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":0,"accountId":"b8d093f7-cb70-40ed-9fac-3c82d4bf28f1","displayName":"demo","bio":"bio","presenceVisibilityPolicy":"PRIVATE"}
                    """))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be positive\"");
  }
}
