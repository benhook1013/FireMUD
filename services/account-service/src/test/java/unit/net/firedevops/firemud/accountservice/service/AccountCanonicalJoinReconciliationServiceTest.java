package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.dto.CanonicalMembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinReconciliationCandidate;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountCanonicalJoinReconciliationServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");
  private static final long ACCOUNT_ROW_ID = 101L;
  private static final long MEMBERSHIP_ROW_ID = 501L;
  private static final UUID ACCOUNT_UUID = UUID.fromString("f951c9e1-5854-45b6-955f-262df49b3d10");
  private static final UUID TENANT_UUID = UUID.fromString("8b5949df-8245-4cea-b740-910450fd37e5");
  private static final UUID REQUEST_UUID = UUID.fromString("f14f9e1e-f6d0-4473-9e14-529c08744dc2");
  private static final String REQUEST_ID = REQUEST_UUID.toString();
  private static final String WORLD_SLUG = "first-world";
  private static final String REALM_SLUG = "production";
  private static final String SHA = "sha256:" + "a".repeat(64);
  private static final UUID TENANT_SOURCE_OPERATION =
      UUID.fromString("47849617-e9da-4dcb-a638-754b26605bb7");
  private static final VerifiedTenantProvenance TENANT_PROVENANCE =
      new VerifiedTenantProvenance(
          null, TenantProvenanceKind.FRESH_GAME_DESIGN, TENANT_SOURCE_OPERATION, SHA);

  @Test
  void canonicalAuditPayloadUsesExistingFieldsAndRequiresExactOneTenantVersionMap() {
    String payload =
        AccountAuditOutboxRepository.canonicalJoinPayload(
            ACCOUNT_UUID,
            TENANT_UUID,
            WORLD_SLUG,
            REALM_SLUG,
            Map.of(TENANT_UUID.toString(), "2"),
            REQUEST_ID);

    assertThat(payload)
        .isEqualTo(
            "{\"accountId\":\""
                + ACCOUNT_UUID
                + "\",\"tenantId\":\""
                + TENANT_UUID
                + "\",\"worldSlug\":\"first-world\",\"realmSlug\":\"production\","
                + "\"membershipVersion\":{\""
                + TENANT_UUID
                + "\":\"2\"},\"requestId\":\""
                + REQUEST_ID
                + "\"}");
    assertThatThrownBy(
            () ->
                AccountAuditOutboxRepository.canonicalJoinPayload(
                    ACCOUNT_UUID, TENANT_UUID, WORLD_SLUG, REALM_SLUG, Map.of(), REQUEST_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountAuditOutboxRepository.canonicalJoinPayload(
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    WORLD_SLUG,
                    REALM_SLUG,
                    Map.of(TENANT_UUID.toString(), "2", UUID.randomUUID().toString(), "3"),
                    REQUEST_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fullyProvedExpiredJoinSurvivesLaterUnavailableDiagnosticWithoutRewritingEvidence() {
    Fixture fixture = new Fixture();
    CanonicalJoinOperationEvidence pending = pendingOperation(true, "UNAVAILABLE", "TIMEOUT");
    stubDue(fixture, pending);
    stubPositiveEvidence(fixture, pending);
    when(fixture.operations.finishCanonicalOperation(
            REQUEST_ID, "COMMITTED", "JOINED", MEMBERSHIP_ROW_ID, 2L, 1L))
        .thenReturn(terminalOperation(pending));

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.operations).lockAccount(ACCOUNT_ROW_ID);
    verify(fixture.operations).findCanonicalEvidenceByRequestId(REQUEST_ID);
    verify(fixture.operations)
        .finishCanonicalOperation(REQUEST_ID, "COMMITTED", "JOINED", MEMBERSHIP_ROW_ID, 2L, 1L);
    verify(fixture.operations, never())
        .recordCanonicalReconciliationAttempt(
            anyString(), anyInt(), anyInt(), any(), anyString(), any());
    verify(fixture.memberships).findCanonicalMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID);
    verify(fixture.roleSnapshots)
        .findForCanonicalUpdate(
            ACCOUNT_UUID, TENANT_UUID, TENANT_PROVENANCE, MEMBERSHIP_ROW_ID, 2L);
    verify(fixture.receipts).findCanonicalByRequestId(REQUEST_ID);
    verify(fixture.audit).findCanonicalJoinEnvelopeForUpdate(fixture.auditEventId(), TENANT_UUID);
    verify(fixture.eventProducer).requireCanonicalFirstJoinEvent(pending);
    verify(fixture.memberships, never()).saveCanonical(any(), any(), any(), any());
    verify(fixture.roleSnapshots, never())
        .replaceCanonical(any(), any(), any(), any(), anyLong(), any());
    verify(fixture.receipts, never())
        .appendCanonicalTransition(any(), any(), anyString(), anyString());
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
  }

  @Test
  void absentMembershipRemainsPendingEvenWhenScopeExpired() {
    Fixture fixture = new Fixture();
    CanonicalJoinOperationEvidence pending = pendingOperation(true, "AVAILABLE", null);
    stubDue(fixture, pending);
    when(fixture.operations.findCanonicalEvidenceByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(pending));
    when(fixture.memberships.findCanonicalMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.empty());
    when(fixture.operations.recordCanonicalReconciliationAttempt(
            REQUEST_ID, 1, 3, NOW, "CANONICAL_MEMBERSHIP_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.operations)
        .recordCanonicalReconciliationAttempt(
            REQUEST_ID, 1, 3, NOW, "CANONICAL_MEMBERSHIP_ABSENT", NOW.plusMillis(5_000));
    verify(fixture.operations, never())
        .finishCanonicalOperation(anyString(), anyString(), anyString(), any(), any(), any());
    verifyNoInteractions(
        fixture.roleSnapshots, fixture.receipts, fixture.audit, fixture.eventProducer);
  }

  @Test
  void absentReceiptOrAuditRemainsPendingWithoutCreatingDependentEvidence() {
    Fixture receiptFixture = new Fixture();
    CanonicalJoinOperationEvidence pending = pendingOperation(true, "AVAILABLE", null);
    stubDue(receiptFixture, pending);
    AccountTenantMembership membership = activeMembership();
    when(receiptFixture.operations.findCanonicalEvidenceByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(pending));
    when(receiptFixture.memberships.findCanonicalMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.of(membership));
    when(receiptFixture.roleSnapshots.findForCanonicalUpdate(
            ACCOUNT_UUID, TENANT_UUID, TENANT_PROVENANCE, MEMBERSHIP_ROW_ID, 2L))
        .thenReturn(Optional.of(roleSnapshot()));
    when(receiptFixture.receipts.findCanonicalByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
    when(receiptFixture.operations.recordCanonicalReconciliationAttempt(
            REQUEST_ID, 1, 3, NOW, "CANONICAL_TRANSITION_RECEIPT_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    receiptFixture.service.reconcileDueOperations(NOW);

    verify(receiptFixture.operations)
        .recordCanonicalReconciliationAttempt(
            REQUEST_ID, 1, 3, NOW, "CANONICAL_TRANSITION_RECEIPT_ABSENT", NOW.plusMillis(5_000));
    verify(receiptFixture.audit, never()).findCanonicalJoinEnvelopeForUpdate(any(), any());
    verify(receiptFixture.eventProducer, never()).requireCanonicalFirstJoinEvent(any());
    verify(receiptFixture.operations, never())
        .finishCanonicalOperation(anyString(), anyString(), anyString(), any(), any(), any());

    Fixture auditFixture = new Fixture();
    stubDue(auditFixture, pending);
    when(auditFixture.operations.findCanonicalEvidenceByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(pending));
    AccountTenantMembership auditMembership = activeMembership();
    when(auditFixture.memberships.findCanonicalMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.of(auditMembership));
    when(auditFixture.roleSnapshots.findForCanonicalUpdate(
            ACCOUNT_UUID, TENANT_UUID, TENANT_PROVENANCE, MEMBERSHIP_ROW_ID, 2L))
        .thenReturn(Optional.of(roleSnapshot()));
    when(auditFixture.receipts.findCanonicalByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(transitionReceipt()));
    when(auditFixture.audit.findCanonicalJoinEnvelopeForUpdate(
            auditFixture.auditEventId(), TENANT_UUID))
        .thenReturn(Optional.empty());
    when(auditFixture.operations.recordCanonicalReconciliationAttempt(
            REQUEST_ID, 1, 3, NOW, "CANONICAL_JOIN_AUDIT_ENVELOPE_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    auditFixture.service.reconcileDueOperations(NOW);

    verify(auditFixture.eventProducer, never()).requireCanonicalFirstJoinEvent(any());
    verify(auditFixture.operations, never())
        .finishCanonicalOperation(anyString(), anyString(), anyString(), any(), any(), any());
    verify(auditFixture.receipts, never())
        .appendCanonicalTransition(any(), any(), anyString(), anyString());
  }

  @Test
  void unavailablePolicyCannotBeReplacedByPositiveMembershipHistory() {
    Fixture fixture = new Fixture();
    CanonicalJoinOperationEvidence pending = pendingOperation(false, "UNAVAILABLE", "TIMEOUT");
    stubDue(fixture, pending);
    when(fixture.operations.findCanonicalEvidenceByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(pending));
    when(fixture.operations.recordCanonicalReconciliationAttempt(
            REQUEST_ID,
            1,
            3,
            NOW,
            "CANONICAL_JOIN_OPERATION_POLICY_UNPROVEN",
            NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.operations)
        .recordCanonicalReconciliationAttempt(
            REQUEST_ID,
            1,
            3,
            NOW,
            "CANONICAL_JOIN_OPERATION_POLICY_UNPROVEN",
            NOW.plusMillis(5_000));
    verifyNoInteractions(
        fixture.memberships, fixture.roleSnapshots, fixture.receipts, fixture.audit);
    verify(fixture.eventProducer, never()).requireCanonicalFirstJoinEvent(any());
    verify(fixture.operations, never())
        .finishCanonicalOperation(anyString(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void malformedScopeSourceCandidateRecordsDiagnosticAndDoesNotStarveLaterValidCandidate() {
    Fixture fixture = new Fixture();
    CanonicalJoinOperationEvidence pending = pendingOperation(true, "AVAILABLE", null);
    CanonicalJoinReconciliationCandidate malformedSource =
        new CanonicalJoinReconciliationCandidate(
            "missing-scope-source", ACCOUNT_ROW_ID, 1, NOW.minusSeconds(1));
    when(fixture.operations.findDueCanonicalPendingReconciliation(NOW, 10, 3))
        .thenReturn(List.of(malformedSource, candidate(pending)));
    when(fixture.operations.findCanonicalEvidenceByRequestId("missing-scope-source"))
        .thenThrow(new IllegalStateException("canonical scope source is absent"));
    when(fixture.operations.recordCanonicalReconciliationAttempt(
            "missing-scope-source",
            1,
            3,
            NOW,
            "CANONICAL_JOIN_READBACK_UNAVAILABLE",
            NOW.plusMillis(5_000)))
        .thenReturn(true);
    stubPositiveEvidence(fixture, pending);
    when(fixture.operations.finishCanonicalOperation(
            REQUEST_ID, "COMMITTED", "JOINED", MEMBERSHIP_ROW_ID, 2L, 1L))
        .thenReturn(terminalOperation(pending));

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.operations)
        .recordCanonicalReconciliationAttempt(
            "missing-scope-source",
            1,
            3,
            NOW,
            "CANONICAL_JOIN_READBACK_UNAVAILABLE",
            NOW.plusMillis(5_000));
    verify(fixture.operations)
        .finishCanonicalOperation(REQUEST_ID, "COMMITTED", "JOINED", MEMBERSHIP_ROW_ID, 2L, 1L);
  }

  private static void stubDue(Fixture fixture, CanonicalJoinOperationEvidence operation) {
    when(fixture.operations.findDueCanonicalPendingReconciliation(NOW, 10, 3))
        .thenReturn(List.of(candidate(operation)));
  }

  private static CanonicalJoinReconciliationCandidate candidate(
      CanonicalJoinOperationEvidence operation) {
    return new CanonicalJoinReconciliationCandidate(
        operation.requestId(),
        operation.privateAccountId(),
        operation.reconciliationAttemptCount(),
        operation.nextReconciliationAttemptAt());
  }

  private static void stubPositiveEvidence(
      Fixture fixture, CanonicalJoinOperationEvidence operation) {
    when(fixture.operations.findCanonicalEvidenceByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(operation));
    AccountTenantMembership membership = activeMembership();
    when(fixture.memberships.findCanonicalMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.of(membership));
    when(fixture.roleSnapshots.findForCanonicalUpdate(
            ACCOUNT_UUID, TENANT_UUID, TENANT_PROVENANCE, MEMBERSHIP_ROW_ID, 2L))
        .thenReturn(Optional.of(roleSnapshot()));
    when(fixture.receipts.findCanonicalByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(transitionReceipt()));
    when(fixture.audit.findCanonicalJoinEnvelopeForUpdate(fixture.auditEventId(), TENANT_UUID))
        .thenReturn(Optional.of(auditEnvelope(operation)));
    when(fixture.eventProducer.requireCanonicalFirstJoinEvent(operation))
        .thenReturn(
            new Checkpoint(
                "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID,
                1L,
                "event-1",
                SHA));
  }

  private static CanonicalJoinOperationEvidence pendingOperation(
      boolean allowPublicJoin, String lastAvailability, String lastFailure) {
    CanonicalConnectScopeEvidence scope = scopeEvidence();
    return new CanonicalJoinOperationEvidence(
        REQUEST_ID,
        ACCOUNT_ROW_ID,
        "game-session:fixture",
        scope.scopeTokenHash(),
        scope.scopeDigest(),
        2,
        2,
        2,
        SHA,
        "AVAILABLE",
        allowPublicJoin,
        19L,
        2,
        SHA,
        lastAvailability,
        lastFailure,
        false,
        "PENDING",
        null,
        null,
        null,
        null,
        1,
        NOW.minusSeconds(10),
        "PREVIOUS_RECONCILIATION_ATTEMPT",
        NOW.minusSeconds(1),
        scope);
  }

  private static CanonicalJoinOperationEvidence terminalOperation(
      CanonicalJoinOperationEvidence pending) {
    return new CanonicalJoinOperationEvidence(
        pending.requestId(),
        pending.privateAccountId(),
        pending.callerBinding(),
        pending.scopeTokenHash(),
        pending.connectScopeDigest(),
        pending.operationRepresentationVersion(),
        pending.scopeDigestVersion(),
        pending.intentDigestVersion(),
        pending.intentDigest(),
        pending.entitlementAuthorityAvailability(),
        pending.allowPublicJoin(),
        pending.entitlementVersion(),
        pending.requestDigestVersion(),
        pending.requestDigest(),
        pending.lastAttemptAuthorityAvailability(),
        pending.lastAttemptFailureCode(),
        pending.callerBoundAuthorityInvalidated(),
        "COMMITTED",
        "JOINED",
        MEMBERSHIP_ROW_ID,
        2L,
        1L,
        pending.reconciliationAttemptCount(),
        pending.lastReconciliationAttemptAt(),
        pending.lastReconciliationAttemptReason(),
        pending.nextReconciliationAttemptAt(),
        pending.scopeEvidence());
  }

  private static CanonicalConnectScopeEvidence scopeEvidence() {
    return new CanonicalConnectScopeEvidence(
        SHA,
        ACCOUNT_ROW_ID,
        "PUBLIC_PRODUCTION",
        ACCOUNT_UUID,
        TENANT_UUID,
        UUID.fromString("998a33ef-23d6-4dc6-8d5d-6fd53de34d67"),
        "tenant-fixture",
        WORLD_SLUG,
        REALM_SLUG,
        UUID.fromString("ba2c9d44-dfc0-4437-a13a-5f263f131f42"),
        "SHARED",
        UUID.fromString("88a13821-1569-43a6-9eba-dbecf93833c1"),
        7L,
        4L,
        "2026-10-03T00:00:00Z",
        "2026-10-03T01:00:00Z",
        2,
        SHA,
        TENANT_PROVENANCE);
  }

  private static AccountTenantMembership activeMembership() {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn(ACCOUNT_ROW_ID);
    when(account.getAccountUuid()).thenReturn(ACCOUNT_UUID);
    AccountTenantMembership membership = mock(AccountTenantMembership.class);
    when(membership.getId()).thenReturn(MEMBERSHIP_ROW_ID);
    when(membership.getAccount()).thenReturn(account);
    when(membership.getTenantId()).thenReturn(null);
    when(membership.getTenantUuid()).thenReturn(TENANT_UUID);
    when(membership.getTenantProvenanceKind())
        .thenReturn(TenantProvenanceKind.FRESH_GAME_DESIGN.name());
    when(membership.getTenantSourceOperationId()).thenReturn(TENANT_SOURCE_OPERATION);
    when(membership.getTenantProvenanceDigest()).thenReturn(SHA);
    when(membership.isGameplayAdmissionAllowed()).thenReturn(true);
    when(membership.getLifecycleState()).thenReturn("ACTIVE");
    when(membership.getMembershipVersion()).thenReturn(2L);
    when(membership.getMembershipAuthorityGeneration()).thenReturn(1L);
    when(membership.getAuthorityProvenance()).thenReturn("EXPLICIT_JOIN");
    return membership;
  }

  private static RoleSnapshot roleSnapshot() {
    return new RoleSnapshot(
        ACCOUNT_ROW_ID,
        null,
        MEMBERSHIP_ROW_ID,
        2L,
        List.of("player"),
        ACCOUNT_UUID,
        TENANT_UUID,
        TENANT_PROVENANCE);
  }

  private static CanonicalMembershipTransitionReceipt transitionReceipt() {
    String streamKey =
        MembershipTransitionReceiptDigest.receiptStreamKeyV2(ACCOUNT_UUID, TENANT_UUID);
    return new CanonicalMembershipTransitionReceipt(
        streamKey,
        1L,
        MembershipTransitionReceiptDigest.receiptIdForRequestV2(REQUEST_ID),
        SHA,
        MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
        "MEMBERSHIP_JOINED",
        REQUEST_ID,
        ACCOUNT_UUID,
        TENANT_UUID,
        "ACTIVE",
        true,
        Map.of(TENANT_UUID.toString(), "2"),
        1L,
        "EXPLICIT_JOIN",
        TenantProvenanceKind.FRESH_GAME_DESIGN.name(),
        TENANT_SOURCE_OPERATION,
        SHA);
  }

  private static AccountAuditEnvelope auditEnvelope(CanonicalJoinOperationEvidence operation) {
    var scope = operation.scopeEvidence();
    String payload =
        AccountAuditOutboxRepository.canonicalJoinPayload(
            scope.accountUuid(),
            scope.tenantUuid(),
            scope.worldSlug(),
            scope.realmSlug(),
            Map.of(scope.tenantUuid().toString(), "2"),
            operation.requestId());
    return new AccountAuditEnvelope(
        UUID.nameUUIDFromBytes(
            ("account-join-audit/v1:" + operation.requestId()).getBytes(StandardCharsets.UTF_8)),
        "tenant",
        AccountAuditTenantIdentity.canonicalTenantV2(scope.tenantUuid().toString()),
        "account-service",
        "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
        NOW.minusSeconds(60),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static final class Fixture {
    private final AccountJoinOperationRepository operations =
        mock(AccountJoinOperationRepository.class);
    private final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    private final AccountTenantMembershipRoleSnapshotRepository roleSnapshots =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    private final AccountMembershipTransitionReceiptRepository receipts =
        mock(AccountMembershipTransitionReceiptRepository.class);
    private final AccountAuditOutboxRepository audit = mock(AccountAuditOutboxRepository.class);
    private final AccountMembershipAuthorityEventProducer eventProducer =
        mock(AccountMembershipAuthorityEventProducer.class);
    private final AccountCanonicalJoinReconciliationService service;

    private Fixture() {
      PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
      when(transactionManager.getTransaction(any(TransactionDefinition.class)))
          .thenAnswer(invocation -> new SimpleTransactionStatus());
      service =
          new AccountCanonicalJoinReconciliationService(
              operations,
              memberships,
              roleSnapshots,
              receipts,
              audit,
              eventProducer,
              transactionManager,
              10,
              3,
              5_000);
    }

    private UUID auditEventId() {
      return UUID.nameUUIDFromBytes(
          ("account-join-audit/v1:" + REQUEST_ID).getBytes(StandardCharsets.UTF_8));
    }
  }
}
