package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountCoverageEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import org.junit.jupiter.api.Test;

class CanonicalGameplayAdmissionAbortOwnerTest {
  private static final UUID TRANSITION_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final String ORIGINAL_LEASE = "canonical-original-lease-evidence";

  @Test
  void accountTimeoutRetainsTheOriginalNonAdmittingObligation() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var accountClient = mock(AccountGameplayAdmissionLeaseClient.class);
    var source = source(false);
    when(repository.readFirstNewAdmissionAbortRetrySource(TRANSITION_ID)).thenReturn(source);
    when(accountClient.abort(eq("presented-initial-gsa"), any(byte[].class), eq(TRANSITION_ID)))
        .thenThrow(new IllegalStateException("Account abort response unavailable"));

    var owner = new CanonicalGameplayAdmissionAbortOwner(repository, accountClient);
    assertThatThrownBy(() -> owner.abortFirstNew(TRANSITION_ID, "presented-initial-gsa"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account abort response unavailable");

    verify(accountClient)
        .abort(
            eq("presented-initial-gsa"),
            org.mockito.ArgumentMatchers.argThat(
                bytes ->
                    java.util.Arrays.equals(
                        ORIGINAL_LEASE.getBytes(StandardCharsets.UTF_8), bytes)),
            eq(TRANSITION_ID));
    verify(repository, never()).recordFirstNewAccountAbort(any(), any());
  }

  private static final UUID CLEANUP_ID = UUID.fromString("abababab-abab-4bab-8bab-abababababab");

  @Test
  void retainedTerminalReplayStillAuthenticatesTheCurrentCallerAndCannotCasOnFailure() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var client = mock(AccountGameplayAdmissionLeaseClient.class);
    var terminalSource = source(true);
    when(repository.readFirstNewAdmissionAbortRetrySource(TRANSITION_ID))
        .thenReturn(terminalSource);
    when(client.abort(eq("expired-or-revoked-caller"), any(byte[].class), eq(TRANSITION_ID)))
        .thenThrow(new IllegalStateException("current caller refused"));
    assertThatThrownBy(
            () ->
                new CanonicalGameplayAdmissionAbortOwner(repository, client)
                    .abortFirstNew(TRANSITION_ID, "expired-or-revoked-caller"))
        .hasMessage("current caller refused");
    verify(client).abort(eq("expired-or-revoked-caller"), any(byte[].class), eq(TRANSITION_ID));
    verify(repository, never()).recordFirstNewAccountAbort(any(), any());
  }

  @Test
  void retainedTerminalRetryReturnsTheOriginalCleanupOnlyAfterExactAccountReplay() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var client = mock(AccountGameplayAdmissionLeaseClient.class);
    var retained = source(true);
    var lease = retained.originalDecision().leaseEvidence();
    when(lease.hasSameIdentity(lease)).thenReturn(true);
    var operation =
        mock(
            net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec
                .OperationEvidence.class);
    when(operation.originalLeaseEvidence()).thenReturn(lease);
    when(operation.state())
        .thenReturn(
            net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec
                .State.ABORTED);
    when(operation.bindingDecisionId()).thenReturn(TRANSITION_ID);
    when(operation.orphanCleanupId()).thenReturn(CLEANUP_ID);
    when(repository.readFirstNewAdmissionAbortRetrySource(TRANSITION_ID)).thenReturn(retained);
    when(client.abort(any(), any(), any())).thenReturn(operation);

    var result =
        new CanonicalGameplayAdmissionAbortOwner(repository, client)
            .abortFirstNew(TRANSITION_ID, "presented-initial-gsa");

    assertThat(result.orphanCleanupId()).isEqualTo(CLEANUP_ID);
    assertThat(result.accountOperation()).isSameAs(operation);
    verify(client).abort(eq("presented-initial-gsa"), any(byte[].class), eq(TRANSITION_ID));
    verify(repository, never()).readFirstNewAdmissionAbortSource(any());
    verify(repository, never()).recordFirstNewAccountAbort(any(), any());
  }

  @Test
  void ambientTransactionOrSynchronizationRefusesBeforeAnyOwnerInteraction() {
    for (boolean synchronizationOnly : new boolean[] {false, true}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var client = mock(AccountGameplayAdmissionLeaseClient.class);
      if (synchronizationOnly) {
        org.springframework.transaction.support.TransactionSynchronizationManager
            .initSynchronization();
      } else {
        org.springframework.transaction.support.TransactionSynchronizationManager
            .setActualTransactionActive(true);
      }
      try {
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayAdmissionAbortOwner(repository, client)
                        .abortFirstNew(TRANSITION_ID, "presented-initial-gsa"))
            .hasMessage("First-NEW Account abort requires no ambient SQL scope");
        org.mockito.Mockito.verifyNoInteractions(repository, client);
      } finally {
        org.springframework.transaction.support.TransactionSynchronizationManager.clear();
      }
    }
  }

  @Test
  void retainedAbortRejectsContradictoryAccountStatesOrChangedCleanupWithoutCas() {
    for (var state :
        net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State
            .values()) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var client = mock(AccountGameplayAdmissionLeaseClient.class);
      var retained = source(true);
      var lease = retained.originalDecision().leaseEvidence();
      when(lease.hasSameIdentity(lease)).thenReturn(true);
      var operation =
          mock(
              net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec
                  .OperationEvidence.class);
      when(operation.originalLeaseEvidence()).thenReturn(lease);
      when(operation.state()).thenReturn(state);
      switch (state) {
        case ABORTED -> {
          when(operation.bindingDecisionId()).thenReturn(TRANSITION_ID);
          when(operation.orphanCleanupId()).thenReturn(UUID.randomUUID());
        }
        case COMMITTED -> {
          when(operation.bindingDecisionId()).thenReturn(TRANSITION_ID);
          when(operation.originalAckReceipt())
              .thenReturn(
                  mock(
                      net.firedevops.firemud.common.account.admission
                          .AccountGameplayAdmissionLeaseGrpcCodec.OriginalAckReceiptEvidence
                          .class));
        }
        case PENDING -> {}
      }
      when(repository.readFirstNewAdmissionAbortRetrySource(TRANSITION_ID)).thenReturn(retained);
      when(client.abort(any(), any(), any())).thenReturn(operation);
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayAdmissionAbortOwner(repository, client)
                      .abortFirstNew(TRANSITION_ID, "presented-initial-gsa"))
          .hasMessage("Account replay contradicts the retained definitive abort");
      verify(repository, never()).recordFirstNewAccountAbort(any(), any());
    }
  }

  static CanonicalGameplayAdmissionAbortSource source(boolean aborted) {
    var decision = mock(CanonicalGameplayAdmissionDecision.class);
    var lease = mock(AccountGameplayAdmissionLeaseEvidence.class);
    var identity =
        new CanonicalGameplayBindingIdentity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "SHARED",
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            23L,
            "66666666-6666-4666-8666-666666666666",
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            BigInteger.ONE,
            GameSessionAccountDelegationProfile.ISSUER,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.TEN);
    CanonicalGameplayBindingRef bindingRef = identity.bindingRef();
    CanonicalPlayableTarget target =
        new CanonicalPlayableTarget(
            "game-session-admission-test",
            "tenant",
            identity.tenantId(),
            "world",
            "World",
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            "realm",
            "Realm",
            1L,
            23L,
            identity.playableStateNamespaceId(),
            "SHARED",
            identity.gameInstanceId(),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            1L,
            1L,
            1L,
            "a".repeat(64),
            1L,
            "original-request",
            "b".repeat(64),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            "sha256:" + "d".repeat(64),
            1L,
            "sha256:" + "e".repeat(64),
            CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            false,
            Instant.parse("2026-10-01T00:00:00Z"),
            "ALLOW");
    var transition =
        new CanonicalGameplayBindingTransitionSnapshot(
            TRANSITION_ID,
            identity,
            BigInteger.ONE,
            bindingRef,
            BigInteger.ONE,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            null,
            null,
            aborted
                ? CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED
                : CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL,
            CanonicalGameplayAccountCoverageEvidence.noActiveFlow());
    var candidate =
        new CanonicalGameplayBindingInventoryEntry(
            identity,
            bindingRef,
            BigInteger.ONE,
            transition.candidateAccountIndexFence(),
            transition.issuerReservationId(),
            TRANSITION_ID,
            aborted
                ? CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED
                : CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            BigInteger.ONE);
    var reservation =
        new CanonicalIssuerPartitionReservation(
            transition.issuerReservationId(),
            identity,
            BigInteger.ONE,
            TRANSITION_ID,
            BigInteger.ZERO,
            aborted
                ? CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
                : CanonicalIssuerPartitionReservation.Lifecycle.RESERVED,
            transition.reservationFence(),
            UUID.fromString("12121212-1212-4212-8212-121212121212"),
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE);
    when(decision.bindingDecisionId()).thenReturn(TRANSITION_ID);
    when(decision.leaseEvidence()).thenReturn(lease);
    when(decision.candidate()).thenReturn(identity);
    when(decision.bindingGeneration()).thenReturn(BigInteger.ONE);
    when(decision.expectedOldBindingGeneration()).thenReturn(null);
    when(decision.targetEvidence()).thenReturn(target);
    when(decision.leaseKind())
        .thenReturn(AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING);
    when(lease.leaseKind()).thenReturn(AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING);
    when(decision.leaseEvidenceJson()).thenReturn(ORIGINAL_LEASE);
    when(lease.canonicalJson()).thenReturn(ORIGINAL_LEASE);
    return new CanonicalGameplayAdmissionAbortSource(
        transition, candidate, reservation, decision, aborted ? CLEANUP_ID : null);
  }
}
