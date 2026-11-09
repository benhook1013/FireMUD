package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateMutationRequest;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository.Capture;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapWorldsOwner;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthRoleRequirement;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
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
import org.springframework.transaction.support.TransactionTemplate;
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
  @Autowired private javax.sql.DataSource dataSource;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private AccountServiceImpl canonicalFacade;
  @Autowired private SessionService realAccountSessions;
  @Autowired private HttpAuthProperties httpAuthProperties;
  @Autowired private ObjectProvider<HttpJwtAuthInterceptor> httpJwtAuthInterceptorProvider;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private net.firedevops.firemud.accountservice.repository.AccountRepository accountRepository;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository
      emailVerificationTokenRepository;

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
  void concurrentEmailVerificationClaimsTheExactTokenOnce() throws Exception {
    var fixture = emailVerificationFixture();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var located = new CountDownLatch(2);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var original = invocation.callRealMethod();
              located.countDown();
              if (!located.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Both verification attempts must read the token");
              }
              return original;
            })
        .when(emailVerificationTokenRepository)
        .findByToken(fixture.token());
    var executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> attempt =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Email verification start was not released");
            }
            try {
              verifyEmail(fixture.token());
              return true;
            } catch (IllegalArgumentException consumed) {
              return false;
            }
          };
      var first = executor.submit(attempt);
      var second = executor.submit(attempt);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isNotEqualTo(second.get(30, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
    assertThat(
            dsl.fetchValue("SELECT email_verified FROM accounts WHERE id = ?", fixture.accountId()))
        .isEqualTo(true);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM email_verification_token WHERE token = ?", fixture.token()))
        .isEqualTo(0L);
  }

  @Test
  void expiredAndExactlyExpiringEmailVerificationTokensCannotMutateAccount() {
    for (boolean equality : List.of(false, true)) {
      var fixture = emailVerificationFixture();
      var expiry = LocalDateTime.now().minusSeconds(1);
      dsl.execute(
          "UPDATE email_verification_token SET expires_at = ? WHERE token = ?",
          expiry,
          fixture.token());
      var before = emailVerificationState(fixture);
      if (equality) {
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                status -> {
                  accountRepository.findByIdForUpdate(fixture.accountId()).orElseThrow();
                  var token =
                      emailVerificationTokenRepository.findByToken(fixture.token()).orElseThrow();
                  assertThat(
                          emailVerificationTokenRepository.consumeIfUnexpired(
                              token, token.getExpiresAt()))
                      .isFalse();
                });
      } else {
        assertThatThrownBy(() -> verifyEmail(fixture.token()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Token expired");
      }
      assertThat(emailVerificationState(fixture)).isEqualTo(before);
    }
  }

  @Test
  void changedOriginalEmailVerificationTokenCannotMutateAccount() {
    for (String field : List.of("token", "expires_at", "account_id")) {
      var fixture = emailVerificationFixture();
      var other = emailVerificationFixture();
      var before = emailVerificationState(fixture);
      var changed = new AtomicBoolean();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var original = invocation.callRealMethod();
                if (changed.compareAndSet(false, true)) {
                  switch (field) {
                    case "token" ->
                        dsl.execute(
                            "UPDATE email_verification_token SET token = ? WHERE token = ?",
                            fixture.token() + "-changed",
                            fixture.token());
                    case "expires_at" ->
                        dsl.execute(
                            "UPDATE email_verification_token SET expires_at = expires_at + INTERVAL '1 second' WHERE token = ?",
                            fixture.token());
                    case "account_id" ->
                        dsl.execute(
                            "UPDATE email_verification_token SET account_id = ? WHERE token = ?",
                            other.accountId(),
                            fixture.token());
                    default -> throw new AssertionError(field);
                  }
                }
                return original;
              })
          .when(emailVerificationTokenRepository)
          .findByToken(fixture.token());

      assertThatThrownBy(() -> verifyEmail(fixture.token()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid token");

      // The injected change shares the owner transaction and rolls back with the rejected claim.
      assertThat(emailVerificationState(fixture)).isEqualTo(before);
      assertThat(
              dsl.fetchValue("SELECT email_verified FROM accounts WHERE id = ?", other.accountId()))
          .isEqualTo(false);
    }
  }

  @Test
  void emailVerificationRollbackRestoresTokenAccountAndSourceEvidence() {
    var fixture = emailVerificationFixture();
    var before = emailVerificationState(fixture);
    var transaction = new TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      verifyEmail(fixture.token());
                      throw new IllegalStateException("failure after verification source update");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("failure after verification source update");
    assertThat(emailVerificationState(fixture)).isEqualTo(before);
    verifyEmail(fixture.token());
    assertThat(
            dsl.fetchValue("SELECT email_verified FROM accounts WHERE id = ?", fixture.accountId()))
        .isEqualTo(true);
  }

  @Test
  void emailVerificationWithoutOwnerBaselineRollsBackClaimWithoutEnrollingRetainedAccount() {
    String suffix = UUID.randomUUID().toString();
    // A raw retained-style row deliberately lacks repository-owned V40 source initialization.
    long accountId =
        java.util.Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "retained-" + suffix.substring(0, 8),
                    suffix + "@example.com",
                    "retained-password")
                .fetchOne(0, Long.class));
    var fixture = insertEmailVerificationToken(accountId);
    var before = emailVerificationState(fixture);
    assertThatThrownBy(() -> verifyEmail(fixture.token()))
        .isInstanceOf(SourceEvidenceUnavailableException.class);
    assertThat(emailVerificationState(fixture)).isEqualTo(before);
  }

  @Test
  void emailVerificationUsesFreshOwnerAfterConcurrentAccountUpdate() throws Exception {
    var fixture = emailVerificationFixture();
    var changed = new AtomicBoolean();
    var executor = Executors.newSingleThreadExecutor();
    try {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var original = invocation.callRealMethod();
                if (changed.compareAndSet(false, true)) {
                  executor
                      .submit(
                          () ->
                              new TransactionTemplate(transactionManager)
                                  .executeWithoutResult(
                                      status -> {
                                        var current =
                                            accountRepository
                                                .findByIdForUpdate(fixture.accountId())
                                                .orElseThrow();
                                        current.setUsername("current-" + fixture.accountId());
                                        current.setEmail(
                                            "current-" + fixture.accountId() + "@example.com");
                                        current.setPasswordHash("current-password-hash");
                                        current.setRole("player");
                                        current.setLoginAuthModes("EMAIL_OTP");
                                        accountRepository.save(current);
                                      }))
                      .get(30, TimeUnit.SECONDS);
                }
                return original;
              })
          .when(emailVerificationTokenRepository)
          .findByToken(fixture.token());
      UUID originalUuid =
          dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", fixture.accountId())
              .fetchOne(0, UUID.class);

      verifyEmail(fixture.token());

      var current = accountRepository.findById(fixture.accountId()).orElseThrow();
      assertThat(current.isEmailVerified()).isTrue();
      assertThat(current.getUsername()).isEqualTo("current-" + fixture.accountId());
      assertThat(current.getEmail()).isEqualTo("current-" + fixture.accountId() + "@example.com");
      assertThat(current.getPasswordHash()).isEqualTo("current-password-hash");
      assertThat(current.getRole()).isEqualTo("player");
      assertThat(current.getAccountUuid()).isEqualTo(originalUuid);
      assertThat(current.getAccountUuidSourceNumericId()).isEqualTo(fixture.accountId());
      assertThat(current.getAccountUuidProvenance())
          .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      assertThat(current.getLoginAuthModes()).isEqualTo("EMAIL_OTP");
    } finally {
      executor.shutdownNow();
    }
  }

  private EmailVerificationFixture emailVerificationFixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var account =
        accountService.createAccount(
            new CreateAccountRequest(
                "verify-" + suffix, "verify-" + suffix + "@example.com", "password123"));
    long accountId =
        java.util.Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id FROM accounts WHERE account_uuid = ?", UUID.fromString(account.id()))
                .fetchOne(0, Long.class));
    return insertEmailVerificationToken(accountId);
  }

  private EmailVerificationFixture insertEmailVerificationToken(long accountId) {
    String token = UUID.randomUUID().toString();
    dsl.execute(
        "INSERT INTO email_verification_token (account_id, token, expires_at) VALUES (?, ?, ?)",
        accountId,
        token,
        LocalDateTime.now().plusHours(1));
    return new EmailVerificationFixture(accountId, token);
  }

  private void verifyEmail(String token) {
    accountService.verifyEmail(new VerifyEmailRequest(token));
  }

  private List<String> emailVerificationState(EmailVerificationFixture fixture) {
    return List.of(
        dsl.resultQuery(
                "SELECT row_to_json(a)::text FROM accounts a WHERE id = ?", fixture.accountId())
            .fetchOne(0, String.class),
        dsl.resultQuery(
                "SELECT row_to_json(t)::text FROM email_verification_token t WHERE token = ?",
                fixture.token())
            .fetchOne(0, String.class),
        dsl.resultQuery(
                "SELECT COALESCE(json_agg(s ORDER BY outbox_stream_key)::text, '[]') "
                    + "FROM account_authority_source_records s WHERE account_uuid = "
                    + "(SELECT account_uuid FROM accounts WHERE id = ?)",
                fixture.accountId())
            .fetchOne(0, String.class),
        dsl.resultQuery(
                "SELECT COUNT(*)::text FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = 'account:auth-authority:v1:account/' || "
                    + "(SELECT account_uuid::text FROM accounts WHERE id = ?)",
                fixture.accountId())
            .fetchOne(0, String.class));
  }

  private record EmailVerificationFixture(long accountId, String token) {}

  @Test
  void publicJoinBrowserAndDirectScopesUseActualUuidIntentMembershipEventAndAuditOwners()
      throws Exception {
    var f = publicJoinFixture(false);
    var worlds = f.service.listBootstrapWorlds(f.bootstrap);
    assertThat(worlds).extracting(value -> value.worldSlug()).containsExactly(f.target.worldSlug());
    var browser = f.browserScope();
    var direct = f.directScope();
    for (String token : List.of(browser, direct)) {
      var claims = f.jwt.parseToken(token).getPayload();
      assertThat(claims.getSubject()).isEqualTo(f.account.getAccountUuid().toString());
      assertThat(claims.get("tenantId")).isEqualTo(f.tenantId.toString());
      assertThat(claims.get("gameInstanceId"))
          .isEqualTo(f.target.canonicalGameInstanceId().toString());
      var retained =
          f.in(
              () ->
                  f.scopes
                      .findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(token))
                      .orElseThrow());
      assertThat(retained.privateAccountId()).isEqualTo(f.account.getId());
      assertThat(retained.scopeDigestVersion()).isEqualTo(2);
      assertThat(retained.tenantUuid()).isEqualTo(f.tenantId);
      assertThat(f.in(() -> f.scopes.find(token))).isEmpty();
    }
    for (String token : List.of(browser, direct)) {
      assertThatThrownBy(
              () ->
                  f.service.issueConnectToken(
                      f.bootstrap,
                      new net.firedevops.firemud.accountservice.dto.ConnectTokenRequest(
                          token, "held-issuer")))
          .isInstanceOf(AuthenticationException.class)
          .extracting(value -> ((AuthenticationException) value).getCode())
          .isEqualTo("AUTH_UNAVAILABLE");
    }
    String request = UUID.randomUUID().toString();
    var first = f.join(browser, request);
    assertThat(first.success()).isTrue();
    assertThat(first.accountId()).isEqualTo(f.account.getAccountUuid().toString());
    assertThat(first.tenantId()).isEqualTo(f.tenantId);
    assertThat(first.membershipVersion()).isEqualTo(2L);
    assertThat(first.membershipAuthorityGeneration()).isEqualTo(1L);
    org.mockito.Mockito.verify(f.targets, org.mockito.Mockito.times(2))
        .readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any());
    var committed = f.state(request);
    var replay = f.join(browser, request);
    assertThat(replay.success()).isTrue();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.membershipId()).isEqualTo(first.membershipId());
    assertThat(f.state(request)).isEqualTo(committed);
    f.assertTransitionCount(1L);
    var active = f.directJoin(direct, UUID.randomUUID().toString());
    assertThat(active.success()).isTrue();
    assertThat(active.outcomeCode()).isEqualTo("ALREADY_ACTIVE");
    assertThat(active.membershipVersion()).isEqualTo(first.membershipVersion());
    assertThat(active.membershipAuthorityGeneration())
        .isEqualTo(first.membershipAuthorityGeneration());
    f.assertTransitionCount(1L);
  }

  @Test
  void publicJoinPaidOwnerUsesRealProvisioningCurrentRowFenceAndSealedStripeObservation() {
    var f = publicJoinFixture(true);
    String token = f.browserScope();
    int reads = f.paid.transport().reads();
    String request = UUID.randomUUID().toString();
    var first = f.join(token, request);
    assertThat(first.success()).isTrue();
    assertThat(f.paid.transport().reads()).isGreaterThan(reads);
    f.assertTransitionCount(1L);
    assertThat(f.operation(request).operationRepresentationVersion()).isEqualTo(2);
    assertThat(f.operation(request).entitlementVersion()).isPositive();
    assertThat(f.operation(request).terminalProof().entitlementVersion())
        .isEqualTo(f.operation(request).entitlementVersion());
  }

  @Test
  void publicJoinStrictPublishedWorldSourceOutageCannotCommitFromResolverOnlyEvidence() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    org.mockito.Mockito.when(
            f.targets.readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any()))
        .thenThrow(
            new IllegalStateException("controlled strict published/World ACTIVE source outage"));
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    assertThat(f.operation(request).status()).isEqualTo("PENDING");
    assertThat(f.operation(request).lastAttemptFailureCode())
        .isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    assertThat(f.membershipCount()).isZero();
    f.assertTransitionCount(0L);
  }

  @Test
  void publicJoinFailureBeforeCommitRollsBackMembershipPairEventAuditAndTerminalReceipt() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    var failed = new AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              if (failed.compareAndSet(false, true))
                throw new IllegalStateException("controlled terminal rollback");
              return result;
            })
        .when(f.operations)
        .commitCanonicalJoin(
            org.mockito.ArgumentMatchers.eq(request),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(() -> f.join(token, request)).isInstanceOf(AuthenticationException.class);
    assertThat(f.operation(request).status()).isEqualTo("PENDING");
    assertThat(f.operation(request).terminalProof()).isNull();
    assertThat(f.operation(request).requestDigest()).isNull();
    f.assertTransitionCount(0L);
    assertThat(f.membershipCount()).isZero();
    String intent = f.operation(request).intentDigest();
    assertThat(f.join(token, request).success()).isTrue();
    assertThat(f.operation(request).intentDigest()).isEqualTo(intent);
    f.assertTransitionCount(1L);
  }

  @Test
  void publicJoinLostCommitAcknowledgementReplaysTheCompleteCommittedOriginal() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    var lost = new AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              if (lost.compareAndSet(false, true)) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                          @Override
                          public void afterCommit() {
                            throw new IllegalStateException(
                                "controlled lost commit acknowledgement");
                          }
                        });
              }
              return result;
            })
        .when(f.operations)
        .commitCanonicalJoin(
            org.mockito.ArgumentMatchers.eq(request),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(() -> f.join(token, request)).isInstanceOf(AuthenticationException.class);
    assertThat(f.operation(request).status()).isEqualTo("COMMITTED");
    var committed = f.state(request);
    var replay = f.join(token, request);
    assertThat(replay.success()).isTrue();
    assertThat(replay.replayed()).isTrue();
    assertThat(f.state(request)).isEqualTo(committed);
    f.assertTransitionCount(1L);
  }

  @Test
  void publicJoinActualSqlAbortAtFinalEntitlementReadRetainsIntentAndRecoversOnce() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    var abort = new AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (abort.compareAndSet(true, false)) {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isTrue();
                dsl.fetch("SELECT 1 / 0");
              }
              return invocation.callRealMethod();
            })
        .when(f.demos)
        .revalidate(org.mockito.ArgumentMatchers.any());
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            AuthenticationException.class, () -> f.join(token, request));
    assertThat(failure.getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(sqlState(failure)).isEqualTo("22012");
    assertThat(f.operation(request).status()).isEqualTo("PENDING");
    assertThat(f.operation(request).lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.operation(request).requestDigest()).isNull();
    f.assertTransitionCount(0L);
    String intent = f.operation(request).intentDigest();
    assertThat(f.join(token, request).success()).isTrue();
    assertThat(f.operation(request).intentDigest()).isEqualTo(intent);
    f.assertTransitionCount(1L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void publicJoinUnavailableCanonicalSourceRetainsOriginalIntentAndRecovers(boolean sourceRead) {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    var outage = new AtomicBoolean(true);
    if (sourceRead) {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                if (outage.get())
                  throw new org.springframework.dao.DataAccessResourceFailureException(
                      "source unavailable");
                return invocation.callRealMethod();
              })
          .when(f.sources)
          .readCurrentSourceSelection(f.tenantId);
    } else {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                if (outage.get())
                  throw new org.springframework.dao.DataAccessResourceFailureException(
                      "demo unavailable");
                return invocation.callRealMethod();
              })
          .when(f.demos)
          .readCurrent(f.tenantId);
    }
    assertThatThrownBy(() -> f.join(token, request)).isInstanceOf(AuthenticationException.class);
    var pending = f.operation(request);
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.requestDigest()).isNull();
    assertThat(pending.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.membershipCount()).isZero();
    f.assertTransitionCount(0L);
    outage.set(false);
    assertThat(f.join(token, request).success()).isTrue();
    assertThat(f.operation(request).intentDigest()).isEqualTo(pending.intentDigest());
    f.assertTransitionCount(1L);
  }

  @Test
  void publicJoinLostResponseThenExpiredScopePreservesCompleteCommittedOriginal() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    assertThat(f.join(token, request).success()).isTrue();
    var committed = f.state(request);
    f.clock.offset = Duration.ofMinutes(3);
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(f.state(request)).isEqualTo(committed);
    f.assertTransitionCount(1L);
  }

  @Test
  void publicJoinExpiredPendingScopePreservesIntentAndCannotCommitMembership() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "controlled source outage"))
        .when(f.demos)
        .readCurrent(f.tenantId);
    assertThatThrownBy(() -> f.join(token, request)).isInstanceOf(AuthenticationException.class);
    var pending = f.state(request);
    f.clock.offset = Duration.ofMinutes(3);
    org.mockito.Mockito.doCallRealMethod().when(f.demos).readCurrent(f.tenantId);
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(f.state(request)).isEqualTo(pending);
    assertThat(f.operation(request).status()).isEqualTo("PENDING");
    assertThat(f.membershipCount()).isZero();
    f.assertTransitionCount(0L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void publicJoinExpiryDuringProcessingPreservesPendingIntentAndRollsBack(boolean duringProvider) {
    var f = publicJoinFixture(duringProvider);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    var intent = new java.util.concurrent.atomic.AtomicReference<String>();
    if (duringProvider) {
      f.paid
          .transport()
          .duringRead(
              () -> {
                intent.set(f.operation(request).intentDigest());
                f.clock.offset = Duration.ofMinutes(3);
              });
    } else {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var result =
                    (AccountJoinOperationRepository.CanonicalJoinOperationEvidence)
                        invocation.callRealMethod();
                // Reuse the owner receipt: a new transaction would wait on the owner's locks.
                intent.set(result.intentDigest());
                f.clock.offset = Duration.ofMinutes(3);
                return result;
              })
          .when(f.operations)
          .commitCanonicalJoin(
              org.mockito.ArgumentMatchers.eq(request),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.any());
    }
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(intent.get()).isNotBlank();
    assertThat(f.operation(request).intentDigest()).isEqualTo(intent.get());
    assertThat(f.operation(request).status()).isEqualTo("PENDING");
    assertThat(f.operation(request).requestDigest()).isNull();
    assertThat(f.operation(request).terminalProof()).isNull();
    assertThat(f.operation(request).lastAttemptFailureCode()).isNull();
    assertThat(f.membershipCount()).isZero();
    f.assertTransitionCount(0L);
    var pending = f.state(request);
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(f.state(request)).isEqualTo(pending);
  }

  @Test
  void publicJoinChangedCallerOrScopeCannotReplaceOriginalTerminalBytes() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    assertThat(f.join(token, request).success()).isTrue();
    var committed = f.state(request);
    String different = f.browserScope();
    assertThatThrownBy(() -> f.join(different, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(f.state(request)).isEqualTo(committed);
    f.assertTransitionCount(1L);
  }

  @Test
  void publicJoinFreshClosedPolicyPersistsDenialWithoutMembershipOrTransition() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    f.policy(false, true);
    String request = UUID.randomUUID().toString();
    var denied = f.join(token, request);
    assertThat(denied.success()).isFalse();
    assertThat(denied.outcomeCode()).isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(f.operation(request).status()).isEqualTo("FAILED");
    assertThat(f.operation(request).allowPublicJoin()).isFalse();
    assertThat(f.membershipCount()).isZero();
    f.assertTransitionCount(0L);
    var original = f.state(request);
    assertThat(f.join(token, request).replayed()).isTrue();
    assertThat(f.state(request)).isEqualTo(original);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void publicJoinTerminalEntitlementOutagePreservesOriginalBytesAndRecovers(boolean paid) {
    var f = publicJoinFixture(paid);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    var original = f.join(token, request);
    assertThat(original.success()).isTrue();
    var committed = f.state(request);
    if (paid) {
      // A complete but contradictory provider object cannot be used as cached current authority.
      f.paid.providerStatus("unpaid");
    } else {
      org.mockito.Mockito.doThrow(
              new org.springframework.dao.DataAccessResourceFailureException(
                  "controlled current demo outage"))
          .when(f.demos)
          .readCurrent(f.tenantId);
    }
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.state(request)).isEqualTo(committed);
    f.assertTransitionCount(1L);
    if (paid) f.paid.providerStatus("active");
    else org.mockito.Mockito.doCallRealMethod().when(f.demos).readCurrent(f.tenantId);
    var recovered = f.join(token, request);
    assertThat(recovered.success()).isTrue();
    assertThat(recovered.replayed()).isTrue();
    assertThat(recovered.membershipId()).isEqualTo(original.membershipId());
    assertThat(recovered.membershipVersion()).isEqualTo(original.membershipVersion());
    assertThat(recovered.membershipAuthorityGeneration())
        .isEqualTo(original.membershipAuthorityGeneration());
    assertThat(f.state(request)).isEqualTo(committed);
  }

  @Test
  void publicJoinChangedAvailablePolicyCannotRebindTheOriginalTerminalReceipt() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String request = UUID.randomUUID().toString();
    assertThat(f.join(token, request).success()).isTrue();
    var committed = f.state(request);
    // The new current version is still open; immutable request/policy evidence must still conflict.
    f.policy(true, true);
    assertThatThrownBy(() -> f.join(token, request))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(f.state(request)).isEqualTo(committed);
    f.assertTransitionCount(1L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void publicJoinRestoresControlledInactiveSnapshotOnceAndPreservesAuthorityGeneration(
      boolean paid) {
    var f = publicJoinFixture(paid);
    String token = f.browserScope();
    assertThat(f.join(token, UUID.randomUUID().toString()).success()).isTrue();
    f.seedControlledInactiveSnapshot();
    String request = UUID.randomUUID().toString();
    var restored = f.join(token, request);
    assertThat(restored.success()).isTrue();
    assertThat(restored.outcomeCode()).isEqualTo("JOINED");
    assertThat(restored.membershipVersion()).isEqualTo(4L);
    assertThat(restored.membershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(f.membershipCount()).isEqualTo(1L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                f.stream()))
        .isEqualTo(3L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE tenant_uuid = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                f.tenantId))
        .isEqualTo(2L);
    var restoredEvent = f.in(() -> f.outbox.findEvent(f.stream(), 3L).orElseThrow());
    var decodedRestoredEvent =
        MembershipAuthorityEventV1Codec.verify(
            new String(restoredEvent.payload(), StandardCharsets.UTF_8));
    assertThat(decodedRestoredEvent.requestId()).isEqualTo(request);
    assertThat(decodedRestoredEvent.outboxSequence()).isEqualTo("3");
    assertThat(decodedRestoredEvent.callerBoundAuthorityInvalidated()).isFalse();
    var committed = f.state(request);
    assertThat(f.join(token, request).replayed()).isTrue();
    assertThat(f.state(request)).isEqualTo(committed);
  }

  @Test
  void publicJoinActiveControlOnlyMemberReturnsCurrentSnapshotWithoutGrantingGameplay() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    assertThat(f.join(token, UUID.randomUUID().toString()).success()).isTrue();
    f.seedControlledSnapshot("ACTIVE", false, List.of("moderator"));
    f.policy(false, true);
    String request = UUID.randomUUID().toString();
    var result = f.join(token, request);
    assertThat(result.success()).isTrue();
    assertThat(result.outcomeCode()).isEqualTo("ALREADY_ACTIVE");
    assertThat(result.membershipVersion()).isEqualTo(3L);
    assertThat(result.membershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(result.gameplayAdmissionAllowed()).isFalse();
    assertThat(f.currentRoles()).containsExactly("moderator");
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                f.stream()))
        .isEqualTo(2L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE tenant_uuid = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                f.tenantId))
        .isEqualTo(1L);
    var committed = f.state(request);
    var replay = f.join(token, request);
    assertThat(replay.success()).isTrue();
    assertThat(replay.gameplayAdmissionAllowed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(f.state(request)).isEqualTo(committed);
    assertThat(f.currentRoles()).containsExactly("moderator");
  }

  @Test
  void publicJoinAnotherAuthenticatedAccountCannotConsumeTheOriginalScopeOrCreateIntent() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String suffix = UUID.randomUUID().toString();
    var other =
        accountService.createAccount(
            new CreateAccountRequest(
                "other-facade-" + suffix,
                "other-facade-" + suffix + "@example.test",
                "password123"));
    Account owner = f.accounts.findByAccountUuid(UUID.fromString(other.id())).orElseThrow();
    String bootstrap =
        f.jwt.generateToken(
            other.id(),
            Map.of(
                "aud",
                "player-bootstrap",
                "accountId",
                other.id(),
                "jti",
                UUID.randomUUID().toString()));
    org.mockito.Mockito.when(f.sessions.isAccountSessionActive(owner.getId(), bootstrap))
        .thenReturn(true);
    String request = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                f.service.joinPublicProduction(
                    bootstrap, new JoinPublicProductionRequest(token, request)))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(f.in(() -> f.operations.findCanonicalEvidenceByRequestId(request))).isEmpty();
    assertThat(f.membershipCount()).isZero();
    f.assertTransitionCount(0L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void bootstrapCharactersUsesActualAccountAuthorityAndPreservesControlledEntityNamesAndLevels(
      boolean paid) {
    var f = publicJoinFixture(paid);
    String token = f.browserScope();
    String join = UUID.randomUUID().toString();
    assertThat(f.join(token, join).success()).isTrue();
    UUID ada = UUID.randomUUID(), zed = UUID.randomUUID();
    org.mockito.Mockito.when(
            f.roster.read(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(f.account.getAccountUuid()),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isSynchronizationActive())
                  .isFalse();
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(zed, "Zed", 37),
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(ada, "Ada", 12));
            });
    var before = f.state(join);
    int providerReads = paid ? f.paid.transport().reads() : 0;
    var actors = f.characters(token);
    assertThat(actors).extracting(value -> value.characterName()).containsExactly("Ada", "Zed");
    assertThat(actors)
        .extracting(value -> value.characterId())
        .containsExactly(ada.toString(), zed.toString());
    assertThat(actors).extracting(value -> value.level()).containsExactly(12, 37);
    assertThat(actors)
        .allSatisfy(
            actor -> {
              assertThat(actor.stateScope()).isEqualTo("SHARED");
              assertThat(actor.characterCreationPolicy()).isEqualTo("PRESEEDED_ONLY");
            });
    var selected =
        org.mockito.ArgumentCaptor.forClass(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
    org.mockito.Mockito.verify(f.roster)
        .read(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq(f.account.getAccountUuid()),
            selected.capture());
    assertThat(selected.getValue().selector().canonicalAccountUuid())
        .isEqualTo(f.account.getAccountUuid());
    assertThat(selected.getValue().selector().canonicalTenantUuid()).isEqualTo(f.tenantId);
    assertThat(selected.getValue().selector().realmUuid()).isEqualTo(f.target.realmId());
    assertThat(selected.getValue().selector().playableStateNamespaceUuid())
        .isEqualTo(f.target.playableStateNamespaceId());
    assertThat(selected.getValue().selector().canonicalGameInstanceUuid())
        .isEqualTo(f.target.canonicalGameInstanceId());
    assertThat(selected.getValue().selector().canonicalVersionUuid())
        .isEqualTo(f.target.canonicalVersionId());
    assertThat(selected.getValue().selector().expectedCatalogRevision())
        .isEqualTo(f.target.catalogRevision());
    assertThat(selected.getValue().selector().expectedPointerVersion())
        .isEqualTo(f.target.pointerVersion());
    assertThat(selected.getValue().selector().expectedActiveWorldEpoch())
        .isEqualTo(f.target.activeWorldEpoch());
    if (paid) assertThat(f.paid.transport().reads()).isGreaterThanOrEqualTo(providerReads + 2);
    assertThat(f.state(join)).isEqualTo(before);
    f.assertTransitionCount(1L);
  }

  @Test
  void bootstrapCharactersValidEmptyResultStillRequiresSuccessfulEntityDiscovery() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    assertThat(f.join(token, UUID.randomUUID().toString()).success()).isTrue();
    assertThat(f.characters(token)).isEmpty();
    org.mockito.Mockito.verify(f.roster)
        .read(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq(f.account.getAccountUuid()),
            org.mockito.ArgumentMatchers.any());
    f.assertTransitionCount(1L);
  }

  @Test
  void
      bootstrapCharactersSameAccountAndDuplicateWorldRealmNamesRemainBoundToExactTenantAndTarget() {
    var first = publicJoinFixture(false);
    var second = new CanonicalPublicJoinFixture(false, first.account);
    String firstScope = first.browserScope(), secondScope = second.browserScope();
    assertThat(first.target.worldSlug()).isEqualTo(second.target.worldSlug());
    assertThat(first.target.realmSlug()).isEqualTo(second.target.realmSlug());
    assertThat(first.tenantId).isNotEqualTo(second.tenantId);
    assertThat(first.join(firstScope, UUID.randomUUID().toString()).success()).isTrue();
    assertThat(second.join(secondScope, UUID.randomUUID().toString()).success()).isTrue();
    UUID firstActor = UUID.randomUUID(), secondActor = UUID.randomUUID();
    org.mockito.Mockito.when(
            first.roster.read(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(first.account.getAccountUuid()),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                    .Actor(firstActor, "First tenant actor", 9)));
    org.mockito.Mockito.when(
            second.roster.read(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(second.account.getAccountUuid()),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                    .Actor(secondActor, "Second tenant actor", 21)));
    assertThat(first.characters(firstScope))
        .extracting(value -> value.characterId())
        .containsExactly(firstActor.toString());
    assertThat(second.characters(secondScope))
        .extracting(value -> value.characterId())
        .containsExactly(secondActor.toString());
    var selected =
        org.mockito.ArgumentCaptor.forClass(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
    org.mockito.Mockito.verify(first.roster)
        .read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(first.account.getAccountUuid()),
            selected.capture());
    assertThat(selected.getValue().selector().canonicalTenantUuid()).isEqualTo(first.tenantId);
    org.mockito.Mockito.verify(second.roster)
        .read(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(second.account.getAccountUuid()),
            selected.capture());
    assertThat(selected.getValue().selector().canonicalTenantUuid()).isEqualTo(second.tenantId);
    assertThat(selected.getValue().selector().realmUuid()).isEqualTo(second.target.realmId());
    assertThat(selected.getValue().selector().playableStateNamespaceUuid())
        .isEqualTo(second.target.playableStateNamespaceId());
    assertThat(selected.getValue().selector().canonicalGameInstanceUuid())
        .isEqualTo(second.target.canonicalGameInstanceId());
  }

  @Test
  void bootstrapCharactersAnotherAuthenticatedAccountCannotConsumeTheSelectedScope() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    assertThat(f.join(token, UUID.randomUUID().toString()).success()).isTrue();
    String suffix = UUID.randomUUID().toString();
    var other =
        accountService.createAccount(
            new CreateAccountRequest(
                "other-roster-" + suffix,
                "other-roster-" + suffix + "@example.test",
                "password123"));
    Account owner = f.accounts.findByAccountUuid(UUID.fromString(other.id())).orElseThrow();
    String bootstrap =
        f.jwt.generateToken(
            other.id(),
            Map.of(
                "aud",
                "player-bootstrap",
                "accountId",
                other.id(),
                "jti",
                UUID.randomUUID().toString()));
    org.mockito.Mockito.when(f.sessions.isAccountSessionActive(owner.getId(), bootstrap))
        .thenReturn(true);
    assertThatThrownBy(
            () ->
                f.service.listBootstrapCharacters(
                    bootstrap, f.target.worldSlug(), f.target.realmSlug(), token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    org.mockito.Mockito.verifyNoInteractions(f.roster);
    f.assertTransitionCount(1L);
  }

  @Test
  void bootstrapCharactersMissingAndControlledInactiveMembersRequireJoinWithoutEntityDisclosure() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("JOIN_REQUIRED");
    org.mockito.Mockito.verifyNoInteractions(f.roster);
    assertThat(f.join(token, UUID.randomUUID().toString()).success()).isTrue();
    f.seedControlledInactiveSnapshot();
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("JOIN_REQUIRED");
    org.mockito.Mockito.verifyNoInteractions(f.roster);
  }

  @Test
  void bootstrapCharactersClosedPolicyAndControlOnlyMembershipCannotBecomeAnEmptySuccess() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    f.policy(false, true);
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    org.mockito.Mockito.verifyNoInteractions(f.roster);
    f.policy(true, true);
    assertThat(f.join(token, UUID.randomUUID().toString()).success()).isTrue();
    f.seedControlledSnapshot("ACTIVE", false, List.of("moderator"));
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(f.currentRoles()).containsExactly("moderator");
    org.mockito.Mockito.verifyNoInteractions(f.roster);
  }

  @Test
  void bootstrapCharactersRechecksActualMembershipSnapshotAfterEntityDiscovery() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String join = UUID.randomUUID().toString();
    assertThat(f.join(token, join).success()).isTrue();
    org.mockito.Mockito.when(
            f.roster.read(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(f.account.getAccountUuid()),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              f.seedControlledInactiveSnapshot();
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Withheld", 19));
            });
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    assertThat(f.operation(join).status()).isEqualTo("COMMITTED");
    assertThat(f.operation(join).membershipVersion()).isEqualTo(2L);
    assertThat(
            dsl.fetchValue(
                "SELECT lifecycle_state FROM account_tenant_membership WHERE account_id = ? AND tenant_uuid = ?",
                f.account.getId(),
                f.tenantId))
        .isEqualTo("INACTIVE");
    assertThat(
            dsl.fetchValue(
                "SELECT membership_version FROM account_tenant_membership WHERE account_id = ? AND tenant_uuid = ?",
                f.account.getId(),
                f.tenantId))
        .isEqualTo(3L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                f.stream()))
        .isEqualTo(2L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE tenant_uuid = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                f.tenantId))
        .isEqualTo(1L);
  }

  @Test
  void bootstrapCharactersFinalEntitlementOutageDiscardsEntityResultAndPreservesAccountState() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String join = UUID.randomUUID().toString();
    assertThat(f.join(token, join).success()).isTrue();
    var before = f.state(join);
    org.mockito.Mockito.when(
            f.roster.read(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(f.account.getAccountUuid()),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              org.mockito.Mockito.doThrow(
                      new org.springframework.dao.DataAccessResourceFailureException(
                          "controlled late demo currentness outage"))
                  .when(f.demos)
                  .revalidate(org.mockito.ArgumentMatchers.any());
              return List.of(
                  new net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                      .Actor(UUID.randomUUID(), "Withheld", 31));
            });
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.state(join)).isEqualTo(before);
    f.assertTransitionCount(1L);
  }

  @Test
  void bootstrapCharactersEntityFailureAndExpiredScopeNeverReturnAnEmptySuccess() {
    var f = publicJoinFixture(false);
    String token = f.browserScope();
    String join = UUID.randomUUID().toString();
    assertThat(f.join(token, join).success()).isTrue();
    var before = f.state(join);
    org.mockito.Mockito.when(
            f.roster.read(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(f.account.getAccountUuid()),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("controlled Entity discovery failure"));
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    f.clock.offset = Duration.ofMinutes(3);
    assertThatThrownBy(() -> f.characters(token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(value -> ((AuthenticationException) value).getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(f.state(join)).isEqualTo(before);
    f.assertTransitionCount(1L);
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
    }
    return null;
  }

  private CanonicalPublicJoinFixture publicJoinFixture(boolean paid) {
    return new CanonicalPublicJoinFixture(paid);
  }

  /**
   * Actual Account SQL/JWT/intent/subscription/member/event/audit composition. GS
   * resolver/published-source/World ACTIVE responses and caller registry inputs are controlled (the
   * strict source decoder and remote freshness are not qualified); this does not qualify PKI,
   * issuer custody or runtime registration.
   */
  private final class CanonicalPublicJoinFixture {
    private final String namespace;
    private final AccountRepository accounts = new AccountRepository(dsl);
    private final AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    private final AccountAuthorityOutboxRepository outbox =
        new AccountAuthorityOutboxRepository(dsl);
    private final AccountAuthoritySourceEvidenceRepository evidence =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    private final AccountMembershipPairAuthorityRepository pairs =
        new AccountMembershipPairAuthorityRepository(dsl);
    private final AccountTenantMembershipRoleSnapshotRepository roles =
        new AccountTenantMembershipRoleSnapshotRepository(dsl);
    private final AccountAuditOutboxRepository audits = new AccountAuditOutboxRepository(dsl);
    private final FreshTenantIdentityAssociationRepository tenants;
    private final AccountTenantMembershipRepository members;
    private final AccountTenantAuthorityEventRepository sources;
    private final AccountDemoTenantEntitlementRepository demos;
    private final AccountConnectScopeRepository scopes;
    private final AccountJoinOperationRepository operations;
    private final AccountCanonicalFirstJoinTerminalCoordinator terminal;
    private final integration.net.firedevops.firemud.accountservice.repository
            .CanonicalHostingSubscriptionTestFixture.Fixture
        paid;
    private final AccountHostingSubscriptionLifecycleRepository hosting;
    private final StripeHostingSubscriptionObserver observer;
    private final FreshTenantCreationEvidence source;
    private final UUID tenantId;
    private final Account account;
    private final FixtureClock clock;
    private final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    private final CanonicalGameplayAdmissionDecisionReadClient targets =
        org.mockito.Mockito.mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    private final SessionService sessions = org.mockito.Mockito.mock(SessionService.class);
    private final net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
        roster =
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient
                    .class);
    private final JwtUtil jwt =
        new JwtUtil("controlled-public-facade-fixture-key-01234567890123456789", 300000L);
    private final String bootstrap;
    private final AccountServiceImpl service;
    private final Target target;

    private CanonicalPublicJoinFixture(boolean usePaid) {
      this(usePaid, null);
    }

    private CanonicalPublicJoinFixture(boolean usePaid, Account existingAccount) {
      namespace = usePaid ? "prod" : "test";
      transaction.setPropagationBehavior(
          org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      transaction.setIsolationLevel(
          org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
      tenants = new FreshTenantIdentityAssociationRepository(dsl, namespace);
      members = new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);
      var billing = new AccountTenantEntitlementOutboxRepository(dsl);
      sources =
          org.mockito.Mockito.spy(
              new AccountTenantAuthorityEventRepository(
                  dsl, outbox, billing, tenants, generations));
      demos =
          org.mockito.Mockito.spy(
              new AccountDemoTenantEntitlementRepository(
                  dsl, tenants, generations, billing, sources));
      scopes =
          new AccountConnectScopeRepository(
              dsl,
              new AccountTenantIdentityResolver(
                  org.mockito.Mockito.mock(ApprovedLegacyTenantAssociationRepository.class),
                  namespace),
              tenants);
      operations = org.mockito.Mockito.spy(new AccountJoinOperationRepository(dsl, scopes));
      var producer =
          new AccountMembershipAuthorityEventProducer(
              operations,
              accounts,
              pairs,
              members,
              roles,
              generations,
              outbox,
              evidence,
              sources,
              demos);
      terminal =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts, operations, members, roles, outbox, pairs, audits, producer);
      if (usePaid) {
        paid =
            integration.net.firedevops.firemud.accountservice.repository
                .CanonicalHostingSubscriptionTestFixture.create(dataSource);
        paid.completeFirstInvoice();
        hosting = paid.lifecycleRepository();
        observer = paid.observer();
        tenantId = paid.tenantId();
        source = in(() -> tenants.read(tenantId).orElseThrow());
        clock = new FixtureClock(paid.clock());
      } else {
        paid = null;
        hosting = org.mockito.Mockito.mock(AccountHostingSubscriptionLifecycleRepository.class);
        observer = org.mockito.Mockito.mock(StripeHostingSubscriptionObserver.class);
        tenantId = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        String key = "f-" + tenantId.toString().replace("-", "");
        String digest =
            GameTenantCreationDigest.requestDigest(namespace, request, key, "Facade fixture", null);
        source =
            new FreshTenantCreationEvidence(
                1,
                namespace,
                request,
                operation,
                digest,
                tenantId,
                100000000L + Integer.toUnsignedLong(key.hashCode()),
                key,
                "NEW_GAME_ROW",
                GameTenantCreationDigest.evidenceDigest(
                    namespace,
                    request,
                    operation,
                    digest,
                    tenantId,
                    100000000L + Integer.toUnsignedLong(key.hashCode()),
                    key,
                    "NEW_GAME_ROW"));
        clock = new FixtureClock(Clock.systemUTC());
        in(
            () -> {
              tenants.importVerified(source);
              generations.initializeTenantIfAbsent(tenantId);
              return null;
            });
        policy(true, true);
      }
      String suffix = UUID.randomUUID().toString();
      if (existingAccount == null) {
        var created =
            accountService.createAccount(
                new CreateAccountRequest(
                    "facade-" + suffix, "facade-" + suffix + "@example.test", "password123"));
        account = accounts.findByAccountUuid(UUID.fromString(created.id())).orElseThrow();
      } else {
        account = accounts.findByAccountUuid(existingAccount.getAccountUuid()).orElseThrow();
      }
      in(
          () -> {
            evidence.initializeIssuerIfAbsent("firemud-account-service");
            generations.initialize(
                AccountAuthorityGenerationRepository.AuthorityScope.membership(
                    account.getAccountUuid(), tenantId));
            return null;
          });
      if (paid != null) {
        // The SQL lifecycle owner evaluates at microsecond precision; its SDK input must use the
        // same controlled instant rather than appear nanoseconds later than the evaluation.
        Instant browserTime =
            Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (paid.clock().instant().isBefore(browserTime)) {
          paid.clock().advance(Duration.between(paid.clock().instant(), browserTime));
        }
      }
      target = target();
      org.mockito.Mockito.when(
              targets.listVisiblePublicProductionAdmissionTargets(
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var request =
                    new CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest(
                        invocation.getArgument(0, UUID.class), namespace);
                return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
                    request,
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                        request, List.of(target)));
              });
      org.mockito.Mockito.when(
              targets.resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var selector =
                    invocation.getArgument(
                        0,
                        CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector
                            .class);
                return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromTargetResolutionResponse(
                    selector,
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
                        selector, target));
              });
      // The transport boundary is controlled. Actual Account owners must still request this strict
      // published-source/World ACTIVE result; the resolver response alone cannot authorize JOIN.
      org.mockito.Mockito.when(
              targets.readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var strict =
                    invocation.getArgument(
                        0,
                        net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                            .Request.class);
                var published =
                    org.mockito.Mockito.mock(
                        CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence
                            .class);
                org.mockito.Mockito.when(published.selector()).thenReturn(strict);
                org.mockito.Mockito.when(published.currentTarget()).thenReturn(target);
                var sourceEvidence =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                            .class);
                org.mockito.Mockito.when(sourceEvidence.request()).thenReturn(strict);
                org.mockito.Mockito.when(sourceEvidence.admissionPointerSnapshotDigest())
                    .thenReturn(target.admissionPointerSnapshotDigest());
                // Controlled remote policy capture; the Account integration test does not qualify
                // its decoder.
                var policies =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.publication
                            .PublishedRealmEntryPolicySetEvidence.class);
                org.mockito.Mockito.when(policies.canonicalBytes())
                    .thenReturn(
                        ("controlled-policy-set-" + tenantId).getBytes(StandardCharsets.UTF_8));
                org.mockito.Mockito.when(sourceEvidence.publishedPolicySetEvidence())
                    .thenReturn(policies);
                var world =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
                            .class);
                org.mockito.Mockito.when(world.lifecycleStatus()).thenReturn("ACTIVE");
                org.mockito.Mockito.when(world.lifecycleEpoch())
                    .thenReturn(target.activeWorldEpoch());
                org.mockito.Mockito.when(world.rowVersion()).thenReturn(1L);
                org.mockito.Mockito.when(published.sourceEvidence()).thenReturn(sourceEvidence);
                org.mockito.Mockito.when(published.worldLifecycleEvidence()).thenReturn(world);
                return published;
              });
      var properties = new AccountTokenProperties();
      properties.setConnectScopeExpirationMs(120000L);
      bootstrap =
          jwt.generateToken(
              account.getAccountUuid().toString(),
              Map.of(
                  "aud",
                  "player-bootstrap",
                  "accountId",
                  account.getAccountUuid().toString(),
                  "jti",
                  UUID.randomUUID().toString()));
      org.mockito.Mockito.when(sessions.isAccountSessionActive(account.getId(), bootstrap))
          .thenReturn(true);
      var caller = new AccountPlayerBootstrapCallerVerifier(jwt, accounts, sessions);
      var entitlement =
          new AccountPublicBootstrapEntitlementOwner(
              sources,
              new AccountHostingSubscriptionLifecycleService(hosting, observer, transactionManager),
              demos,
              transactionManager,
              clock);
      service =
          new AccountServiceImpl(
              accounts,
              generations,
              evidence,
              outbox,
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountPasswordResetOperationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountLogoutAllOperationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountSecurityStateOperationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.service
                      .AccountPasswordResetDraftSourceChangeRepository.class),
              audits,
              operations,
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountEmailLoginChallengeRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository
                      .class),
              members,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              new net.firedevops.firemud.accountservice.config.MailProperties(),
              properties,
              new net.firedevops.firemud.common.security.JwtAuthProperties(),
              jwt,
              sessions,
              transactionManager);
      service.setPublicBootstrapWorldsOwner(
          new AccountPublicBootstrapWorldsOwner(caller, targets, entitlement, clock));
      service.setCanonicalPublicRealmScopeOwner(
          new AccountCanonicalPublicRealmScopeOwner(
              caller,
              targets,
              entitlement,
              accounts,
              members,
              tenants,
              sources,
              scopes,
              jwt,
              properties,
              clock,
              transactionManager,
              namespace));
      service.setAccountBootstrapRosterReadClient(roster);
      org.mockito.Mockito.when(
              roster.read(
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.eq(account.getAccountUuid()),
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isSynchronizationActive())
                    .isFalse();
                return List.of();
              });
      service.setCanonicalScopeConsumers(
          new AccountCanonicalConnectScopeReader(jwt, scopes, transactionManager, clock),
          targets,
          sources,
          demos,
          hosting,
          observer,
          terminal,
          namespace,
          clock);
    }

    private void policy(boolean join, boolean gameplay) {
      in(
          () -> {
            DemoTenantEntitlementSnapshot previous;
            try {
              previous = demos.readCurrent(tenantId);
            } catch (
                net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException
                    missing) {
              if (missing.code()
                  != net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException
                      .Code.ENTITLEMENT_UNAVAILABLE) throw missing;
              previous = null;
            }
            return demos.provision(
                new DemoTenantEntitlementRequest(
                    UUID.randomUUID(),
                    tenantId,
                    source.creationRequestId(),
                    source.requestDigest(),
                    previous == null ? null : previous.entitlementVersion(),
                    previous == null ? null : previous.tenantAuthorityGeneration(),
                    previous == null ? null : previous.tenantAuthoritySourceVersion(),
                    gameplay,
                    join,
                    gameplay,
                    gameplay,
                    new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L)),
                source);
          });
    }

    private Target target() {
      UUID realm = UUID.randomUUID(),
          state = UUID.randomUUID(),
          instance = UUID.randomUUID(),
          version = UUID.randomUUID();
      String digest = "a".repeat(64), initial = UUID.randomUUID().toString();
      var request =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
              namespace,
              tenantId,
              "world",
              realm,
              state,
              "SHARED",
              instance,
              version,
              1L,
              initial,
              digest,
              net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                  .InitialAdmissionOrigin.NO_PRIOR_POINTER,
              23L,
              null);
      var hold =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
              request, UUID.randomUUID(), UUID.randomUUID());
      return new Target(
          namespace,
          "tenant",
          tenantId,
          "world",
          "World",
          realm,
          "production",
          "Production",
          11L,
          44L,
          state,
          "SHARED",
          instance,
          version,
          1L,
          23L,
          17L,
          digest,
          1L,
          initial,
          digest,
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind.NO_PRIOR_POINTER,
          null,
          hold.holdId(),
          hold.holdFence(),
          hold.holdBindingDigest(),
          1L,
          "sha256:" + digest,
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED,
          false,
          clock.instant(),
          "PRESEEDED_ONLY");
    }

    private String browserScope() {
      return service
          .listBootstrapRealms(bootstrap, target.tenantSlug(), target.worldSlug())
          .getFirst()
          .connectScopeId();
    }

    private List<net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto> characters(
        String token) {
      return service.listBootstrapCharacters(
          bootstrap, target.worldSlug(), target.realmSlug(), token);
    }

    private DirectTextCallerContext directCaller(String request) {
      return new DirectTextCallerContext(
          account.getAccountUuid(),
          tenantId,
          target.realmId(),
          target.playableStateNamespaceId(),
          target.playableStateScope(),
          target.canonicalGameInstanceId(),
          "00042",
          request);
    }

    private String directScope() throws Exception {
      String request = UUID.randomUUID().toString();
      return peer()
          .call(
              () ->
                  service
                      .issueDirectTextConnectScope(
                          directCaller(request),
                          new DirectTextJoinTarget(
                              tenantId,
                              target.realmId(),
                              target.worldSlug(),
                              target.realmSlug(),
                              target.playableStateNamespaceId(),
                              target.playableStateScope(),
                              target.canonicalGameInstanceId(),
                              target.catalogRevision(),
                              target.pointerVersion()))
                      .connectScopeId());
    }

    private JoinPublicProductionResult directJoin(String token, String request) throws Exception {
      return peer()
          .call(
              () ->
                  service.joinPublicProductionFromGameSession(
                      directCaller(request), new JoinPublicProductionRequest(token, request)));
    }

    private io.grpc.Context peer() {
      return io.grpc.Context.ROOT.withValue(
          net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
          new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
              "spiffe://firemud/ns/" + namespace + "/sa/game-session-service",
              namespace,
              "game-session-service"));
    }

    private JoinPublicProductionResult join(String token, String request) {
      return service.joinPublicProduction(
          bootstrap, new JoinPublicProductionRequest(token, request));
    }

    private AccountJoinOperationRepository.CanonicalJoinOperationEvidence operation(
        String request) {
      return in(() -> operations.findCanonicalEvidenceForUpdateByRequestId(request).orElseThrow());
    }

    /**
     * Controlled prior lifecycle event; this does not claim a production leave/suspension owner.
     */
    private void seedControlledInactiveSnapshot() {
      seedControlledSnapshot("INACTIVE", false, List.of("player"));
    }

    @SuppressWarnings("unchecked")
    private void seedControlledSnapshot(String lifecycle, boolean gameplay, List<String> roleSet) {
      TransactionTemplate history = new TransactionTemplate(transactionManager);
      history.setPropagationBehavior(
          org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      history.setIsolationLevel(
          org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
      history.execute(
          status -> {
            operations.lockAccount(account.getId());
            var member =
                members
                    .findFreshMembershipForUpdate(account.getAccountUuid(), tenantId)
                    .orElseThrow();
            var generation =
                generations.read(
                    AccountAuthorityGenerationRepository.AuthorityScope.membership(
                        account.getAccountUuid(), tenantId));
            var beforeSource =
                evidence.readCurrentIssuerAccountSources(
                    "firemud-account-service", account.getAccountUuid());

            // Closed receipt/source-change mechanics supply the one shared Account fence advance.
            // This remains controlled historical input, not a registered security or leave
            // producer.
            var currentSource = advanceControlledClosedAccountSource(beforeSource);
            if (currentSource.account().generation() != beforeSource.account().generation() + 1L
                || currentSource.account().sourceVersion()
                    != beforeSource.account().sourceVersion() + 1L
                || currentSource.account().checkpoint().sequence()
                    != beforeSource.account().checkpoint().sequence() + 1L
                || currentSource.issuanceFence().value() != generation.issuanceFence().value() + 1L
                || currentSource.issuanceFence().sourceVersion()
                    != generation.issuanceFence().sourceVersion() + 1L) {
              throw new IllegalStateException(
                  "Controlled Account source/fence did not advance once");
            }
            int advanced =
                dsl.execute(
                    "UPDATE account_authority_generations SET generation = generation + 1, "
                        + "source_version = source_version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE scope_kind = 'MEMBERSHIP' AND issuer_id IS NULL "
                        + "AND account_uuid = ? AND tenant_uuid = ? AND generation = ? AND source_version = ?",
                    account.getAccountUuid(),
                    tenantId,
                    generation.generation(),
                    generation.sourceVersion());
            if (advanced != 1) {
              throw new IllegalStateException(
                  "Controlled membership authority CAS did not advance once");
            }
            var authority =
                generations.readCompositeSnapshot(
                    "firemud-account-service",
                    account.getAccountUuid(),
                    List.of(tenantId),
                    List.of(tenantId));
            var next = authority.memberships().getFirst();
            if (next.generation() != generation.generation() + 1L
                || next.sourceVersion() != generation.sourceVersion() + 1L
                || !next.issuanceFence().equals(currentSource.issuanceFence())
                || !authority.issuanceFence().equals(currentSource.issuanceFence())) {
              throw new IllegalStateException(
                  "Controlled membership differs from the actual Account fence");
            }
            var tenantSource = sources.readCurrentSourceSelection(tenantId).checkpoint();
            var prior = pairs.readForUpdate(account.getAccountUuid(), tenantId).orElseThrow();
            var previous = outbox.findEvent(stream(), prior.eventSequence()).orElseThrow();
            Map<String, Object> event =
                JsonMapper.builder()
                    .build()
                    .readValue(new String(previous.payload(), StandardCharsets.UTF_8), Map.class);
            event.remove("eventDigest");
            String leave = "controlled-inactive-" + UUID.randomUUID();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("requestId", leave);
            event.put("outboxSequence", "2");
            event.put("membershipLifecycleState", lifecycle);
            event.put("roles", roleSet);
            event.put("membershipVersion", Map.of(tenantId.toString(), "3"));
            event.put("membershipAuthorityGeneration", Long.toString(next.generation()));
            Map<String, Object> tuple = new java.util.LinkedHashMap<>();
            tuple.put("issuerAuthGeneration", Long.toString(authority.issuer().generation()));
            tuple.put(
                "accountAuthorityGeneration", Long.toString(authority.account().generation()));
            tuple.put(
                "tenantAuthorityGeneration",
                Map.of(
                    tenantId.toString(),
                    Long.toString(authority.tenants().getFirst().generation())));
            tuple.put(
                "membershipAuthorityGeneration",
                Map.of(tenantId.toString(), Long.toString(next.generation())));
            tuple.put("privateRealmGrantVersions", List.of());
            currentSource
                .account()
                .accountSecurityCutoff()
                .ifPresent(
                    cutoff ->
                        tuple.put(
                            "accountSecurityCutoff",
                            Map.of(
                                "accountAuthorityGeneration", cutoff.accountAuthorityGeneration(),
                                "outboxStreamKey", cutoff.outboxStreamKey(),
                                "outboxSequence", cutoff.outboxSequence())));
            tuple.put(
                "tenantBillingCutoff",
                Map.of(
                    tenantId.toString(),
                    Map.of(
                        "tenantAuthorityGeneration",
                            Long.toString(tenantSource.tenantAuthorityGeneration()),
                        "tenantBillingSequence",
                            Long.toString(tenantSource.tenantBillingSequence()),
                        "outboxStreamKey", tenantSource.outboxStreamKey(),
                        "outboxSequence", Long.toString(tenantSource.outboxSequence()))));
            event.put("authorityTuple", tuple);
            event.put("issuanceFence", Long.toString(authority.issuanceFence().value()));
            event.put("gameplayAdmissionAllowed", gameplay);
            event.put("callerBoundAuthorityInvalidated", true);
            var sealed = MembershipAuthorityEventV1Codec.seal(event);
            var appended =
                outbox.append(
                    stream(),
                    leave,
                    sequence -> {
                      if (sequence != 2L)
                        throw new IllegalStateException("Controlled prior event sequence changed");
                      return new AccountAuthorityOutboxRepository.EventEvidence(
                          sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
                    });
            dsl.execute(
                "UPDATE account_tenant_membership SET lifecycle_state = ?, gameplay_admission_allowed = ?, membership_version = 3, membership_authority_generation = 2 WHERE id = ?",
                lifecycle,
                gameplay,
                member.getId());
            var inactive =
                members
                    .findCanonicalMembershipForUpdate(account.getAccountUuid(), tenantId)
                    .orElseThrow();
            var provenance =
                new AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
                    null,
                    AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN,
                    source.operationId(),
                    source.evidenceDigest());
            roles.replaceCanonical(
                inactive, account.getAccountUuid(), tenantId, provenance, 3L, roleSet);
            dsl.execute(
                "UPDATE account_membership_pair_authority SET membership_version = 3, membership_authority_generation = 2, last_event_sequence = 2, last_event_id = ?, last_event_digest = ?, last_transition_invalidated = TRUE WHERE account_uuid = ? AND tenant_uuid = ?",
                appended.eventId(),
                appended.eventDigest(),
                account.getAccountUuid(),
                tenantId);
            return null;
          });
    }

    /**
     * Completes one controlled closed security-state receipt in the caller's history transaction.
     */
    private AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot
        advanceControlledClosedAccountSource(
            AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot beforeSource) {
      Account current = accounts.findByAccountUuidForUpdate(account.getAccountUuid()).orElseThrow();
      var sourceState =
          generations.read(
              AccountAuthorityGenerationRepository.AuthorityScope.account(
                  account.getAccountUuid()));
      var roleSource =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT global_roles, global_role_source_version FROM account_global_role_sources "
                      + "WHERE account_uuid = ? FOR SHARE",
                  account.getAccountUuid()));
      long roleVersion = roleSource.get("global_role_source_version", Long.class);
      var beforeState =
          new AccountState(
              current.isEmailVerified(),
              AccountLoginAuthModes.read(current.getLoginAuthModes()).stream()
                  .map(Enum::name)
                  .sorted()
                  .toList(),
              java.util.Arrays.stream(roleSource.get("global_roles", String[].class))
                  .sorted()
                  .toList(),
              current.getLifecycleState().name());
      var afterState =
          new AccountState(
              !beforeState.emailVerified(),
              beforeState.loginAuthModes(),
              beforeState.globalRoles(),
              beforeState.lifecycleState());
      UUID requestId = UUID.randomUUID();
      // These bytes are retained fixture correlation, not authenticated caller authorization.
      byte[] correlation =
          ("{\"actorAccountUuid\":\""
                  + account.getAccountUuid()
                  + "\",\"ownerEvidenceDigest\":\"sha256:"
                  + "a".repeat(64)
                  + "\",\"ownerOperationId\":\""
                  + requestId
                  + "\",\"schemaVersion\":\"account-security-state-caller-correlation/v1\"}")
              .getBytes(StandardCharsets.UTF_8);
      var request =
          new AccountSecurityStateMutationRequest(
              requestId,
              account.getAccountUuid(),
              correlation,
              sourceState.generation(),
              sourceState.sourceVersion(),
              AccountSecurityStateOperationRepository.detectedKinds(beforeState, afterState),
              afterState);
      var checkpoint = beforeSource.account().checkpoint();
      byte[] checkpointPayload =
          checkpoint.sequence() == 0L
              ? new byte[0]
              : outbox
                  .findEvent(checkpoint.outboxStreamKey(), checkpoint.sequence())
                  .orElseThrow()
                  .payload();
      var draft =
          new Capture(
              current.getId(),
              current.getAccountUuidProvenance(),
              beforeState,
              sourceState,
              checkpoint.sequence(),
              checkpointPayload,
              roleVersion,
              null);
      byte[] captureBytes = AccountSecurityStateOperationRepository.captureBytes(request, draft);
      var source =
          new SourceEvidence(
              SourceKind.ACCOUNT,
              account.getAccountUuid().toString(),
              Long.toString(sourceState.generation()),
              Long.toString(sourceState.sourceVersion()),
              checkpoint.outboxStreamKey(),
              Long.toString(checkpoint.sequence()),
              captureBytes);
      var change = new SourceChange(UUID.randomUUID(), List.of(source), captureBytes);
      var capture =
          new Capture(
              draft.accountId(),
              draft.provenance(),
              beforeState,
              sourceState,
              checkpoint.sequence(),
              checkpointPayload,
              roleVersion,
              change);
      var fences = new DraftAuthorizationFenceRepository(dsl);
      var security = new AccountSecurityStateOperationRepository(dsl);
      if (!fences.requestSourceChange(change)
          || !security.claim(request, capture).claimed()
          || !fences.sourceMutationPermitted(change)) {
        throw new IllegalStateException(
            "Controlled closed source change is not exclusively settled");
      }
      var advanced =
          evidence.prepareClosedAccountAdvance(account.getAccountUuid(), beforeSource.account());
      int changed =
          dsl.execute(
              "UPDATE accounts SET email_verified = ? WHERE account_uuid = ? AND email_verified = ?",
              afterState.emailVerified(),
              account.getAccountUuid(),
              beforeState.emailVerified());
      if (changed != 1) {
        throw new IllegalStateException("Controlled closed Account state CAS did not change once");
      }
      var event =
          outbox.append(
              checkpoint.outboxStreamKey(),
              requestId.toString(),
              sequence -> {
                var sealed =
                    AccountSecurityStateAuthorityEventV1Codec.seal(
                        AccountSecurityStateMutationRequest.eventPreimage(
                            requestId,
                            account.getAccountUuid(),
                            Long.toString(advanced.generation()),
                            Long.toString(advanced.sourceVersion()),
                            Long.toString(sequence),
                            request.mutationKinds(),
                            afterState));
                return new AccountAuthorityOutboxRepository.EventEvidence(
                    sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
              });
      fences.markSourceCommitted(change);
      var completed = security.complete(request, event, advanced, roleVersion);
      assertThat(completed.receipt().orElseThrow().event()).isEqualTo(event);
      assertThat(completed.receipt().orElseThrow().sourceState()).isEqualTo(advanced);
      assertThat(completed.capture().sourceChange().canonicalBytes())
          .isEqualTo(change.canonicalBytes());
      assertThat(fences.readSourceChange(change).status()).isEqualTo("SOURCE_COMMITTED");
      return evidence.readCurrentIssuerAccountSources(
          "firemud-account-service", account.getAccountUuid());
    }

    private List<String> currentRoles() {
      return in(
          () -> {
            operations.lockAccount(account.getId());
            var member =
                members
                    .findFreshMembershipForUpdate(account.getAccountUuid(), tenantId)
                    .orElseThrow();
            var provenance =
                new AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
                    null,
                    AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN,
                    source.operationId(),
                    source.evidenceDigest());
            return roles
                .findForCanonicalUpdate(
                    account.getAccountUuid(),
                    tenantId,
                    provenance,
                    member.getId(),
                    member.getMembershipVersion())
                .orElseThrow()
                .roles();
          });
    }

    private long membershipCount() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_uuid = ?",
                  account.getId(),
                  tenantId))
          .get(0, Long.class);
    }

    private String stream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + account.getAccountUuid()
          + "/"
          + tenantId;
    }

    private void assertTransitionCount(long expected) {
      assertThat(
              dsl.fetchValue(
                  "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                  stream()))
          .isEqualTo(expected);
      assertThat(
              dsl.fetchValue(
                  "SELECT COUNT(*) FROM account_audit_outbox WHERE tenant_uuid = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                  tenantId))
          .isEqualTo(expected);
    }

    private List<String> state(String request) {
      return List.of(
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT row_to_json(j)::text FROM account_join_operations j WHERE request_id = ?",
                      request))
              .get(0, String.class),
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COALESCE(json_agg(m)::text, '[]') FROM account_tenant_membership m WHERE account_id = ? AND tenant_uuid = ?",
                      account.getId(),
                      tenantId))
              .get(0, String.class),
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COALESCE(json_agg(e ORDER BY outbox_sequence)::text, '[]') FROM account_authority_outbox_events e WHERE outbox_stream_key = ?",
                      stream()))
              .get(0, String.class),
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COALESCE(json_agg(to_jsonb(a) - ARRAY['attempt_count','last_attempt_at','next_attempt_at'])::text, '[]') FROM account_audit_outbox a WHERE tenant_uuid = ?",
                      tenantId))
              .get(0, String.class));
    }

    private <T> T in(Supplier<T> work) {
      return transaction.execute(status -> work.get());
    }
  }

  private static final class FixtureClock extends Clock {
    private final Clock delegate;
    private volatile Duration offset = Duration.ZERO;

    private FixtureClock(Clock delegate) {
      this.delegate = delegate;
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return delegate.instant().plus(offset);
    }
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
  @org.springframework.test.annotation.DirtiesContext
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
    // Controlled complete empty source; caller identity and Redis session reads remain real.
    var emptyTargets = org.mockito.Mockito.mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    org.mockito.Mockito.when(
            emptyTargets.listVisiblePublicProductionAdmissionTargets(
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              var request =
                  new CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest(
                      invocation.getArgument(0, UUID.class), "test");
              return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
                  request,
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                      request, List.of()));
            });
    var unusedEntitlements = org.mockito.Mockito.mock(AccountPublicBootstrapEntitlementOwner.class);
    canonicalFacade.setPublicBootstrapWorldsOwner(
        new AccountPublicBootstrapWorldsOwner(
            new AccountPlayerBootstrapCallerVerifier(
                jwtUtil, accountRepository, realAccountSessions),
            emptyTargets,
            unusedEntitlements,
            Clock.systemUTC()));
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
  void retainedProfileUuidProjectionAndControlAuthorizationPreservePersistedIdentity()
      throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "profile-" + suffix;
    var account =
        accountService.createAccount(
            new CreateAccountRequest(username, username + "@example.com", "password123"));
    long row =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT id FROM accounts WHERE account_uuid = ?",
                    UUID.fromString(account.id())))
            .get(0, Long.class);
    long tenant = 500000000L + Integer.toUnsignedLong(suffix.hashCode());
    // Retained profile access checks relationship existence; this quarantined row grants no
    // gameplay.
    dsl.execute(
        "INSERT INTO account_tenant_membership (account_id, tenant_id, lifecycle_state, "
            + "gameplay_admission_allowed, membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, 'LEGACY_UNVERIFIED', FALSE, 1, 1, 'LEGACY_UNVERIFIED')",
        row,
        tenant);
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
    String accountUuid = account.id();
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
        .contains("\"message\":\"accountId must be a canonical non-nil Account UUID\"");
  }

  @Test
  void accountSelectorRoutesUsePersistedUuidAndPreserveCurrentAuthorizationAndDenials()
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

    String ordinary = accountService.authenticate(username, "password123").authToken();
    String accountUuid = account.id();

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
