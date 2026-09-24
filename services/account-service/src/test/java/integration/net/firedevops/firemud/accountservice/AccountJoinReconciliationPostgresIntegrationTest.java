package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.service.AccountJoinReconciliationService;
import net.firedevops.firemud.accountservice.service.AccountService;
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
      "firemud.account.join-reconciliation.interval-ms=3600000",
      "firemud.account.join-reconciliation.batch-size=20",
      "firemud.account.join-reconciliation.max-attempts=2",
      "firemud.account.join-reconciliation.backoff-ms=1000"
    })
class AccountJoinReconciliationPostgresIntegrationTest {
  private static final UUID REALM_ID = UUID.fromString("a825f7ef-0ea3-4e8c-bf7c-a20242b4c931");
  private static final String WORLD_SLUG = "join-reconciliation-world";
  private static final String REALM_SLUG = "production";
  private static final String NAMESPACE_ID = "join-reconciliation-namespace";
  private static final long GAME_INSTANCE_ID = 73L;
  private static final long CATALOG_REVISION = 29L;
  private static final long POINTER_VERSION = 11L;

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
  @Autowired private AccountJoinReconciliationService reconciliationService;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @Test
  void exactPersistedEvidenceRecoversExpiredScopeAndSameRequestRetryWithoutDuplicates() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult original = join(fixture);
    assertThat(original.success()).isTrue();
    assertThat(original.outcomeCode()).isEqualTo("JOINED");
    long membershipId = original.membershipId();

    // This is a synthetic ambiguous row assembled from a genuine committed JOIN. The current
    // atomic terminal transaction cannot naturally commit membership/audit while leaving the
    // operation PENDING; this fixture tests the exact readback branch without inventing evidence.
    makeOperationPendingWithRetainedEvidence(fixture);
    Instant scopeExpiry = expireScopeBeforeReconciliationButAfterEvaluation(fixture);

    Instant now = scopeExpiry.plusSeconds(1);
    reconciliationService.reconcileDueOperations(now);

    assertOperation(fixture, "COMMITTED", "JOINED", 0, null);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_join_operations WHERE request_id = ? AND status = 'COMMITTED' AND outcome = 'JOINED' AND membership_id = ? AND outcome_membership_version = 1 AND outcome_membership_authority_generation = 1",
                    fixture.requestId(),
                    membershipId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertMembershipAndAuditOnce(fixture, membershipId);

    JoinPublicProductionResult retry = join(fixture);
    assertThat(retry.success()).isTrue();
    assertThat(retry.outcomeCode()).isEqualTo("JOINED");
    assertThat(retry.replayed()).isTrue();
    assertThat(retry.membershipId()).isEqualTo(membershipId);
    assertMembershipAndAuditOnce(fixture, membershipId);
  }

  @Test
  void absentScopeAndExpiredScopeWithoutMembershipProofRemainPendingWithDiagnostics() {
    JoinFixture absentScope = committedEvidencePendingFixture();
    dsl.execute(
        "DELETE FROM account_connect_scope_records WHERE scope_token_hash = ?",
        AccountJoinDigest.tokenHash(absentScope.connectScopeId()));
    assertUnresolved(absentScope, "JOIN_SCOPE_EVIDENCE_ABSENT");
    assertThat(countMemberships(absentScope)).isEqualTo(1L);
    assertThat(countJoinOutbox(absentScope)).isEqualTo(1L);

    JoinFixture expiredWithoutMembership = committedEvidencePendingFixture();
    dsl.execute(
        "DELETE FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
        expiredWithoutMembership.accountId(),
        expiredWithoutMembership.tenantId());
    expireScopeBeforeReconciliationButAfterEvaluation(expiredWithoutMembership);
    assertUnresolved(expiredWithoutMembership, "MEMBERSHIP_EVIDENCE_ABSENT");
    assertThat(countMemberships(expiredWithoutMembership)).isZero();
    assertThat(countJoinOutbox(expiredWithoutMembership)).isEqualTo(1L);
  }

  @Test
  void absentMembershipOrAuditEnvelopeCannotTerminalizePendingOperation() {
    JoinFixture absentMembership = committedEvidencePendingFixture();
    dsl.execute(
        "DELETE FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
        absentMembership.accountId(),
        absentMembership.tenantId());
    assertUnresolved(absentMembership, "MEMBERSHIP_EVIDENCE_ABSENT");
    assertThat(countJoinOutbox(absentMembership)).isEqualTo(1L);

    JoinFixture absentAudit = committedEvidencePendingFixture();
    dsl.execute(
        "DELETE FROM account_audit_outbox WHERE audit_event_id = ?",
        joinAuditEventId(absentAudit.requestId()));
    assertUnresolved(absentAudit, "JOIN_AUDIT_ENVELOPE_ABSENT");
    assertThat(countMemberships(absentAudit)).isEqualTo(1L);
    assertThat(countJoinOutbox(absentAudit)).isZero();
  }

  @Test
  void concurrentCallerRetryAndReconcilerKeepSingleMembershipTransitionAndAudit() throws Exception {
    JoinFixture fixture = committedEvidencePendingFixture();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Instant now = Instant.now().plusSeconds(1);
      Future<?> reconciliation =
          executor.submit(
              () -> {
                await(start);
                reconciliationService.reconcileDueOperations(now);
              });
      Future<JoinPublicProductionResult> callerRetry =
          executor.submit(
              () -> {
                await(start);
                return join(fixture);
              });
      start.countDown();

      reconciliation.get(30, TimeUnit.SECONDS);
      JoinPublicProductionResult retry = callerRetry.get(30, TimeUnit.SECONDS);

      assertThat(retry.success()).isTrue();
      assertThat(retry.outcomeCode()).isIn("JOINED", "ALREADY_ACTIVE");
      assertOperation(fixture, "COMMITTED", retry.outcomeCode(), 0, null);
      assertMembershipAndAuditOnce(fixture, originalMembershipId(fixture));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void naturallyAbandonedPendingIntentStaysVisibleAndStopsAtConfiguredAttemptLimit() {
    JoinFixture fixture = fixture("active");
    when(gameSessionClient.getAdmissionPointer(fixture.tenantId(), WORLD_SLUG, REALM_SLUG))
        .thenThrow(new IllegalStateException("simulated unavailable admission authority"));

    JoinPublicProductionResult attempt = join(fixture);

    assertThat(attempt.success()).isFalse();
    assertThat(attempt.outcomeCode()).isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    assertOperation(fixture, "PENDING", null, 0, null);
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();

    Instant firstAttemptAt = Instant.now().plusSeconds(1);
    reconciliationService.reconcileDueOperations(firstAttemptAt);
    assertOperation(fixture, "PENDING", null, 1, "JOIN_OPERATION_POLICY_UNPROVEN");

    reconciliationService.reconcileDueOperations(firstAttemptAt);
    assertOperation(fixture, "PENDING", null, 1, "JOIN_OPERATION_POLICY_UNPROVEN");

    reconciliationService.reconcileDueOperations(firstAttemptAt.plusMillis(1500));
    assertOperation(fixture, "PENDING", null, 2, "JOIN_OPERATION_POLICY_UNPROVEN");

    reconciliationService.reconcileDueOperations(firstAttemptAt.plusSeconds(5));
    assertOperation(fixture, "PENDING", null, 2, "JOIN_OPERATION_POLICY_UNPROVEN");
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();
  }

  private JoinFixture committedEvidencePendingFixture() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult committed = join(fixture);
    assertThat(committed.success()).isTrue();
    assertThat(committed.outcomeCode()).isEqualTo("JOINED");
    makeOperationPendingWithRetainedEvidence(fixture);
    return fixture;
  }

  private void makeOperationPendingWithRetainedEvidence(JoinFixture fixture) {
    int updated =
        dsl.execute(
            "UPDATE account_join_operations SET status = 'PENDING', outcome = NULL, membership_id = NULL, outcome_membership_version = NULL, outcome_membership_authority_generation = NULL, reconciliation_attempt_count = 0, last_reconciliation_attempt_at = NULL, last_reconciliation_attempt_reason = NULL, next_reconciliation_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second', updated_at = CURRENT_TIMESTAMP WHERE request_id = ? AND status = 'COMMITTED' AND outcome = 'JOINED'",
            fixture.requestId());
    assertThat(updated).isEqualTo(1);
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(countJoinOutbox(fixture)).isEqualTo(1L);
  }

  private Instant expireScopeBeforeReconciliationButAfterEvaluation(JoinFixture fixture) {
    String expiresAt =
        dsl.resultQuery(
                "SELECT connect_scope_expires_at FROM account_connect_scope_records WHERE scope_token_hash = ?",
                AccountJoinDigest.tokenHash(fixture.connectScopeId()))
            .fetchOne(0, String.class);
    assertThat(expiresAt).isNotBlank();
    return Instant.parse(expiresAt);
  }

  private void assertUnresolved(JoinFixture fixture, String reason) {
    Instant now = Instant.now().plusSeconds(2);
    reconciliationService.reconcileDueOperations(now);
    assertOperation(fixture, "PENDING", null, 1, reason);

    reconciliationService.reconcileDueOperations(now);
    assertOperation(fixture, "PENDING", null, 1, reason);
  }

  private void assertOperation(
      JoinFixture fixture, String status, String outcome, int attempts, String reason) {
    var row =
        dsl.resultQuery(
                "SELECT status, outcome, reconciliation_attempt_count, last_reconciliation_attempt_reason, (next_reconciliation_attempt_at > last_reconciliation_attempt_at) AS backoff_scheduled FROM account_join_operations WHERE request_id = ?",
                fixture.requestId())
            .fetchOne();
    assertThat(row).isNotNull();
    assertThat(row.get("status", String.class)).isEqualTo(status);
    assertThat(row.get("outcome", String.class)).isEqualTo(outcome);
    assertThat(row.get("reconciliation_attempt_count", Integer.class)).isEqualTo(attempts);
    assertThat(row.get("last_reconciliation_attempt_reason", String.class)).isEqualTo(reason);
    if (attempts > 0) {
      assertThat(row.get("backoff_scheduled", Boolean.class)).isTrue();
    }
  }

  private long originalMembershipId(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT id FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private void assertMembershipAndAuditOnce(JoinFixture fixture, long membershipId) {
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE id = ? AND account_id = ? AND tenant_id = ? AND lifecycle_state = 'ACTIVE' AND gameplay_admission_allowed = TRUE AND authority_provenance = 'EXPLICIT_JOIN' AND membership_version = 1 AND membership_authority_generation = 1",
                    membershipId,
                    fixture.accountId(),
                    fixture.tenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(countJoinOutbox(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND scope = 'tenant' AND tenant_id = ? AND producer_service = 'account-service' AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION' AND payload_digest_version = 1 AND payload_digest LIKE 'sha256:%' AND payload LIKE ?",
                    joinAuditEventId(fixture.requestId()),
                    fixture.tenantId(),
                    "%" + fixture.requestId() + "%")
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  private long countMemberships(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private long countJoinOutbox(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND scope = 'tenant' AND tenant_id = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                joinAuditEventId(fixture.requestId()),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.connectScopeId(), fixture.requestId()));
  }

  private JoinFixture fixture(String subscriptionStatus) {
    String suffix = UUID.randomUUID().toString();
    String requestId = "join-reconcile-" + suffix;
    long tenantId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "join-reconcile-" + suffix,
                    "join-reconcile-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) VALUES (?, ?, ?, ?, 1)",
        accountId,
        tenantId,
        "join-reconcile",
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

  private static UUID joinAuditEventId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private static void await(CountDownLatch start) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrent JOIN test did not receive its start signal");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent JOIN test was interrupted", ex);
    }
  }

  private record JoinFixture(
      long accountId,
      long tenantId,
      String requestId,
      DirectTextCallerContext caller,
      String connectScopeId) {}
}
