package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InitialAdmissionBindHoldServiceImplTest {
  private static final long TENANT_ID = 42L;
  private static final long GAME_INSTANCE_ID = 101L;
  private static final long VERSION_ID = 11L;
  private static final long ACTIVE_EPOCH = 7L;
  private static final String REQUEST_ID = "initial-admission-1";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String REALM_UUID = "00000000-0000-0000-0000-000000000001";
  private static final String NAMESPACE_UUID = "00000000-0000-0000-0000-000000000002";
  private static final String HOLD_ID = "00000000-0000-0000-0000-000000000003";
  private static final String HOLD_FENCE = "00000000-0000-0000-0000-000000000004";

  private InitialAdmissionBindHoldRepository holdRepository;
  private WorldInstanceRepository worldInstanceRepository;
  private InitialAdmissionBindHoldServiceImpl service;

  @BeforeEach
  void setUp() {
    holdRepository = Mockito.mock(InitialAdmissionBindHoldRepository.class);
    worldInstanceRepository = Mockito.mock(WorldInstanceRepository.class);
    service = new InitialAdmissionBindHoldServiceImpl(holdRepository, worldInstanceRepository);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(
            TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(Optional.of(activeWorldInstance()));
  }

  @Test
  void acquireAllocatesOpaqueHoldAndExactRetryReusesItsIdentity() {
    AtomicReference<InitialAdmissionBindHold> persisted = new AtomicReference<>();
    when(holdRepository.findByTenantIdAndRequestId(TENANT_ID, REQUEST_ID))
        .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(holdRepository.hasNonterminalForRealm(TENANT_ID, REALM_UUID)).thenReturn(false);
    when(holdRepository.insertIfNoUniqueConflict(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHold hold = invocation.getArgument(0);
              persisted.set(hold);
              return Optional.of(hold);
            });

    var first = service.acquire(request());
    var retry = service.acquire(request());

    assertEquals("PENDING", first.status());
    assertNotEquals(REQUEST_ID, first.holdId());
    assertNotEquals(first.holdId(), first.holdFence());
    assertEquals(first.holdId(), retry.holdId());
    assertEquals(first.holdFence(), retry.holdFence());
    assertEquals(REALM_UUID, retry.realmUuid());
    assertEquals(NAMESPACE_UUID, retry.playableStateNamespaceUuid());
    assertEquals(17L, retry.expectedCatalogRevision());
    verify(worldInstanceRepository, Mockito.times(2))
        .findByTenantIdAndGameInstanceIdForUpdate(TENANT_ID, GAME_INSTANCE_ID);
  }

  @Test
  void acquireRejectsChangedRequestDigestForTheSameStableRequestId() {
    when(holdRepository.findByTenantIdAndRequestId(TENANT_ID, REQUEST_ID))
        .thenReturn(Optional.of(pendingHold()));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.acquire(
                    new InitialAdmissionBindHoldRequest(
                        TENANT_ID,
                        GAME_INSTANCE_ID,
                        VERSION_ID,
                        ACTIVE_EPOCH,
                        REQUEST_ID,
                        "b".repeat(64),
                        REALM_UUID,
                        NAMESPACE_UUID,
                        "SHARED",
                        true,
                        17L)));

    assertTrue(error.getMessage().startsWith("IDEMPOTENCY_CONFLICT:"));
    verify(holdRepository, never()).insertIfNoUniqueConflict(any());
  }

  @Test
  void acquireRejectsASecondNonterminalHoldForTheRealm() {
    when(holdRepository.findByTenantIdAndRequestId(TENANT_ID, REQUEST_ID))
        .thenReturn(Optional.empty());
    when(holdRepository.hasNonterminalForRealm(TENANT_ID, REALM_UUID)).thenReturn(true);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.acquire(request()));

    assertTrue(error.getMessage().startsWith("INITIAL_ADMISSION_BIND_HOLD_CONFLICT:"));
    verify(holdRepository, never()).insertIfNoUniqueConflict(any());
  }

  @Test
  void acquireRejectsAnUnsupportedPlayableStateScope() {
    InitialAdmissionBindHoldRequest invalidScopeRequest =
        new InitialAdmissionBindHoldRequest(
            TENANT_ID,
            GAME_INSTANCE_ID,
            VERSION_ID,
            ACTIVE_EPOCH,
            REQUEST_ID,
            REQUEST_DIGEST,
            REALM_UUID,
            NAMESPACE_UUID,
            "PLAYABLE_STATE",
            true,
            17L);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.acquire(invalidScopeRequest));

    assertTrue(error.getMessage().startsWith("INVALID_ARGUMENT:"));
    verify(worldInstanceRepository, never())
        .findByTenantIdAndGameInstanceIdForUpdate(TENANT_ID, GAME_INSTANCE_ID);
  }

  @Test
  void pendingOwnerReadMovesHoldToBlockingReconciliationRequired() {
    InitialAdmissionBindHold hold = pendingHold();
    when(holdRepository.findByHoldId(HOLD_ID)).thenReturn(Optional.of(hold));
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID)).thenReturn(Optional.of(hold));
    when(holdRepository.markReconciliationRequired(any(), Mockito.eq("GS_OWNER_PENDING"), any()))
        .thenAnswer(invocation -> Optional.of(withStatus(hold, "RECONCILIATION_REQUIRED", 1L)));

    var result = service.reconcileOwnerProof(HOLD_ID, ownerProof(Outcome.PENDING, false, null));

    assertEquals("RECONCILIATION_REQUIRED", result.status());
    verify(holdRepository, never())
        .recordTerminalProof(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void committedReadbackRequiresExactPointerAuditDigest() {
    InitialAdmissionBindHold hold = pendingHold();
    when(holdRepository.findByHoldId(HOLD_ID)).thenReturn(Optional.of(hold));
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID)).thenReturn(Optional.of(hold));
    when(holdRepository.markReconciliationRequired(
            any(), Mockito.eq("GS_OWNER_TERMINAL_PROOF_INCOMPLETE"), any()))
        .thenAnswer(invocation -> Optional.of(withStatus(hold, "RECONCILIATION_REQUIRED", 1L)));

    var result =
        service.reconcileOwnerProof(HOLD_ID, ownerProof(Outcome.COMMITTED, true, "c".repeat(64)));

    assertEquals("RECONCILIATION_REQUIRED", result.status());
    verify(worldInstanceRepository, never())
        .findByTenantIdAndGameInstanceIdForUpdate(TENANT_ID, GAME_INSTANCE_ID);
    verify(holdRepository, never())
        .recordTerminalProof(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void committedReadbackRequiresTheFirstPointerVersionExactly() {
    InitialAdmissionBindHold hold = pendingHold();
    when(holdRepository.findByHoldId(HOLD_ID)).thenReturn(Optional.of(hold));
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID)).thenReturn(Optional.of(hold));
    when(holdRepository.markReconciliationRequired(
            any(), Mockito.eq("GS_OWNER_TERMINAL_PROOF_INCOMPLETE"), any()))
        .thenAnswer(invocation -> Optional.of(withStatus(hold, "RECONCILIATION_REQUIRED", 1L)));

    var result =
        service.reconcileOwnerProof(
            HOLD_ID, ownerProof(Outcome.COMMITTED, true, REQUEST_DIGEST, 2L));

    assertEquals("RECONCILIATION_REQUIRED", result.status());
    verify(worldInstanceRepository, never())
        .findByTenantIdAndGameInstanceIdForUpdate(TENANT_ID, GAME_INSTANCE_ID);
    verify(holdRepository, never())
        .recordTerminalProof(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void exactCommittedPointerAndAuditReadbackTerminalizesAndReplays() {
    InitialAdmissionBindHold pending = pendingHold();
    AtomicReference<InitialAdmissionBindHold> persisted = new AtomicReference<>(pending);
    when(holdRepository.findByHoldId(HOLD_ID))
        .thenAnswer(invocation -> Optional.of(persisted.get()));
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID))
        .thenAnswer(invocation -> Optional.of(persisted.get()));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(
            TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(Optional.of(activeWorldInstance()));
    when(holdRepository.recordTerminalProof(
            any(),
            Mockito.eq("COMMITTED"),
            Mockito.eq("gs-ledger-100"),
            any(),
            Mockito.eq("audit-100"),
            Mockito.eq(1L),
            any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHold committed =
                  withTerminalProof(
                      pending,
                      ownerProof(Outcome.COMMITTED, true, REQUEST_DIGEST),
                      invocation.getArgument(3));
              persisted.set(committed);
              return Optional.of(committed);
            });

    var first =
        service.reconcileOwnerProof(HOLD_ID, ownerProof(Outcome.COMMITTED, true, REQUEST_DIGEST));
    var retry =
        service.reconcileOwnerProof(HOLD_ID, ownerProof(Outcome.COMMITTED, true, REQUEST_DIGEST));

    assertEquals("COMMITTED", first.status());
    assertEquals(first, retry);
    verify(holdRepository, Mockito.times(1))
        .recordTerminalProof(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void abortedAttemptRequiresDurableTombstoneAndPreventsFutureCommit() {
    InitialAdmissionBindHold pending = pendingHold();
    when(holdRepository.findByHoldId(HOLD_ID)).thenReturn(Optional.of(pending));
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID)).thenReturn(Optional.of(pending));
    when(holdRepository.markReconciliationRequired(
            any(), Mockito.eq("GS_OWNER_TERMINAL_PROOF_INCOMPLETE"), any()))
        .thenAnswer(invocation -> Optional.of(withStatus(pending, "RECONCILIATION_REQUIRED", 1L)));

    var result = service.reconcileOwnerProof(HOLD_ID, ownerProof(Outcome.ABORTED, false, null));

    assertEquals("RECONCILIATION_REQUIRED", result.status());
    verify(holdRepository, never())
        .recordTerminalProof(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void exactAbortedTombstoneDoesNotRequirePriorPointerAbsence() {
    InitialAdmissionBindHold pending = pendingHold();
    InitialAdmissionBindOwnerProof proof = ownerProof(Outcome.ABORTED, true, null);
    when(holdRepository.findByHoldId(HOLD_ID)).thenReturn(Optional.of(pending));
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID)).thenReturn(Optional.of(pending));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(
            TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(Optional.of(activeWorldInstance()));
    when(holdRepository.recordTerminalProof(
            any(),
            Mockito.eq("ABORTED"),
            Mockito.eq("gs-ledger-100"),
            any(),
            Mockito.isNull(),
            Mockito.isNull(),
            any()))
        .thenAnswer(
            invocation ->
                Optional.of(withTerminalProof(pending, proof, invocation.getArgument(3))));

    var result = service.reconcileOwnerProof(HOLD_ID, proof);

    assertEquals("ABORTED", result.status());
    verify(holdRepository)
        .recordTerminalProof(
            any(), Mockito.eq("ABORTED"), any(), any(), Mockito.isNull(), Mockito.isNull(), any());
  }

  @Test
  void repeatedIdenticalOwnerErrorsStillTouchAndRotateBlockedHold() {
    InitialAdmissionBindHold blocked = withStatus(pendingHold(), "RECONCILIATION_REQUIRED", 1L);
    when(holdRepository.findByHoldId(HOLD_ID)).thenReturn(Optional.of(blocked));
    when(holdRepository.markReconciliationRequired(any(), Mockito.eq("GS_OWNER_PENDING"), any()))
        .thenAnswer(
            invocation ->
                Optional.of(withStatus(invocation.getArgument(0), "RECONCILIATION_REQUIRED", 2L)));

    var result = service.reconcileOwnerProof(HOLD_ID, ownerProof(Outcome.PENDING, false, null));

    assertEquals("RECONCILIATION_REQUIRED", result.status());
    assertEquals(HOLD_ID, result.holdId());
    verify(holdRepository).markReconciliationRequired(any(), Mockito.eq("GS_OWNER_PENDING"), any());
  }

  @Test
  void lostReconciliationCompareAndSetRaisesStaleError() {
    when(holdRepository.findByHoldIdForUpdate(HOLD_ID)).thenReturn(Optional.of(pendingHold()));
    when(holdRepository.markReconciliationRequired(any(), Mockito.eq("GS_OWNER_PENDING"), any()))
        .thenReturn(Optional.empty());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.requireReconciliation(HOLD_ID, "GS_OWNER_PENDING"));

    assertTrue(error.getMessage().startsWith("INITIAL_ADMISSION_BIND_HOLD_STALE:"));
  }

  private InitialAdmissionBindHoldRequest request() {
    return new InitialAdmissionBindHoldRequest(
        TENANT_ID,
        GAME_INSTANCE_ID,
        VERSION_ID,
        ACTIVE_EPOCH,
        REQUEST_ID,
        REQUEST_DIGEST,
        REALM_UUID,
        NAMESPACE_UUID,
        "SHARED",
        true,
        17L);
  }

  private WorldInstance activeWorldInstance() {
    WorldInstance worldInstance = new WorldInstance();
    worldInstance.setTenantId(TENANT_ID);
    worldInstance.setGameInstanceId(GAME_INSTANCE_ID);
    worldInstance.setVersionId(VERSION_ID);
    worldInstance.setLifecycleEpoch(ACTIVE_EPOCH);
    worldInstance.setStatus("ACTIVE");
    return worldInstance;
  }

  private InitialAdmissionBindHold pendingHold() {
    Instant now = Instant.parse("2026-09-01T00:00:00Z");
    return new InitialAdmissionBindHold(
        HOLD_ID,
        HOLD_FENCE,
        TENANT_ID,
        REALM_UUID,
        NAMESPACE_UUID,
        "SHARED",
        GAME_INSTANCE_ID,
        VERSION_ID,
        ACTIVE_EPOCH,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        17L,
        "PENDING",
        now.plusSeconds(300),
        null,
        null,
        null,
        null,
        null,
        now,
        now,
        null,
        0L);
  }

  private InitialAdmissionBindOwnerProof ownerProof(
      Outcome outcome, boolean futureCommitPrevented, String auditDigest) {
    return ownerProof(
        outcome, futureCommitPrevented, auditDigest, outcome == Outcome.COMMITTED ? 1L : 0L);
  }

  private InitialAdmissionBindOwnerProof ownerProof(
      Outcome outcome, boolean futureCommitPrevented, String auditDigest, long pointerVersion) {
    return new InitialAdmissionBindOwnerProof(
        outcome,
        HOLD_ID,
        HOLD_FENCE,
        TENANT_ID,
        REALM_UUID,
        NAMESPACE_UUID,
        "SHARED",
        GAME_INSTANCE_ID,
        VERSION_ID,
        ACTIVE_EPOCH,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        17L,
        "gs-ledger-100",
        outcome == Outcome.COMMITTED ? "audit-100" : null,
        pointerVersion,
        auditDigest,
        futureCommitPrevented);
  }

  private InitialAdmissionBindHold withStatus(
      InitialAdmissionBindHold hold, String status, long rowVersion) {
    return new InitialAdmissionBindHold(
        hold.holdId(),
        hold.holdFence(),
        hold.tenantId(),
        hold.realmUuid(),
        hold.playableStateNamespaceUuid(),
        hold.playableStateScope(),
        hold.gameInstanceId(),
        hold.versionId(),
        hold.activeLifecycleEpoch(),
        hold.initialAdmissionRequestId(),
        hold.requestDigest(),
        hold.expectedNoPriorPointer(),
        hold.expectedCatalogRevision(),
        status,
        hold.diagnosticExpiresAt(),
        hold.ownerProofId(),
        hold.ownerProofDigest(),
        hold.ownerPointerAuditId(),
        hold.ownerPointerVersion(),
        "GS_OWNER_PENDING",
        hold.createdAt(),
        hold.updatedAt(),
        hold.terminalAt(),
        rowVersion);
  }

  private InitialAdmissionBindHold withTerminalProof(
      InitialAdmissionBindHold hold,
      InitialAdmissionBindOwnerProof proof,
      String ownerProofDigest) {
    return new InitialAdmissionBindHold(
        hold.holdId(),
        hold.holdFence(),
        hold.tenantId(),
        hold.realmUuid(),
        hold.playableStateNamespaceUuid(),
        hold.playableStateScope(),
        hold.gameInstanceId(),
        hold.versionId(),
        hold.activeLifecycleEpoch(),
        hold.initialAdmissionRequestId(),
        hold.requestDigest(),
        hold.expectedNoPriorPointer(),
        hold.expectedCatalogRevision(),
        proof.outcome().name(),
        hold.diagnosticExpiresAt(),
        proof.ownerProofId(),
        ownerProofDigest,
        proof.pointerAuditId(),
        proof.pointerVersion() == 0L ? null : proof.pointerVersion(),
        null,
        hold.createdAt(),
        hold.updatedAt(),
        Instant.parse("2026-09-01T00:00:01Z"),
        hold.rowVersion() + 1L);
  }
}
