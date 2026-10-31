package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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

  @Test
  void browserAndControlledDirectScopesRetainUuidClaimsAndPrivateJoinRowsWithPreparedHttpCaller()
      throws Exception {
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
            .setAccountId(Long.toString(row))
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
        "INSERT INTO profiles(account_id,tenant_id,display_name,bio,presence_visibility_policy) "
            + "VALUES(?,?,?,?, 'HIDDEN_STAFF')",
        row,
        tenant,
        "owner profile",
        "initial");
    var retainedProfile = accountService.getProfile(tenant, row);
    assertThat(retainedProfile.accountId()).isEqualTo(account.id());
    assertThat(retainedProfile.presenceVisibilityPolicy())
        .isEqualTo(
            net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy.PRIVATE);
    assertThat(jsonMapper.valueToTree(retainedProfile).path("presenceVisibilityPolicy").asText())
        .isEqualTo("PRIVATE");
    var retainedExport = accountService.exportAccountData(row);
    assertThat(
            jsonMapper
                .valueToTree(retainedExport)
                .path("profiles")
                .get(0)
                .path("presenceVisibilityPolicy")
                .asText())
        .isEqualTo("PRIVATE");
    assertThat(
            dsl.fetchValue(
                "SELECT presence_visibility_policy FROM profiles WHERE account_id=? "
                    + "AND tenant_id=?",
                row,
                tenant))
        .isEqualTo("HIDDEN_STAFF");
    assertThat(
            accountService
                .updateProfile(
                    new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                        tenant,
                        row,
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
    String numericProducer = accountService.authenticate(username, "password123").authToken();
    String accountUuid = account.id();
    String ordinary =
        controlledUuidCallerAfterNumericProducerRefusal(
            numericProducer,
            accountUuid,
            "/profiles/" + accountUuid + "?tenantId=" + tenant,
            "/profiles/" + accountUuid,
            profileUpdateBody(tenant, accountUuid, "refused numeric producer"));
    HttpResponse<String> profileResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/profiles/"
                            + accountUuid
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
        .isEqualTo(accountUuid);

    HttpResponse<String> profileUpdateResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        profileUpdateBody(tenant, accountUuid, "updated over UUID REST")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(profileUpdateResponse.statusCode())
        .withFailMessage(
            "Profile update HTTP status %s, body: %s",
            profileUpdateResponse.statusCode(), profileUpdateResponse.body())
        .isEqualTo(200);
    assertThat(
            jsonMapper
                .readTree(profileUpdateResponse.body())
                .path("data")
                .path("accountId")
                .asText())
        .isEqualTo(accountUuid);
    assertThat(
            dsl.fetchValue(
                "SELECT display_name FROM profiles WHERE account_id=? AND tenant_id=?",
                row,
                tenant))
        .isEqualTo("updated over UUID REST");

    for (String invalidSelector :
        java.util.List.of(
            Long.toString(row),
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            accountUuid.toUpperCase(java.util.Locale.ROOT))) {
      HttpResponse<String> invalidPathResponse =
          HTTP_CLIENT.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/profiles/"
                              + invalidSelector
                              + "?tenantId="
                              + tenant))
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(invalidPathResponse.statusCode()).isEqualTo(400);
      assertThat(invalidPathResponse.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    }

    for (String invalidSelector :
        java.util.List.of(
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            accountUuid.toUpperCase(java.util.Locale.ROOT))) {
      HttpResponse<String> invalidBodyResponse =
          HTTP_CLIENT.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                  .header(HttpHeaders.CONTENT_TYPE, "application/json")
                  .PUT(
                      HttpRequest.BodyPublishers.ofString(
                          profileUpdateBody(tenant, invalidSelector, "invalid selector")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(invalidBodyResponse.statusCode()).isEqualTo(400);
      assertThat(invalidBodyResponse.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    }

    HttpResponse<String> numericBodyResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"tenantId\":"
                            + tenant
                            + ",\"accountId\":"
                            + row
                            + ",\"displayName\":\"numeric selector\",\"bio\":\"bio\","
                            + "\"presenceVisibilityPolicy\":\"PRIVATE\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(numericBodyResponse.statusCode()).isEqualTo(400);
    assertThat(numericBodyResponse.body()).contains("\"code\":\"INVALID_ARGUMENT\"");

    long unjoinedTenant = 999999999999L;
    HttpResponse<String> missingMembershipResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/profiles/"
                            + accountUuid
                            + "?tenantId="
                            + unjoinedTenant))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(missingMembershipResponse.statusCode()).isEqualTo(400);
    assertThat(missingMembershipResponse.body()).contains("\"message\":\"Profile not found\"");

    var otherAccount =
        accountService.createAccount(
            new net.firedevops.firemud.accountservice.dto.CreateAccountRequest(
                "other-" + suffix, "other-" + suffix + "@example.com", "password123"));
    HttpResponse<String> mismatchedBodyResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        profileUpdateBody(tenant, otherAccount.id(), "mismatch")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(mismatchedBodyResponse.statusCode()).isEqualTo(400);
    assertThat(mismatchedBodyResponse.body()).contains("accountId must match the path selector");

    HttpResponse<String> otherOwnerResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/profiles/"
                            + otherAccount.id()
                            + "?tenantId="
                            + tenant))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(otherOwnerResponse.statusCode()).isEqualTo(403);
    assertThat(otherOwnerResponse.body()).contains("\"code\":\"PERMISSION_DENIED\"");

    HttpResponse<String> reservedPolicyResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinary)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"tenantId\":"
                            + tenant
                            + ",\"accountId\":\""
                            + accountUuid
                            + "\",\"displayName\":\"reserved\",\"bio\":\"bio\","
                            + "\"presenceVisibilityPolicy\":\"HIDDEN_STAFF\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(reservedPolicyResponse.statusCode()).isEqualTo(400);
    assertThat(reservedPolicyResponse.body())
        .contains("Profile presence visibility policy is not selectable");
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

  private HttpResponse<String> bootstrapWorlds(String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/bootstrap/worlds"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String profileUpdateBody(long tenantId, String accountId, String displayName) {
    return "{\"tenantId\":"
        + tenantId
        + ",\"accountId\":\""
        + accountId
        + "\",\"displayName\":\""
        + displayName
        + "\",\"bio\":\"bio\",\"presenceVisibilityPolicy\":\"PRIVATE\"}";
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
            "f22f310d-5464-45f0-9dc4-c37353004403",
            java.util.Map.of(
                "accountId",
                "f22f310d-5464-45f0-9dc4-c37353004403",
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
        .contains("\"message\":\"accountId must be a canonical non-nil Account UUID\"");
  }

  @Test
  void preparedAccountSelectorsRejectNumericProducerAndPreserveAuthorizationAndDenials()
      throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "account-selector-" + suffix;
    String email = username + "@example.com";
    var account =
        accountService.createAccount(
            new net.firedevops.firemud.accountservice.dto.CreateAccountRequest(
                username, email, "password123"));
    var otherAccount =
        accountService.createAccount(
            new net.firedevops.firemud.accountservice.dto.CreateAccountRequest(
                "other-selector-" + suffix,
                "other-selector-" + suffix + "@example.com",
                "password123"));
    long accountRow =
        java.util.Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id FROM accounts WHERE account_uuid = ?", UUID.fromString(account.id()))
                .fetchOne(0, Long.class),
            "Expected registered Account row");
    long otherAccountRow =
        java.util.Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id FROM accounts WHERE account_uuid = ?",
                    UUID.fromString(otherAccount.id()))
                .fetchOne(0, Long.class),
            "Expected other registered Account row");
    long tenantId = 987654321L;
    dsl.execute(
        "INSERT INTO profiles(account_id, tenant_id, display_name, bio) VALUES (?, ?, ?, ?)",
        accountRow,
        tenantId,
        "Account selector profile",
        "retained during denied deletion");

    String numericProducer = accountService.authenticate(username, "password123").authToken();
    String accountUuid = account.id();
    String ordinary =
        controlledUuidCallerAfterNumericProducerRefusal(
            numericProducer,
            accountUuid,
            "/accounts/" + accountUuid + "/login-auth-modes",
            "/accounts/" + accountUuid + "/login-auth-modes",
            "{\"loginAuthModes\":[\"PASSWORD\"]}");

    HttpResponse<String> modesResponse =
        sendAccountRequest("GET", "/accounts/" + accountUuid + "/login-auth-modes", ordinary, null);
    assertThat(modesResponse.statusCode()).isEqualTo(200);
    assertThat(
            jsonMapper.readTree(modesResponse.body()).path("data").path("loginAuthModes").isArray())
        .isTrue();

    HttpResponse<String> exportResponse =
        sendAccountRequest("GET", "/accounts/" + accountUuid + "/export", ordinary, null);
    assertThat(exportResponse.statusCode()).isEqualTo(200);
    assertThat(
            jsonMapper
                .readTree(exportResponse.body())
                .path("data")
                .path("account")
                .path("id")
                .asText())
        .isEqualTo(accountUuid);
    assertThat(
            jsonMapper
                .readTree(exportResponse.body())
                .path("data")
                .path("profiles")
                .path(0)
                .path("accountId")
                .asText())
        .isEqualTo(accountUuid);

    HttpResponse<String> tenantExportResponse =
        sendAccountRequest(
            "GET",
            "/accounts/" + accountUuid + "/tenant-export?tenantId=" + tenantId,
            ordinary,
            null);
    assertThat(tenantExportResponse.statusCode()).isEqualTo(200);
    assertThat(
            jsonMapper
                .readTree(tenantExportResponse.body())
                .path("data")
                .path("profile")
                .path("accountId")
                .asText())
        .isEqualTo(accountUuid);

    for (java.util.Map.Entry<String, String> request :
        List.of(
            java.util.Map.entry("GET", "/accounts/" + otherAccount.id() + "/login-auth-modes"),
            java.util.Map.entry("PUT", "/accounts/" + otherAccount.id() + "/login-auth-modes"),
            java.util.Map.entry("GET", "/accounts/" + otherAccount.id() + "/export"),
            java.util.Map.entry(
                "GET", "/accounts/" + otherAccount.id() + "/tenant-export?tenantId=" + tenantId),
            java.util.Map.entry("DELETE", "/accounts/" + otherAccount.id()))) {
      String body = request.getKey().equals("PUT") ? "{\"loginAuthModes\":[\"PASSWORD\"]}" : null;
      HttpResponse<String> refused =
          sendAccountRequest(request.getKey(), request.getValue(), ordinary, body);
      assertThat(refused.statusCode()).isEqualTo(403);
      assertThat(refused.body()).contains("\"code\":\"PERMISSION_DENIED\"");
      String absentPath =
          request.getValue().replace(otherAccount.id(), UUID.randomUUID().toString());
      HttpResponse<String> absent =
          sendAccountRequest(request.getKey(), absentPath, ordinary, body);
      assertThat(absent.statusCode()).isEqualTo(403);
      assertThat(absent.body()).isEqualTo(refused.body());
    }

    String originalLoginModes =
        dsl.resultQuery("SELECT login_auth_modes FROM accounts WHERE id = ?", accountRow)
            .fetchOne(0, String.class);
    Long originalUuidSourceNumericId =
        dsl.resultQuery(
                "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?", accountRow)
            .fetchOne(0, Long.class);
    String originalUuidProvenance =
        dsl.resultQuery("SELECT account_uuid_provenance FROM accounts WHERE id = ?", accountRow)
            .fetchOne(0, String.class);

    HttpResponse<String> factorUpdateResponse =
        sendAccountRequest(
            "PUT",
            "/accounts/" + accountUuid + "/login-auth-modes",
            ordinary,
            "{\"loginAuthModes\":[\"PASSWORD\"]}");
    assertThat(factorUpdateResponse.statusCode()).isEqualTo(501);
    assertThat(factorUpdateResponse.body()).contains("\"code\":\"NOT_IMPLEMENTED\"");
    assertThat(
            dsl.resultQuery("SELECT login_auth_modes FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, String.class))
        .isEqualTo(originalLoginModes);
    HttpResponse<String> deleteRefused =
        sendAccountRequest("DELETE", "/accounts/" + accountUuid, ordinary, null);
    assertThat(deleteRefused.statusCode()).isEqualTo(409);
    assertThat(deleteRefused.body()).contains("\"code\":\"ACCOUNT_DELETE_UNAVAILABLE\"");
    assertThat(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, UUID.class))
        .isEqualTo(UUID.fromString(accountUuid));
    assertThat(
            dsl.resultQuery("SELECT login_auth_modes FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, String.class))
        .isEqualTo(originalLoginModes);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM profiles WHERE account_id = ? AND tenant_id = ?",
                accountRow,
                tenantId))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, Long.class))
        .isEqualTo(originalUuidSourceNumericId);
    assertThat(
            dsl.resultQuery("SELECT account_uuid_provenance FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, String.class))
        .isEqualTo(originalUuidProvenance);
    assertThat(
            dsl.resultQuery("SELECT id FROM accounts WHERE id = ?", otherAccountRow)
                .fetchOne(0, Long.class))
        .isEqualTo(otherAccountRow);

    List<String> invalidSelectors =
        List.of(
            Long.toString(accountRow),
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            accountUuid.toUpperCase(java.util.Locale.ROOT));
    for (String selector : invalidSelectors) {
      List<java.util.Map.Entry<String, String>> requests =
          List.of(
              java.util.Map.entry("GET", "/accounts/" + selector + "/login-auth-modes"),
              java.util.Map.entry("PUT", "/accounts/" + selector + "/login-auth-modes"),
              java.util.Map.entry("GET", "/accounts/" + selector + "/export"),
              java.util.Map.entry(
                  "GET", "/accounts/" + selector + "/tenant-export?tenantId=" + tenantId),
              java.util.Map.entry("DELETE", "/accounts/" + selector));
      for (java.util.Map.Entry<String, String> request : requests) {
        String body = request.getKey().equals("PUT") ? "{\"loginAuthModes\":[\"PASSWORD\"]}" : null;
        HttpResponse<String> rejected =
            sendAccountRequest(request.getKey(), request.getValue(), ordinary, body);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
      }
    }

    assertThat(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, UUID.class))
        .isEqualTo(UUID.fromString(accountUuid));
    assertThat(
            dsl.resultQuery("SELECT login_auth_modes FROM accounts WHERE id = ?", accountRow)
                .fetchOne(0, String.class))
        .isEqualTo(originalLoginModes);
  }

  /** Test-owned caller proof for prepared HTTP readers; this does not qualify UUID issuance. */
  private String controlledUuidCallerAfterNumericProducerRefusal(
      String numericProducer,
      String accountUuid,
      String readPath,
      String writePath,
      String writeBody)
      throws Exception {
    var claims = jwtUtil.parseToken(numericProducer).getPayload();
    Number privateAccountRow =
        java.util.Objects.requireNonNull(
            claims.get("accountId", Number.class), "Expected parent-issued numeric Account claim");
    assertThat(claims.getSubject()).isEqualTo(privateAccountRow.toString());
    assertThat(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", privateAccountRow)
                .fetchOne(0, UUID.class))
        .isEqualTo(UUID.fromString(accountUuid));
    String accountBefore =
        dsl.resultQuery(
                "SELECT row_to_json(a)::text || ':' || a.xmin::text FROM accounts a WHERE id = ?",
                privateAccountRow)
            .fetchOne(0, String.class);
    List<String> profilesBefore = preparationProfileRows(privateAccountRow);

    assertThat(sendAccountRequest("GET", readPath, numericProducer, null).statusCode())
        .isEqualTo(401);
    assertThat(sendAccountRequest("PUT", writePath, numericProducer, writeBody).statusCode())
        .isEqualTo(401);
    assertThat(
            dsl.resultQuery(
                    "SELECT row_to_json(a)::text || ':' || a.xmin::text FROM accounts a WHERE id = ?",
                    privateAccountRow)
                .fetchOne(0, String.class))
        .isEqualTo(accountBefore);
    assertThat(preparationProfileRows(privateAccountRow)).isEqualTo(profilesBefore);

    return jwtUtil.generateToken(accountUuid, java.util.Map.of("accountId", accountUuid));
  }

  private List<String> preparationProfileRows(Number privateAccountRow) {
    return dsl.resultQuery(
            "SELECT row_to_json(p)::text || ':' || p.xmin::text FROM profiles p "
                + "WHERE account_id = ? ORDER BY id",
            privateAccountRow)
        .fetch(0, String.class);
  }

  private HttpResponse<String> sendAccountRequest(
      String method, String path, String token, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    switch (method) {
      case "GET" -> request.GET();
      case "DELETE" -> request.DELETE();
      case "PUT" ->
          request
              .header(HttpHeaders.CONTENT_TYPE, "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString(body));
      default -> throw new IllegalArgumentException("Unsupported Account test request method");
    }
    return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void linkExternalRouteIsUnavailableWithAuthenticatedRequest() throws Exception {
    String token =
        jwtUtil.generateToken(
            "d09f80cb-a46a-415e-8d35-c9e8068e49de",
            java.util.Map.of("accountId", "d09f80cb-a46a-415e-8d35-c9e8068e49de"));
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
    String token =
        jwtUtil.generateToken(
            "d09f80cb-a46a-415e-8d35-c9e8068e49de",
            java.util.Map.of("accountId", "d09f80cb-a46a-415e-8d35-c9e8068e49de"));
    String accountUuid = "d09f80cb-a46a-415e-8d35-c9e8068e49de";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/profiles/" + accountUuid))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":0,"accountId":"d09f80cb-a46a-415e-8d35-c9e8068e49de","displayName":"demo","bio":"bio","presenceVisibilityPolicy":"PRIVATE"}
                    """))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be positive\"");
  }
}
