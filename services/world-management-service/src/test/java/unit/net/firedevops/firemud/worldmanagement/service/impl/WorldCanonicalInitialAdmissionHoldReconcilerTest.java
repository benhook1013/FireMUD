package net.firedevops.firemud.worldmanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInitialAdmissionHoldReconcilerTest {
  private static final String NAMESPACE = "prod";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void advancesBoundedKeysetAcrossRowFailuresAndReportsReconciliationRequired() {
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var finalizer = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var first = ref(1, 1);
    var second = ref(2, 2);
    var third = ref(3, 3);
    var fourth = ref(4, 4);
    var firstIdentity = identity(first.holdId());
    var terminalIdentity = identity(fourth.holdId());
    when(repository.findReconciliationCandidates(NAMESPACE, null, 2))
        .thenReturn(List.of(first, second));
    when(repository.readReconciliationCandidate(NAMESPACE, first.holdId()))
        .thenThrow(
            new WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldIdentityException(
                "malformed first row"));
    when(repository.readReconciliationCandidate(NAMESPACE, second.holdId()))
        .thenReturn(
            Optional.of(
                candidate(
                    identity(second.holdId()),
                    WorldCanonicalInitialAdmissionHoldState.HoldStatus.RECONCILIATION_REQUIRED)));

    when(repository.findReconciliationCandidates(NAMESPACE, second.cursor(), 2))
        .thenReturn(List.of(third, fourth));
    when(repository.readReconciliationCandidate(NAMESPACE, third.holdId()))
        .thenReturn(Optional.empty());
    when(repository.readReconciliationCandidate(NAMESPACE, fourth.holdId()))
        .thenReturn(
            Optional.of(
                candidate(
                    terminalIdentity, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING)));
    var committed = committed(terminalIdentity);
    when(finalizer.observeAndFinalizeHold(terminalIdentity)).thenReturn(Optional.of(committed));
    when(repository.findReconciliationCandidates(NAMESPACE, fourth.cursor(), 2))
        .thenReturn(List.of());

    var reconciler =
        new WorldCanonicalInitialAdmissionHoldReconciler(repository, finalizer, NAMESPACE);

    var firstPage = reconciler.reconcileNextPage(2);
    assertThat(firstPage.scanned()).isEqualTo(2);
    assertThat(firstPage.unresolvedHoldIds()).containsExactly(first.holdId());
    assertThat(firstPage.reconciliationRequiredHoldIds()).containsExactly(second.holdId());
    assertThat(firstPage.stillPending()).isZero();
    assertThat(firstPage.endOfPass()).isFalse();
    verify(finalizer, never()).observeAndFinalizeHold(firstIdentity);

    var secondPage = reconciler.reconcileNextPage(2);
    assertThat(secondPage.scanned()).isEqualTo(2);
    assertThat(secondPage.terminalized()).isEqualTo(1);
    assertThat(secondPage.unresolvedHoldIds()).isEmpty();
    assertThat(secondPage.endOfPass()).isFalse();
    verify(finalizer).observeAndFinalizeHold(terminalIdentity);

    var end = reconciler.reconcileNextPage(2);
    assertThat(end.scanned()).isZero();
    assertThat(end.endOfPass()).isTrue();

    verify(repository).findReconciliationCandidates(NAMESPACE, null, 2);
    verify(repository).findReconciliationCandidates(NAMESPACE, second.cursor(), 2);
    verify(repository).findReconciliationCandidates(NAMESPACE, fourth.cursor(), 2);
  }

  @Test
  void pendingOwnerOutcomeIsNoOpAndUnavailableTerminalReadLeavesTheRowUnresolved() {
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var finalizer = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var pendingRef = ref(5, 5);
    var unavailableRef = ref(6, 6);
    HoldIdentity pending = identity(pendingRef.holdId());
    HoldIdentity unavailable = identity(unavailableRef.holdId());
    when(repository.findReconciliationCandidates(NAMESPACE, null, 8))
        .thenReturn(List.of(pendingRef, unavailableRef));
    when(repository.readReconciliationCandidate(NAMESPACE, pendingRef.holdId()))
        .thenReturn(
            Optional.of(
                candidate(pending, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING)));
    when(repository.readReconciliationCandidate(NAMESPACE, unavailableRef.holdId()))
        .thenReturn(
            Optional.of(
                candidate(
                    unavailable, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING)));
    when(finalizer.observeAndFinalizeHold(pending)).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("owner read unavailable"))
        .when(finalizer)
        .observeAndFinalizeHold(unavailable);

    var reconciler =
        new WorldCanonicalInitialAdmissionHoldReconciler(repository, finalizer, NAMESPACE);
    var page = reconciler.reconcileNextPage(8);

    assertThat(page.stillPending()).isEqualTo(1);
    assertThat(page.unresolvedHoldIds()).containsExactly(unavailableRef.holdId());
    verify(finalizer).observeAndFinalizeHold(pending);
    verify(finalizer).observeAndFinalizeHold(unavailable);
  }

  @Test
  void equalTimestampCursorProgressUsesDatabaseUnsignedUuidOrderingAcrossHighBitBoundary() {
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var finalizer = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    Instant updatedAt = Instant.parse("2026-10-08T12:00:00Z");
    var lower = ref("7fffffff-ffff-4fff-8fff-000000000001", updatedAt);
    var lowerLast = ref("7fffffff-ffff-4fff-8fff-000000000002", updatedAt);
    var upper = ref("80000000-0000-4000-8000-000000000001", updatedAt);
    var upperLast = ref("80000000-0000-4000-8000-000000000002", updatedAt);
    when(repository.findReconciliationCandidates(NAMESPACE, null, 2))
        .thenReturn(List.of(lower, lowerLast));
    when(repository.findReconciliationCandidates(NAMESPACE, lowerLast.cursor(), 2))
        .thenReturn(List.of(upper, upperLast));
    when(repository.findReconciliationCandidates(NAMESPACE, upperLast.cursor(), 2))
        .thenReturn(List.of());
    for (var ref : List.of(lower, lowerLast, upper, upperLast)) {
      when(repository.readReconciliationCandidate(NAMESPACE, ref.holdId()))
          .thenReturn(Optional.empty());
    }
    var reconciler =
        new WorldCanonicalInitialAdmissionHoldReconciler(repository, finalizer, NAMESPACE);

    assertThat(reconciler.reconcileNextPage(2).scanned()).isEqualTo(2);
    assertThat(reconciler.reconcileNextPage(2).scanned()).isEqualTo(2);
    assertThat(reconciler.reconcileNextPage(2).endOfPass()).isTrue();

    verify(repository).findReconciliationCandidates(NAMESPACE, null, 2);
    verify(repository).findReconciliationCandidates(NAMESPACE, lowerLast.cursor(), 2);
    verify(repository).findReconciliationCandidates(NAMESPACE, upperLast.cursor(), 2);
  }

  @Test
  void refusesAmbientTransactionAndSynchronizationBeforeTheCandidateScan() {
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var finalizer = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var reconciler =
        new WorldCanonicalInitialAdmissionHoldReconciler(repository, finalizer, NAMESPACE);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    org.assertj.core.api.Assertions.assertThatThrownBy(reconciler::reconcileNextPage)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    org.assertj.core.api.Assertions.assertThatThrownBy(reconciler::reconcileNextPage)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    verifyNoInteractions(repository, finalizer);
  }

  private static WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidate candidate(
      HoldIdentity identity, WorldCanonicalInitialAdmissionHoldState.HoldStatus status) {
    return new WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidate(
        identity, status);
  }

  private static WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidateReference ref(
      long suffix, long second) {
    return ref(
        String.format("00000000-0000-4000-8000-%012d", suffix),
        Instant.parse("2026-10-08T12:00:00Z").plusSeconds(second));
  }

  private static WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidateReference ref(
      String holdIdText, Instant updatedAt) {
    UUID holdId = uuid(holdIdText);
    var cursor =
        new WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCursor(updatedAt, holdId);
    return new WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidateReference(
        holdId, cursor);
  }

  private static HoldIdentity identity(UUID holdId) {
    Request request =
        new Request(
            NAMESPACE,
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-" + holdId.toString().substring(24),
            "a".repeat(64),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null);
    return new HoldIdentity(request, holdId, uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof committed(HoldIdentity identity) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        1L,
        27L,
        "sha256:" + "c".repeat(64),
        false,
        Instant.parse("2026-10-08T12:10:00Z"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
