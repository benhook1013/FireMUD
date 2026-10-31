package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
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
  @Autowired private JwtUtil jwtUtil;
  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private HttpAuthProperties httpAuthProperties;
  @Autowired private ObjectProvider<HttpJwtAuthInterceptor> httpJwtAuthInterceptorProvider;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private net.firedevops.firemud.accountservice.repository.AccountRepository accountRepository;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository
      emailVerificationTokenRepository;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository
      joinOperationRepository;

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
  void publicJoinLostCommitAcknowledgementAndReadbackReplayTheSameOriginalOnce() {
    var fixture = publicJoinFixture();
    var readbackDropped = losePublicJoinTerminalResponse(fixture);

    assertPublicJoinUnavailable(fixture);

    assertThat(readbackDropped).isTrue();
    assertPublicJoinCommittedOnce(fixture);
    var committed = publicJoinState(fixture);
    var replay = publicJoin(fixture);

    assertThat(replay.success()).isTrue();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.outcomeCode()).isEqualTo("JOINED");
    assertThat(replay.accountId()).isEqualTo(fixture.accountUuid());
    assertPublicJoinCommittedOnce(fixture);
    assertThat(publicJoinState(fixture)).isEqualTo(committed);
  }

  @Test
  void publicJoinFailureBeforeCommitRollsBackEffectsAndContinuesTheOriginalIntent() {
    var fixture = publicJoinFixture();
    var failOnce = new AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              invocation.callRealMethod();
              if (failOnce.compareAndSet(true, false)) {
                // The real terminal UPDATE ran, but its owner transaction has not committed.
                throw new IllegalStateException("Injected failure before public JOIN commit");
              }
              return null;
            })
        .when(joinOperationRepository)
        .finish(
            org.mockito.ArgumentMatchers.eq(fixture.caller().requestId()),
            org.mockito.ArgumentMatchers.eq("COMMITTED"),
            org.mockito.ArgumentMatchers.eq("JOINED"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());

    assertPublicJoinUnavailable(fixture);

    assertThat(failOnce).isFalse();
    var pending =
        dsl.fetchOne(
            "SELECT status, outcome, intent_digest, request_digest, membership_id "
                + "FROM account_join_operations WHERE request_id = ?",
            fixture.caller().requestId());
    assertThat(pending).isNotNull();
    assertThat(pending.get("status", String.class)).isEqualTo("PENDING");
    assertThat(pending.get("intent_digest", String.class)).startsWith("sha256:");
    assertThat(pending.get("outcome")).isNull();
    assertThat(pending.get("request_digest")).isNull();
    assertThat(pending.get("membership_id")).isNull();
    assertThat(publicJoinMembershipCount(fixture)).isZero();
    assertThat(publicJoinAuditCount(fixture)).isZero();

    var continued = publicJoin(fixture);

    assertThat(continued.success()).isTrue();
    assertThat(continued.replayed()).isFalse();
    assertThat(continued.outcomeCode()).isEqualTo("JOINED");
    assertPublicJoinCommittedOnce(fixture);
    assertThat(publicJoin(fixture).replayed()).isTrue();
    assertPublicJoinCommittedOnce(fixture);
  }

  @Test
  void publicJoinLostResponseThenExpiredScopePreservesTheCompleteCommittedOriginal() {
    var fixture = publicJoinFixture();
    var readbackDropped = losePublicJoinTerminalResponse(fixture);
    assertPublicJoinUnavailable(fixture);
    assertThat(readbackDropped).isTrue();
    assertPublicJoinCommittedOnce(fixture);
    var committed = publicJoinState(fixture);
    String retainedExpiry =
        dsl.resultQuery(
                "SELECT s.connect_scope_expires_at FROM account_connect_scope_records s "
                    + "JOIN account_join_operations j ON j.scope_token_hash = s.scope_token_hash "
                    + "WHERE j.request_id = ?",
                fixture.caller().requestId())
            .fetchOne(0, String.class);
    assertThat(retainedExpiry).isNotBlank();
    Instant expiredNow = Instant.parse(retainedExpiry).plusSeconds(1);

    // Advance only the owner clock; never rewrite the original scope, intent or terminal row.
    try (org.mockito.MockedStatic<Instant> clock =
        org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      clock.when(Instant::now).thenReturn(expiredNow);
      var refused = publicJoin(fixture);
      assertThat(refused.success()).isFalse();
      assertThat(refused.replayed()).isFalse();
      assertThat(refused.outcomeCode()).isEqualTo("CONNECT_SCOPE_INVALID");
    }

    assertThat(publicJoinState(fixture)).isEqualTo(committed);
    assertPublicJoinCommittedOnce(fixture);
  }

  private AtomicBoolean losePublicJoinTerminalResponse(PublicJoinFixture fixture) {
    var committed = new AtomicBoolean();
    var readbackDropped = new AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              invocation.callRealMethod();
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .registerSynchronization(
                      new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          committed.set(true);
                          throw new IllegalStateException("Injected lost public JOIN commit ACK");
                        }
                      });
              return null;
            })
        .when(joinOperationRepository)
        .finish(
            org.mockito.ArgumentMatchers.eq(fixture.caller().requestId()),
            org.mockito.ArgumentMatchers.eq("COMMITTED"),
            org.mockito.ArgumentMatchers.eq("JOINED"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (committed.get() && readbackDropped.compareAndSet(false, true)) {
                throw new org.springframework.dao.DataAccessResourceFailureException(
                    "Injected unavailable first post-commit JOIN readback");
              }
              return invocation.callRealMethod();
            })
        .when(joinOperationRepository)
        .find(fixture.caller().requestId());
    return readbackDropped;
  }

  private void assertPublicJoinUnavailable(PublicJoinFixture fixture) {
    var unavailable =
        org.junit.jupiter.api.Assertions.assertThrows(
            AuthenticationException.class, () -> publicJoin(fixture));
    assertThat(unavailable.getCode()).isEqualTo("AUTH_UNAVAILABLE");
  }

  private JoinPublicProductionResult publicJoin(PublicJoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.connectScopeId(), fixture.caller().requestId()));
  }

  private PublicJoinFixture publicJoinFixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String world = "join-" + suffix;
    var account =
        accountService.createAccount(
            new CreateAccountRequest(world, world + "@example.com", "password123"));
    long accountId =
        java.util.Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id FROM accounts WHERE account_uuid = ?", UUID.fromString(account.id()))
                .fetchOne(0, Long.class));
    long tenantId = 6000000000L + accountId;
    dsl.execute(
        "INSERT INTO subscription(account_id,plan_id,status,tenant_id,entitlement_version) "
            + "VALUES(?,'join-proof','active',?,1)",
        accountId,
        tenantId);
    UUID realmId = UUID.randomUUID();
    String namespace = UUID.randomUUID().toString();
    var realm =
        net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setRealmId(realmId.toString())
            .setWorldSlug(world)
            .setRealmSlug("production")
            .setPlayableStateNamespaceId(namespace)
            .setStateScope("SHARED")
            .setGameInstanceId("44")
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .build();
    var pointer =
        net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setRealmId(realmId.toString())
            .setWorldSlug(world)
            .setRealmSlug("production")
            .setPlayableStateNamespaceId(namespace)
            .setStateScope("SHARED")
            .setGameInstanceId("44")
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .build();
    org.mockito.Mockito.when(gameSessionClient.listGameplayRealms(world))
        .thenReturn(List.of(realm));
    org.mockito.Mockito.when(gameSessionClient.getAdmissionPointer(tenantId, world, "production"))
        .thenReturn(pointer);
    var caller =
        new DirectTextCallerContext(
            accountId,
            tenantId,
            realmId,
            namespace,
            "SHARED",
            44L,
            "controlled-join-session-" + suffix,
            "original-join-" + suffix);
    var scope =
        accountService.issueDirectTextConnectScope(
            caller,
            new DirectTextJoinTarget(
                tenantId, realmId, world, "production", namespace, "SHARED", 44L, 23L, 17L));
    return new PublicJoinFixture(accountId, account.id(), tenantId, caller, scope.connectScopeId());
  }

  private long publicJoinMembershipCount(PublicJoinFixture fixture) {
    return java.util.Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private UUID publicJoinAuditId(PublicJoinFixture fixture) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + fixture.caller().requestId()).getBytes(StandardCharsets.UTF_8));
  }

  private long publicJoinAuditCount(PublicJoinFixture fixture) {
    return java.util.Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ?",
                publicJoinAuditId(fixture))
            .fetchOne(0, Long.class));
  }

  private void assertPublicJoinCommittedOnce(PublicJoinFixture fixture) {
    assertThat(publicJoinMembershipCount(fixture)).isEqualTo(1L);
    assertThat(publicJoinAuditCount(fixture)).isEqualTo(1L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_join_operations j "
                    + "JOIN account_tenant_membership m ON m.id = j.membership_id "
                    + "WHERE j.request_id = ? AND j.status = 'COMMITTED' AND j.outcome = 'JOINED' "
                    + "AND j.account_id = ? AND j.tenant_id = ? "
                    + "AND m.account_id = j.account_id AND m.tenant_id = j.tenant_id "
                    + "AND m.lifecycle_state = 'ACTIVE' AND m.authority_provenance = 'EXPLICIT_JOIN' "
                    + "AND m.membership_version = 1 AND m.membership_authority_generation = 1 "
                    + "AND j.outcome_membership_version = m.membership_version "
                    + "AND j.outcome_membership_authority_generation = m.membership_authority_generation",
                fixture.caller().requestId(),
                fixture.accountId(),
                fixture.tenantId()))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM account_audit_outbox "
                    + "WHERE audit_event_id = ? AND scope = 'tenant' AND tenant_id = ? "
                    + "AND producer_service = 'account-service' "
                    + "AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION' "
                    + "AND payload::jsonb ->> 'requestId' = ? "
                    + "AND payload::jsonb ->> 'accountId' = ? "
                    + "AND payload::jsonb ->> 'membershipVersion' = '1'",
                publicJoinAuditId(fixture),
                fixture.tenantId(),
                fixture.caller().requestId(),
                Long.toString(fixture.accountId())))
        .isEqualTo(1L);
  }

  private List<String> publicJoinState(PublicJoinFixture fixture) {
    return List.of(
        dsl.resultQuery(
                "SELECT row_to_json(j)::text FROM account_join_operations j WHERE request_id = ?",
                fixture.caller().requestId())
            .fetchOne(0, String.class),
        dsl.resultQuery(
                "SELECT row_to_json(m)::text FROM account_tenant_membership m "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, String.class),
        dsl.resultQuery(
                // Delivery retry diagnostics may advance independently of original JOIN evidence.
                "SELECT (to_jsonb(a) - ARRAY['attempt_count','last_attempt_at','next_attempt_at'])::text "
                    + "FROM account_audit_outbox a WHERE audit_event_id = ?",
                publicJoinAuditId(fixture))
            .fetchOne(0, String.class),
        dsl.resultQuery(
                "SELECT row_to_json(s)::text FROM account_connect_scope_records s "
                    + "JOIN account_join_operations j ON j.scope_token_hash = s.scope_token_hash "
                    + "WHERE j.request_id = ?",
                fixture.caller().requestId())
            .fetchOne(0, String.class));
  }

  private record PublicJoinFixture(
      long accountId,
      String accountUuid,
      long tenantId,
      DirectTextCallerContext caller,
      String connectScopeId) {}

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
