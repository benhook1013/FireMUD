package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.v1.GameplayRealm;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
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
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH
    })
class AccountJoinPostgresIntegrationTest {
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
  private static final String WORLD_SLUG = "join-proof-world";
  private static final String REALM_SLUG = "production";
  private static final String NAMESPACE_ID = "join-proof-namespace";
  private static final long GAME_INSTANCE_ID = 44L;
  private static final long CATALOG_REVISION = 23L;
  private static final long POINTER_VERSION = 17L;

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

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;
  @MockitoSpyBean private AccountJoinOperationRepository joinOperationRepository;

  @Test
  void terminalCommitWithLostAcknowledgementReadsBackExactStoredJoinResult() {
    JoinFixture fixture = fixture("active");
    AtomicBoolean loseNextTerminalAcknowledgement = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              if ("COMMITTED".equals(invocation.getArgument(1))
                  && loseNextTerminalAcknowledgement.compareAndSet(true, false)) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        throw new IllegalStateException(
                            "simulated lost terminal commit acknowledgement");
                      }
                    });
              }
              return null;
            })
        .when(joinOperationRepository)
        .finish(anyString(), anyString(), anyString(), any(), any(), any());

    JoinPublicProductionResult result = join(fixture);

    assertThat(result)
        .isEqualTo(
            new JoinPublicProductionResult(
                true,
                "JOINED",
                fixture.accountId(),
                fixture.tenantId(),
                result.membershipId(),
                1L,
                1L,
                true));
    assertThat(result.membershipId()).isPositive();
    assertThat(
            dsl.fetchOne(
                "SELECT status, outcome, request_digest, request_digest_version, entitlement_version, allow_public_join, membership_id, outcome_membership_version, outcome_membership_authority_generation "
                    + "FROM account_join_operations WHERE request_id = ?",
                fixture.requestId()))
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("COMMITTED");
              assertThat(row.get("outcome", String.class)).isEqualTo("JOINED");
              assertThat(row.get("request_digest", String.class)).startsWith("sha256:");
              assertThat(row.get("request_digest_version", Integer.class)).isEqualTo(1);
              assertThat(row.get("entitlement_version", Long.class)).isEqualTo(1L);
              assertThat(row.get("allow_public_join", Boolean.class)).isTrue();
              assertThat(row.get("membership_id", Long.class)).isEqualTo(result.membershipId());
              assertThat(row.get("outcome_membership_version", Long.class)).isEqualTo(1L);
              assertThat(row.get("outcome_membership_authority_generation", Long.class))
                  .isEqualTo(1L);
            });
    assertTransitionAndAuditOutboxOnce(fixture, result.membershipId());
  }

  @Test
  void rolledBackTerminalAttemptStaysPendingAndSameRequestCommitsOnceOnRetry() {
    JoinFixture fixture = fixture("active");
    AtomicBoolean failNextTerminalAttempt = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              if ("COMMITTED".equals(invocation.getArgument(1))
                  && failNextTerminalAttempt.compareAndSet(true, false)) {
                throw new IllegalStateException(
                    "simulated failure before terminal transaction commit");
              }
              return null;
            })
        .when(joinOperationRepository)
        .finish(anyString(), anyString(), anyString(), any(), any(), any());

    AuthenticationException uncertain =
        assertThrows(AuthenticationException.class, () -> join(fixture));

    assertThat(uncertain.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(
            dsl.fetchOne(
                "SELECT status, outcome, request_digest, membership_id, last_attempt_failure_code "
                    + "FROM account_join_operations WHERE request_id = ?",
                fixture.requestId()))
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("PENDING");
              assertThat(row.get("outcome", String.class)).isNull();
              assertThat(row.get("request_digest", String.class)).isNull();
              assertThat(row.get("membership_id", Long.class)).isNull();
              assertThat(row.get("last_attempt_failure_code", String.class))
                  .isEqualTo("AUTH_UNAVAILABLE");
            });
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();

    JoinPublicProductionResult recovered = join(fixture);

    assertThat(recovered.success()).isTrue();
    assertThat(recovered.outcomeCode()).isEqualTo("JOINED");
    assertThat(recovered.replayed()).isFalse();
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertTransitionAndAuditOutboxOnce(fixture, recovered.membershipId());
  }

  @Test
  void failedJoinReplaysStoredOutcomeAndRejectsChangedPolicyDigest() {
    JoinFixture fixture = fixture("grace");

    JoinPublicProductionResult failed = join(fixture);
    JoinPublicProductionResult replayed = join(fixture);

    assertThat(failed.success()).isFalse();
    assertThat(failed.outcomeCode()).isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(failed.replayed()).isFalse();
    assertThat(replayed)
        .isEqualTo(
            new JoinPublicProductionResult(
                failed.success(),
                failed.outcomeCode(),
                failed.accountId(),
                failed.tenantId(),
                failed.membershipId(),
                failed.membershipVersion(),
                failed.membershipAuthorityGeneration(),
                true));
    String originalDigest =
        dsl.resultQuery(
                "SELECT request_digest FROM account_join_operations WHERE request_id = ?",
                fixture.requestId())
            .fetchOne(0, String.class);
    assertThat(originalDigest).startsWith("sha256:");
    assertThat(
            dsl.resultQuery(
                    "SELECT status || ':' || outcome FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne(0, String.class))
        .isEqualTo("FAILED:PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();

    dsl.execute(
        "UPDATE subscription SET status = 'active', entitlement_version = 2 WHERE tenant_id = ?",
        fixture.tenantId());
    AuthenticationException conflict =
        assertThrows(AuthenticationException.class, () -> join(fixture));

    assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(
            dsl.resultQuery(
                    "SELECT request_digest FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne(0, String.class))
        .isEqualTo(originalDigest);
    assertThat(
            dsl.resultQuery(
                    "SELECT status || ':' || outcome FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne(0, String.class))
        .isEqualTo("FAILED:PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();
  }

  private JoinFixture fixture(String subscriptionStatus) {
    String suffix = UUID.randomUUID().toString();
    String requestId = "join-proof-" + suffix;
    long tenantId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "join-proof-" + suffix,
                    "join-proof-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) VALUES (?, ?, ?, ?, 1)",
        accountId,
        tenantId,
        "join-proof",
        subscriptionStatus);

    GameplayRealm realm =
        GameplayRealm.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    GameplayAdmissionPointer pointer =
        GameplayAdmissionPointer.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    when(gameSessionClient.listGameplayRealms(WORLD_SLUG)).thenReturn(List.of(realm));
    when(gameSessionClient.getAdmissionPointer(tenantId, WORLD_SLUG, REALM_SLUG))
        .thenReturn(pointer);

    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            accountId,
            tenantId,
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "join-session-" + suffix,
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            tenantId,
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            CATALOG_REVISION,
            POINTER_VERSION);
    DirectTextJoinScope scope = accountService.issueDirectTextConnectScope(caller, target);
    return new JoinFixture(accountId, tenantId, requestId, caller, scope.connectScopeId());
  }

  private long countMemberships(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.connectScopeId(), fixture.requestId()));
  }

  private long countJoinOutbox(JoinFixture fixture) {
    UUID eventId =
        UUID.nameUUIDFromBytes(
            ("account-join-audit/v1:" + fixture.requestId()).getBytes(StandardCharsets.UTF_8));
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND scope = 'tenant' AND tenant_id = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                eventId,
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private void assertTransitionAndAuditOutboxOnce(JoinFixture fixture, long membershipId) {
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE id = ? AND account_id = ? AND tenant_id = ? AND lifecycle_state = 'ACTIVE' AND authority_provenance = 'EXPLICIT_JOIN' AND membership_version = 1 AND membership_authority_generation = 1",
                    membershipId,
                    fixture.accountId(),
                    fixture.tenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(countJoinOutbox(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND payload LIKE ?",
                    UUID.nameUUIDFromBytes(
                        ("account-join-audit/v1:" + fixture.requestId())
                            .getBytes(StandardCharsets.UTF_8)),
                    "%" + fixture.requestId() + "%")
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  private record JoinFixture(
      long accountId,
      long tenantId,
      String requestId,
      DirectTextCallerContext caller,
      String connectScopeId) {}
}
