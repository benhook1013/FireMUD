package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountJoinReconciliationService;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
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
      "firemud.grpc.workload-namespace=account_service",
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
  @Autowired private ApprovedLegacyTenantAssociationRepository tenantAssociationRepository;
  @Autowired private LegacyTenantSourceEvidence legacyTenantSourceEvidence;

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
    AuthorityEventSnapshot originalAuthorityEvent = assertAuthorityMembershipEvent(fixture);

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
                    "SELECT COUNT(*) FROM account_join_operations WHERE request_id = ? AND status = 'COMMITTED' AND outcome = 'JOINED' AND membership_id = ? AND outcome_membership_version = 2 AND outcome_membership_authority_generation = 1",
                    fixture.requestId(),
                    membershipId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertMembershipAndAuditOnce(fixture, membershipId);
    assertThat(assertAuthorityMembershipEvent(fixture)).isEqualTo(originalAuthorityEvent);

    JoinPublicProductionResult retry = join(fixture);
    assertThat(retry.success()).isTrue();
    assertThat(retry.outcomeCode()).isEqualTo("JOINED");
    assertThat(retry.replayed()).isTrue();
    assertThat(retry.membershipId()).isEqualTo(membershipId);
    assertMembershipAndAuditOnce(fixture, membershipId);
    assertThat(assertAuthorityMembershipEvent(fixture)).isEqualTo(originalAuthorityEvent);
  }

  @Test
  void newerMembershipGenerationWithoutCanonicalEventRemainsPending() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult committed = join(fixture);
    assertThat(committed.success()).isTrue();
    AuthorityEventSnapshot originalAuthorityEvent = assertAuthorityMembershipEvent(fixture);
    makeOperationPendingWithRetainedEvidence(fixture);
    Map<String, Object> originalReceipt = membershipTransitionReceiptSnapshot(fixture);
    Map<String, Object> originalAudit = joinAuditEnvelopeSnapshot(fixture);

    // Advance the canonical V31 membership generation and issuance fence monotonically while
    // retaining the active membership, JOIN receipt, audit, and immutable V33 event unchanged.
    // No event records this newer authority checkpoint, so the retained JOIN event is stale.
    advanceMembershipGenerationAndFenceWithoutEvent(fixture);
    Instant scopeExpiry = expireScopeBeforeReconciliationButAfterEvaluation(fixture);
    reconciliationService.reconcileDueOperations(scopeExpiry.plusSeconds(1));

    assertPendingAfterUnprovedAuthorityEvent(fixture);
    assertMembershipAndAuditOnce(fixture, committed.membershipId());
    assertThat(membershipTransitionReceiptSnapshot(fixture)).isEqualTo(originalReceipt);
    assertThat(joinAuditEnvelopeSnapshot(fixture)).isEqualTo(originalAudit);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
    assertThat(assertAuthorityMembershipEvent(fixture)).isEqualTo(originalAuthorityEvent);
    assertThat(
            dsl.resultQuery(
                    "SELECT lifecycle_state, gameplay_admission_allowed, membership_version, "
                        + "membership_authority_generation FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_id = ?",
                    fixture.accountId(),
                    fixture.tenantId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("lifecycle_state", String.class)).isEqualTo("ACTIVE");
              assertThat(row.get("gameplay_admission_allowed", Boolean.class)).isTrue();
              assertThat(row.get("membership_version", Long.class)).isEqualTo(2L);
              assertThat(row.get("membership_authority_generation", Long.class)).isEqualTo(1L);
            });
    assertThat(membershipAuthorityGeneration(fixture)).isEqualTo(2L);
    assertThat(issuanceFence(fixture)).isEqualTo(2L);
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
    deleteMembershipEvidence(expiredWithoutMembership);
    expireScopeBeforeReconciliationButAfterEvaluation(expiredWithoutMembership);
    assertUnresolved(expiredWithoutMembership, "MEMBERSHIP_EVIDENCE_ABSENT");
    assertThat(countMemberships(expiredWithoutMembership)).isZero();
    assertThat(countJoinOutbox(expiredWithoutMembership)).isEqualTo(1L);
  }

  @Test
  void absentMembershipOrAuditEnvelopeCannotTerminalizePendingOperation() {
    JoinFixture absentMembership = committedEvidencePendingFixture();
    deleteMembershipEvidence(absentMembership);
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
  void absentRoleSnapshotLeavesPendingAndPreservesMembershipAndAuditEvidence() {
    JoinFixture fixture = committedEvidencePendingFixture();
    deleteRoleSnapshot(fixture);

    assertUnresolved(fixture, "MEMBERSHIP_ROLE_SNAPSHOT_ABSENT");
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(countJoinOutbox(fixture)).isEqualTo(1L);
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

  private void assertPendingAfterUnprovedAuthorityEvent(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT status, outcome, membership_id, outcome_membership_version, "
                    + "outcome_membership_authority_generation, reconciliation_attempt_count, "
                    + "last_reconciliation_attempt_reason, "
                    + "(next_reconciliation_attempt_at > last_reconciliation_attempt_at) "
                    + "AS backoff_scheduled FROM account_join_operations WHERE request_id = ?",
                fixture.requestId())
            .fetchOne();
    assertThat(row).isNotNull();
    assertThat(row.get("status", String.class)).isEqualTo("PENDING");
    assertThat(row.get("outcome", String.class)).isNull();
    assertThat(row.get("membership_id", Long.class)).isNull();
    assertThat(row.get("outcome_membership_version", Long.class)).isNull();
    assertThat(row.get("outcome_membership_authority_generation", Long.class)).isNull();
    assertThat(row.get("reconciliation_attempt_count", Integer.class)).isEqualTo(1);
    assertThat(row.get("last_reconciliation_attempt_reason", String.class))
        .isEqualTo("MEMBERSHIP_AUTHORITY_EVENT_UNAVAILABLE");
    assertThat(row.get("backoff_scheduled", Boolean.class)).isTrue();
  }

  private void advanceMembershipGenerationAndFenceWithoutEvent(JoinFixture fixture) {
    dsl.transaction(
        configuration -> {
          org.jooq.DSLContext transactionDsl = org.jooq.impl.DSL.using(configuration);
          var membershipState =
              transactionDsl.fetchOne(
                  "SELECT generation, source_version FROM account_authority_generations "
                      + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? "
                      + "AND tenant_uuid = ? FOR UPDATE",
                  fixture.accountUuid(),
                  fixture.tenantUuid());
          assertThat(membershipState).isNotNull();
          long membershipGeneration = membershipState.get("generation", Long.class);
          long membershipSourceVersion = membershipState.get("source_version", Long.class);
          int generationUpdates =
              transactionDsl.execute(
                  "UPDATE account_authority_generations "
                      + "SET generation = generation + 1, source_version = source_version + 1 "
                      + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? "
                      + "AND tenant_uuid = ? AND generation = ? AND source_version = ?",
                  fixture.accountUuid(),
                  fixture.tenantUuid(),
                  membershipGeneration,
                  membershipSourceVersion);
          assertThat(generationUpdates).isEqualTo(1);
          var fenceState =
              transactionDsl.fetchOne(
                  "SELECT issuance_fence, source_version FROM account_authority_issuance_fences "
                      + "WHERE account_uuid = ? FOR UPDATE",
                  fixture.accountUuid());
          assertThat(fenceState).isNotNull();
          long issuanceFence = fenceState.get("issuance_fence", Long.class);
          long fenceSourceVersion = fenceState.get("source_version", Long.class);
          int fenceUpdates =
              transactionDsl.execute(
                  "UPDATE account_authority_issuance_fences "
                      + "SET issuance_fence = issuance_fence + 1, "
                      + "source_version = source_version + 1 "
                      + "WHERE account_uuid = ? AND issuance_fence = ? AND source_version = ?",
                  fixture.accountUuid(),
                  issuanceFence,
                  fenceSourceVersion);
          assertThat(fenceUpdates).isEqualTo(1);
        });
  }

  private Map<String, Object> membershipTransitionReceiptSnapshot(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT receipt_stream_key, receipt_sequence, account_id, tenant_id, "
                    + "evidence_status, transition_type, request_id, membership_id, "
                    + "membership_lifecycle_state, gameplay_admission_allowed, membership_version, "
                    + "membership_authority_generation, authority_provenance, receipt_id, "
                    + "receipt_digest FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ? ORDER BY receipt_sequence DESC LIMIT 1",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private Map<String, Object> joinAuditEnvelopeSnapshot(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT audit_event_id, scope, tenant_id, producer_service, event_type, "
                    + "occurred_at, schema_version, payload_digest_version, payload_digest, "
                    + "payload FROM account_audit_outbox "
                    + "WHERE audit_event_id = ? AND tenant_id = ?",
                joinAuditEventId(fixture.requestId()),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private long membershipAuthorityGeneration(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT generation FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne(0, Long.class));
  }

  private long issuanceFence(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                fixture.accountUuid())
            .fetchOne(0, Long.class));
  }

  private long originalMembershipId(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT id FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private void deleteMembershipEvidence(JoinFixture fixture) {
    long membershipId = deleteRoleSnapshot(fixture);
    int deletedMemberships =
        dsl.execute(
            "DELETE FROM account_tenant_membership WHERE id = ? AND account_id = ? AND tenant_id = ?",
            membershipId,
            fixture.accountId(),
            fixture.tenantId());
    assertThat(deletedMemberships).isEqualTo(1);
  }

  private long deleteRoleSnapshot(JoinFixture fixture) {
    var membership =
        dsl.resultQuery(
                "SELECT id, membership_version FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(membership).isNotNull();
    long membershipId = Objects.requireNonNull(membership.get("id", Long.class));
    long snapshotVersion = Objects.requireNonNull(membership.get("membership_version", Long.class));

    int deletedRoles =
        dsl.execute(
            "DELETE FROM account_tenant_membership_role_snapshot_roles WHERE membership_id = ? AND snapshot_version = ?",
            membershipId,
            snapshotVersion);
    assertThat(deletedRoles).isEqualTo(1);
    int deletedHeaders =
        dsl.execute(
            "DELETE FROM account_tenant_membership_role_snapshots WHERE membership_id = ? AND snapshot_version = ?",
            membershipId,
            snapshotVersion);
    assertThat(deletedHeaders).isEqualTo(1);
    return membershipId;
  }

  private void assertMembershipAndAuditOnce(JoinFixture fixture, long membershipId) {
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE id = ? AND account_id = ? AND tenant_id = ? AND lifecycle_state = 'ACTIVE' AND gameplay_admission_allowed = TRUE AND authority_provenance = 'EXPLICIT_JOIN' AND membership_version = 2 AND membership_authority_generation = 1",
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

  private AuthorityEventSnapshot assertAuthorityMembershipEvent(JoinFixture fixture) {
    String streamKey = authorityStreamKey(fixture);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    streamKey)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    var row =
        dsl.resultQuery(
                "SELECT outbox_sequence, request_id, event_id, event_digest, payload "
                    + "FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                streamKey)
            .fetchOne();
    assertThat(row).isNotNull();
    assertThat(row.get("request_id", String.class)).isEqualTo(fixture.requestId());
    String eventId = row.get("event_id", String.class);
    String eventDigest = row.get("event_digest", String.class);
    byte[] payload = row.get("payload", byte[].class);
    String payloadJson = new String(payload, StandardCharsets.UTF_8);
    MembershipAuthorityEventV1Codec.MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(payloadJson);

    assertThat(row.get("outbox_sequence", Long.class)).isEqualTo(1L);
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.requestId()).isEqualTo(fixture.requestId());
    assertThat(event.schemaVersion()).isEqualTo(MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(event.eventType()).isEqualTo(MembershipAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(event.outboxStreamKey()).isEqualTo(streamKey);
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.sourceScope())
        .isEqualTo("membership/" + fixture.accountUuid() + "/" + fixture.tenantUuid());
    assertThat(event.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(event.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(event.membershipVersion()).isEqualTo("2");
    assertThat(event.roles()).containsExactly("player");
    assertThat(event.gameplayAdmissionAllowed()).isTrue();
    assertThat(event.canonicalJson()).isEqualTo(payloadJson);
    assertThat(payload).containsExactly(event.canonicalJsonUtf8());
    assertThat(eventDigest).isEqualTo(event.eventDigest());
    assertThat(eventDigest).matches("sha256:[0-9a-f]{64}");

    return new AuthorityEventSnapshot(eventId, eventDigest, payloadJson);
  }

  private String authorityStreamKey(JoinFixture fixture) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + fixture.accountUuid()
        + "/"
        + fixture.tenantUuid();
  }

  private long countAuthorityMembershipEvents(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ?",
                authorityStreamKey(fixture))
            .fetchOne(0, Long.class));
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
    String requestId = "join-rec-" + suffix;
    long tenantId = positiveRandomLong();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "join-rec-" + suffix,
                    "join-rec-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(accountUuid);
    seedRetainedV26TenantEvidence(tenantId, suffix);
    UUID tenantUuid = UUID.randomUUID();
    String evidenceDigest = legacyTenantSourceEvidence.digest(tenantId);
    tenantAssociationRepository.importApproved(
        tenantId, approvedTenantAssociation(tenantId, tenantUuid, evidenceDigest, suffix));
    var storedAssociation =
        tenantAssociationRepository.findByLegacyTenantId(tenantId).orElseThrow();
    assertThat(storedAssociation.legacyTenantId()).isEqualTo(tenantId);
    assertThat(storedAssociation.canonicalTenantId()).isEqualTo(tenantUuid);
    assertThat(storedAssociation.accountEvidenceDigest()).isEqualTo(evidenceDigest);
    assertThat(
            dsl.resultQuery(
                    "SELECT generation FROM account_authority_generations "
                        + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                    tenantUuid)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_legacy_tenant_sources " + "WHERE account_id = ?",
                    accountId)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_legacy_membership_sources "
                        + "WHERE account_id = ?",
                    accountId)
                .fetchOne(0, Long.class))
        .isZero();
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
    return new JoinFixture(
        accountId, accountUuid, tenantId, tenantUuid, requestId, caller, scope.connectScopeId());
  }

  private void seedRetainedV26TenantEvidence(long tenantId, String suffix) {
    long donorAccountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                    "jrd-" + suffix,
                    "join-rec-retained-donor-" + suffix + "@example.com",
                    "test-hash",
                    tenantId)
                .fetchOne(0, Long.class));
    UUID donorAccountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", donorAccountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(donorAccountUuid);
    long retainedMembershipId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                        + "RETURNING id",
                    donorAccountId,
                    tenantId)
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, "
            + "matching_profile_count, disposition) VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
        donorAccountId,
        tenantId,
        retainedMembershipId);
    dsl.execute(
        "INSERT INTO account_legacy_membership_sources "
            + "(membership_id, account_id, tenant_id, original_gameplay_admission_allowed, "
            + "matches_account_legacy_tenant, disposition) "
            + "VALUES (?, ?, ?, FALSE, TRUE, 'UNVERIFIED')",
        retainedMembershipId,
        donorAccountId,
        tenantId);
  }

  private void seedAccountAuthorityState(UUID accountUuid) {
    dsl.execute(
        "INSERT INTO account_authority_generations "
            + "(scope_kind, account_uuid, generation, source_version) "
            + "VALUES ('ACCOUNT', ?, 1, 1)",
        accountUuid);
    dsl.execute(
        "INSERT INTO account_authority_issuance_fences "
            + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
        accountUuid);
  }

  private ResolveLegacyAccountTenantAssociationResponse approvedTenantAssociation(
      long legacyTenantId, UUID tenantUuid, String evidenceDigest, String suffix) {
    String sourceLegacyGameTenantId = "legacy-game-" + suffix.replace("-", "").substring(0, 24);
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(legacyTenantId)
        .setCanonicalTenantId(tenantUuid.toString())
        .setSourceLegacyGameTenantId(sourceLegacyGameTenantId)
        .setSourceGameRowId(positiveRandomLong())
        .setAccountEvidenceDigest(evidenceDigest)
        .setOperationId(UUID.randomUUID().toString())
        .setManifestDigest("sha256:" + "b".repeat(64))
        .setManifestSignature(java.util.Base64.getEncoder().encodeToString(new byte[64]))
        .setTargetNamespace("account_service")
        .setSignerKeyId("game-design-owner-test")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("unit-1b-postgres-fixture")
        .setSignedAt("2026-09-26T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1)
        .build();
  }

  private long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
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
      UUID accountUuid,
      long tenantId,
      UUID tenantUuid,
      String requestId,
      DirectTextCallerContext caller,
      String connectScopeId) {}

  private record AuthorityEventSnapshot(String eventId, String eventDigest, String payloadJson) {}
}
