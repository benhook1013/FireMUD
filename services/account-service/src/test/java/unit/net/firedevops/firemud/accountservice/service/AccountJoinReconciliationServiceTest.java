package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.ConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.JoinOperation;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository.JoinMembershipProof;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountJoinReconciliationServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
  private static final Instant EVALUATED_AT = NOW.minusSeconds(60);
  private static final Instant EXPIRED_AT = NOW.minusSeconds(30);
  private static final long ACCOUNT_ID = 101L;
  private static final long TENANT_ID = 7L;
  private static final long MEMBERSHIP_ID = 501L;
  private static final long MEMBERSHIP_VERSION = 4L;
  private static final long MEMBERSHIP_AUTHORITY_GENERATION = 3L;
  private static final String REQUEST_ID = "join-request-1";
  private static final String CONNECT_SCOPE_ID = "retained-connect-scope-token";
  private static final String CALLER_BINDING = "game-session:instance-a";
  private static final String WORLD_SLUG = "demo-world";
  private static final String REALM_SLUG = "production";
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");

  @Test
  void matchingRoleSnapshotCommitsEvenWhenScopeExpiredAndAuditOccurredAfterExpiry() {
    Fixture fixture = fixture(5);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 5);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.of(joinAuditEnvelope(WORLD_SLUG, TENANT_ID, correctPayloadDigest())));

    fixture.service.reconcileDueOperations(NOW);

    InOrder lockOrder = inOrder(fixture.joinOperations);
    lockOrder.verify(fixture.joinOperations).lockAccount(ACCOUNT_ID);
    lockOrder.verify(fixture.joinOperations).findForUpdate(REQUEST_ID);
    verify(fixture.joinOperations)
        .finish(
            REQUEST_ID,
            "COMMITTED",
            "JOINED",
            MEMBERSHIP_ID,
            MEMBERSHIP_VERSION,
            MEMBERSHIP_AUTHORITY_GENERATION);
    verify(fixture.joinOperations, never())
        .recordReconciliationAttempt(anyString(), anyInt(), anyInt(), any(), anyString(), any());
    verify(fixture.transitionReceipts).findLatestReceipt(ACCOUNT_ID, TENANT_ID);
    verify(fixture.roleSnapshots)
        .findForUpdate(ACCOUNT_ID, TENANT_ID, MEMBERSHIP_ID, MEMBERSHIP_VERSION);
    verify(fixture.auditOutbox).findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID);
    assertThat(reconciliationCounter(fixture, "committed")).isEqualTo(1);
  }

  @Test
  void priorMembershipHistoryCommitsEventFreeAlreadyActiveOutcome() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    stubReceiptFor(fixture, "prior-join-request", MEMBERSHIP_ID);
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.empty());

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .finish(
            REQUEST_ID,
            "COMMITTED",
            "ALREADY_ACTIVE",
            MEMBERSHIP_ID,
            MEMBERSHIP_VERSION,
            MEMBERSHIP_AUTHORITY_GENERATION);
    verify(fixture.transitionReceipts).findLatestReceipt(ACCOUNT_ID, TENANT_ID);
    verify(fixture.auditOutbox).findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID);
    verify(fixture.auditOutbox, never())
        .append(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  void mismatchedPriorMembershipReceiptStaysPending() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    stubReceiptFor(fixture, "prior-join-request", MEMBERSHIP_ID + 1L);
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.empty());
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_TRANSITION_RECEIPT_MISMATCH", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_TRANSITION_RECEIPT_MISMATCH", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void missingTransitionReceiptDoesNotInferJoinedFromMembershipAndAuditAlone() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.transitionReceipts.findLatestReceipt(ACCOUNT_ID, TENANT_ID))
        .thenReturn(Optional.empty());
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.of(joinAuditEnvelope(WORLD_SLUG, TENANT_ID, correctPayloadDigest())));
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_TRANSITION_RECEIPT_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_TRANSITION_RECEIPT_ABSENT", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    verifyNoInteractions(fixture.auditOutbox);
  }

  @Test
  void missingMembershipAfterScopeExpiryStaysPendingAndRecordsBackoffReason() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    when(fixture.memberships.findJoinProofForUpdate(ACCOUNT_ID, TENANT_ID))
        .thenReturn(Optional.empty());
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_EVIDENCE_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_EVIDENCE_ABSENT", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    verifyNoInteractions(fixture.auditOutbox);
    assertThat(reconciliationCounter(fixture, "unresolved")).isEqualTo(1);
  }

  @Test
  void missingRoleSnapshotStaysPendingAndDoesNotInferRoles() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.roleSnapshots.findForUpdate(
            ACCOUNT_ID, TENANT_ID, MEMBERSHIP_ID, MEMBERSHIP_VERSION))
        .thenReturn(Optional.empty());
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_ROLE_SNAPSHOT_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_ROLE_SNAPSHOT_ABSENT", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    verify(fixture.transitionReceipts, never()).findLatestReceipt(ACCOUNT_ID, TENANT_ID);
    verify(fixture.auditOutbox, never()).findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID);
  }

  @Test
  void mismatchedRoleSnapshotStaysPendingAndDoesNotInferRoles() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.roleSnapshots.findForUpdate(
            ACCOUNT_ID, TENANT_ID, MEMBERSHIP_ID, MEMBERSHIP_VERSION))
        .thenReturn(
            Optional.of(
                new RoleSnapshot(
                    ACCOUNT_ID,
                    TENANT_ID,
                    MEMBERSHIP_ID,
                    MEMBERSHIP_VERSION + 1,
                    List.of("PLAYER"))));
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_ROLE_SNAPSHOT_MISMATCH", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_ROLE_SNAPSHOT_MISMATCH", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    verify(fixture.transitionReceipts, never()).findLatestReceipt(ACCOUNT_ID, TENANT_ID);
    verify(fixture.auditOutbox, never()).findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID);
  }

  @Test
  void publicJoinSnapshotWithoutPlayerStaysPending() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.roleSnapshots.findForUpdate(
            ACCOUNT_ID, TENANT_ID, MEMBERSHIP_ID, MEMBERSHIP_VERSION))
        .thenReturn(
            Optional.of(
                new RoleSnapshot(
                    ACCOUNT_ID,
                    TENANT_ID,
                    MEMBERSHIP_ID,
                    MEMBERSHIP_VERSION,
                    List.of("designer"))));
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_ROLE_SNAPSHOT_MISMATCH", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "MEMBERSHIP_ROLE_SNAPSHOT_MISMATCH", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void absentJoinAuditStaysPendingAndLogsAttemptLimitMetric() {
    Fixture fixture = fixture(1);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 1);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.empty());
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 1, NOW, "JOIN_AUDIT_ENVELOPE_ABSENT", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 1, NOW, "JOIN_AUDIT_ENVELOPE_ABSENT", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    assertThat(reconciliationCounter(fixture, "max_attempts_reached")).isEqualTo(1);
  }

  @Test
  void wrongPayloadDigestStaysPending() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.of(joinAuditEnvelope(WORLD_SLUG, TENANT_ID, sha256("wrong"))));
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "JOIN_AUDIT_ENVELOPE_UNCLEAR", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "JOIN_AUDIT_ENVELOPE_UNCLEAR", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void wrongAuditPayloadTargetStaysPendingEvenWithValidDigest() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    String mismatchedPayload = joinAuditEnvelope("other-world", TENANT_ID, "unused").payload();
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(
            Optional.of(
                joinAuditEnvelope(
                    "other-world", TENANT_ID, AccountAuditDigest.ofPayload(mismatchedPayload))));
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "JOIN_AUDIT_ENVELOPE_UNCLEAR", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "JOIN_AUDIT_ENVELOPE_UNCLEAR", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void wrongRetainedTargetStaysPending() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    stubDue(fixture, pending, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PRIVATE", TENANT_ID, WORLD_SLUG)));
    when(fixture.joinOperations.recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "JOIN_SCOPE_EVIDENCE_MISMATCH", NOW.plusMillis(5_000)))
        .thenReturn(true);

    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .recordReconciliationAttempt(
            REQUEST_ID, 0, 3, NOW, "JOIN_SCOPE_EVIDENCE_MISMATCH", NOW.plusMillis(5_000));
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    verifyNoInteractions(fixture.memberships, fixture.transitionReceipts, fixture.auditOutbox);
  }

  @Test
  void repeatedRunDoesNotFinishSameJoinTwice() {
    Fixture fixture = fixture(3);
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    when(fixture.joinOperations.findDuePendingReconciliation(NOW, 10, 3))
        .thenReturn(List.of(pending), List.of());
    when(fixture.joinOperations.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(fixture.connectScopes.findEvidenceByTokenHash(pending.scopeTokenHash()))
        .thenReturn(Optional.of(scopeEvidence("PUBLIC_PRODUCTION", TENANT_ID, WORLD_SLUG)));
    stubActiveMembership(fixture);
    when(fixture.auditOutbox.findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID))
        .thenReturn(Optional.of(joinAuditEnvelope(WORLD_SLUG, TENANT_ID, correctPayloadDigest())));

    fixture.service.reconcileDueOperations(NOW);
    fixture.service.reconcileDueOperations(NOW);

    verify(fixture.joinOperations)
        .finish(
            REQUEST_ID,
            "COMMITTED",
            "JOINED",
            MEMBERSHIP_ID,
            MEMBERSHIP_VERSION,
            MEMBERSHIP_AUTHORITY_GENERATION);
    verify(fixture.auditOutbox).findJoinEnvelopeForUpdate(joinAuditEventId(), TENANT_ID);
    verify(fixture.auditOutbox, never())
        .append(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  void concurrentCallerTerminalizationIsSkippedAfterAccountAndOperationLocks() {
    Fixture fixture = fixture(3);
    JoinOperation pendingSnapshot = pendingOperation(0, NOW.minusSeconds(1));
    JoinOperation terminalOperation = terminalOperation();
    stubDue(fixture, pendingSnapshot, 3);
    when(fixture.joinOperations.findForUpdate(REQUEST_ID))
        .thenReturn(Optional.of(terminalOperation));

    fixture.service.reconcileDueOperations(NOW);

    InOrder lockOrder = inOrder(fixture.joinOperations);
    lockOrder.verify(fixture.joinOperations).lockAccount(ACCOUNT_ID);
    lockOrder.verify(fixture.joinOperations).findForUpdate(REQUEST_ID);
    verify(fixture.joinOperations, never())
        .finish(eq(REQUEST_ID), anyString(), anyString(), any(), any(), any());
    verify(fixture.joinOperations, never())
        .recordReconciliationAttempt(anyString(), anyInt(), anyInt(), any(), anyString(), any());
    verifyNoInteractions(
        fixture.connectScopes,
        fixture.memberships,
        fixture.transitionReceipts,
        fixture.auditOutbox);
  }

  private static Fixture fixture(int maxAttempts) {
    AccountJoinOperationRepository joinOperations = mock(AccountJoinOperationRepository.class);
    AccountConnectScopeRepository connectScopes = mock(AccountConnectScopeRepository.class);
    AccountTenantMembershipRepository memberships = mock(AccountTenantMembershipRepository.class);
    AccountTenantMembershipRoleSnapshotRepository roleSnapshots =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    AccountMembershipTransitionReceiptRepository transitionReceipts =
        mock(AccountMembershipTransitionReceiptRepository.class);
    AccountAuditOutboxRepository auditOutbox = mock(AccountAuditOutboxRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AccountJoinReconciliationService service =
        new AccountJoinReconciliationService(
            joinOperations,
            connectScopes,
            memberships,
            roleSnapshots,
            transitionReceipts,
            auditOutbox,
            meterRegistry,
            transactionManager,
            10,
            maxAttempts,
            5_000);
    return new Fixture(
        service,
        joinOperations,
        connectScopes,
        memberships,
        roleSnapshots,
        transitionReceipts,
        auditOutbox,
        meterRegistry);
  }

  private static void stubActiveMembership(Fixture fixture) {
    when(fixture.memberships.findJoinProofForUpdate(ACCOUNT_ID, TENANT_ID))
        .thenReturn(Optional.of(activeJoinMembership()));
    when(fixture.roleSnapshots.findForUpdate(
            ACCOUNT_ID, TENANT_ID, MEMBERSHIP_ID, MEMBERSHIP_VERSION))
        .thenReturn(Optional.of(activeRoleSnapshot()));
    stubReceiptFor(fixture, REQUEST_ID, MEMBERSHIP_ID);
  }

  private static RoleSnapshot activeRoleSnapshot() {
    return new RoleSnapshot(
        ACCOUNT_ID, TENANT_ID, MEMBERSHIP_ID, MEMBERSHIP_VERSION, List.of("player"));
  }

  private static void stubReceiptFor(Fixture fixture, String requestId, long membershipId) {
    String receiptStreamKey =
        MembershipTransitionReceiptDigest.receiptStreamKey(ACCOUNT_ID, TENANT_ID);
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest(requestId);
    String receiptDigest =
        MembershipTransitionReceiptDigest.transitionDigest(
            receiptStreamKey,
            1L,
            receiptId,
            "MEMBERSHIP_JOINED",
            requestId,
            ACCOUNT_ID,
            TENANT_ID,
            membershipId,
            "ACTIVE",
            true,
            MEMBERSHIP_VERSION,
            MEMBERSHIP_AUTHORITY_GENERATION,
            "EXPLICIT_JOIN");
    when(fixture.transitionReceipts.findLatestReceipt(ACCOUNT_ID, TENANT_ID))
        .thenReturn(
            Optional.of(
                new MembershipTransitionReceipt(
                    receiptStreamKey,
                    1L,
                    receiptId,
                    receiptDigest,
                    MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                    "MEMBERSHIP_JOINED",
                    requestId,
                    membershipId)));
  }

  private static void stubDue(Fixture fixture, JoinOperation operation, int maxAttempts) {
    when(fixture.joinOperations.findDuePendingReconciliation(NOW, 10, maxAttempts))
        .thenReturn(List.of(operation));
  }

  private static JoinOperation pendingOperation(int attemptCount, Instant nextAttemptAt) {
    VerifiedJoinScope scope = verifiedScope();
    return new JoinOperation(
        REQUEST_ID,
        ACCOUNT_ID,
        TENANT_ID,
        REALM_ID,
        WORLD_SLUG,
        REALM_SLUG,
        "namespace-prod",
        "SHARED",
        9001L,
        12L,
        8L,
        CALLER_BINDING,
        AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID),
        scope.snapshotDigest(),
        "AVAILABLE",
        true,
        42L,
        1,
        AccountJoinDigest.request(scope, CALLER_BINDING, true, 42L),
        "PENDING",
        null,
        null,
        null,
        null,
        1,
        AccountJoinDigest.intent(REQUEST_ID, scope, CALLER_BINDING),
        null,
        "AVAILABLE",
        attemptCount,
        attemptCount == 0 ? null : NOW.minusSeconds(1),
        attemptCount == 0 ? null : "PREVIOUS_RECONCILIATION_ATTEMPT",
        nextAttemptAt);
  }

  private static JoinOperation terminalOperation() {
    JoinOperation pending = pendingOperation(0, NOW.minusSeconds(1));
    return new JoinOperation(
        pending.requestId(),
        pending.accountId(),
        pending.tenantId(),
        pending.realmId(),
        pending.worldSlug(),
        pending.realmSlug(),
        pending.playableStateNamespaceId(),
        pending.playableStateScope(),
        pending.gameInstanceId(),
        pending.catalogRevision(),
        pending.pointerVersion(),
        pending.callerBinding(),
        pending.scopeTokenHash(),
        pending.connectScopeDigest(),
        pending.entitlementAuthorityAvailability(),
        pending.allowPublicJoin(),
        pending.entitlementVersion(),
        pending.requestDigestVersion(),
        pending.requestDigest(),
        "COMMITTED",
        "JOINED",
        MEMBERSHIP_ID,
        MEMBERSHIP_VERSION,
        MEMBERSHIP_AUTHORITY_GENERATION,
        pending.intentDigestVersion(),
        pending.intentDigest(),
        null,
        "AVAILABLE",
        pending.reconciliationAttemptCount(),
        pending.lastReconciliationAttemptAt(),
        pending.lastReconciliationAttemptReason(),
        pending.nextReconciliationAttemptAt());
  }

  private static VerifiedJoinScope verifiedScope() {
    VerifiedJoinScope unsigned =
        new VerifiedJoinScope(
            CONNECT_SCOPE_ID,
            ACCOUNT_ID,
            TENANT_ID,
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            "namespace-prod",
            "SHARED",
            9001L,
            12L,
            8L,
            EVALUATED_AT.toString(),
            EXPIRED_AT.toString(),
            "unused");
    return new VerifiedJoinScope(
        unsigned.connectScopeId(),
        unsigned.accountId(),
        unsigned.tenantId(),
        unsigned.realmId(),
        unsigned.worldSlug(),
        unsigned.realmSlug(),
        unsigned.playableStateNamespaceId(),
        unsigned.playableStateScope(),
        unsigned.gameInstanceId(),
        unsigned.catalogRevision(),
        unsigned.pointerVersion(),
        unsigned.evaluatedAt(),
        unsigned.connectScopeExpiresAt(),
        AccountJoinDigest.scope(unsigned));
  }

  private static ConnectScopeEvidence scopeEvidence(
      String targetClass, long tenantId, String worldSlug) {
    VerifiedJoinScope scope = verifiedScope();
    return new ConnectScopeEvidence(
        AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID),
        ACCOUNT_ID,
        targetClass,
        tenantId,
        REALM_ID,
        worldSlug,
        REALM_SLUG,
        "namespace-prod",
        "SHARED",
        9001L,
        12L,
        8L,
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt(),
        scope.snapshotDigest());
  }

  private static JoinMembershipProof activeJoinMembership() {
    return new JoinMembershipProof(
        MEMBERSHIP_ID,
        ACCOUNT_ID,
        TENANT_ID,
        true,
        "ACTIVE",
        MEMBERSHIP_VERSION,
        MEMBERSHIP_AUTHORITY_GENERATION,
        "EXPLICIT_JOIN");
  }

  private static AccountAuditEnvelope joinAuditEnvelope(
      String payloadWorldSlug, long payloadTenantId, String digest) {
    String payload =
        "{\"accountId\":"
            + ACCOUNT_ID
            + ",\"tenantId\":"
            + payloadTenantId
            + ",\"worldSlug\":\""
            + payloadWorldSlug
            + "\",\"realmSlug\":\""
            + REALM_SLUG
            + "\",\"membershipVersion\":"
            + MEMBERSHIP_VERSION
            + ",\"requestId\":\""
            + REQUEST_ID
            + "\"}";
    return new AccountAuditEnvelope(
        joinAuditEventId(),
        "tenant",
        TENANT_ID,
        "account-service",
        "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
        NOW.minusSeconds(10),
        1,
        1,
        digest,
        payload);
  }

  private static UUID joinAuditEventId() {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + REQUEST_ID).getBytes(StandardCharsets.UTF_8));
  }

  private static String correctPayloadDigest() {
    return AccountAuditDigest.ofPayload(
        joinAuditEnvelope(WORLD_SLUG, TENANT_ID, "unused").payload());
  }

  private static String sha256(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static double reconciliationCounter(Fixture fixture, String result) {
    return fixture
        .meterRegistry
        .get("account.join.reconciliation.operations")
        .tag("result", result)
        .counter()
        .count();
  }

  private record Fixture(
      AccountJoinReconciliationService service,
      AccountJoinOperationRepository joinOperations,
      AccountConnectScopeRepository connectScopes,
      AccountTenantMembershipRepository memberships,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshots,
      AccountMembershipTransitionReceiptRepository transitionReceipts,
      AccountAuditOutboxRepository auditOutbox,
      SimpleMeterRegistry meterRegistry) {}
}
