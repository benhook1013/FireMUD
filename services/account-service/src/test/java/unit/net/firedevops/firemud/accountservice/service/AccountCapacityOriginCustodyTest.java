package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountCapacityOriginDecisionRepository;
import net.firedevops.firemud.accountservice.service.AccountCapacityOriginRecovery;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution.Status;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled contract/continuation proof only; not physical concurrent transaction proof. */
class AccountCapacityOriginCustodyTest {
  @AfterEach
  void clear() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void immutableDenialEncodingReplaysAndBindsExactReceipt() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var id = UUID.randomUUID();
    var first = new AccountCapacityOriginResolution(request, Status.NO_ACCEPT_RETAINED, null, id);
    var replay = new AccountCapacityOriginResolution(request, Status.NO_ACCEPT_RETAINED, null, id);
    assertThat(replay.canonicalBytes()).isEqualTo(first.canonicalBytes());
    assertThat(replay.digest()).isEqualTo(first.digest());
    byte[] copy = first.canonicalBytes();
    copy[0] ^= 1;
    assertThat(first.canonicalBytes()).isNotEqualTo(copy);
    assertThat(
            new AccountCapacityOriginResolution(
                    request, Status.NO_ACCEPT_RETAINED, null, UUID.randomUUID())
                .digest())
        .isNotEqualTo(first.digest());
  }

  @Test
  void unknownCannotCarryFabricatedDenialAndDenialRequiresPositiveReceipt() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    assertThatThrownBy(
            () ->
                new AccountCapacityOriginResolution(
                    request, Status.UNKNOWN, null, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountCapacityOriginResolution(request, Status.NO_ACCEPT_RETAINED, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingDecisionReadDoesNotCreateNoAccept() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    DSLContext dsl = mock(DSLContext.class);
    assertThat(
            new AccountCapacityOriginDecisionRepository(dsl)
                .find(CapacityAdmissionTestFixture.playerRequest()))
        .isNull();
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void retainedRefusalReplaysSameIdAndRejectsCommitDecision() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var request = CapacityAdmissionTestFixture.playerRequest();
    DSLContext dsl = mock(DSLContext.class);
    Record row = mock(Record.class);
    UUID denial = UUID.randomUUID();
    when(row.get("admission_attempt_id", UUID.class)).thenReturn(request.admissionAttemptId());
    when(row.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
    when(row.get("canonical_payload", byte[].class))
        .thenReturn(CapacityAdmissionDigest.preimage(request));
    when(row.get("decision_id", UUID.class)).thenReturn(denial);
    when(row.get("decision", String.class)).thenReturn("NO_ACCEPT");
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
    var repository = new AccountCapacityOriginDecisionRepository(dsl);
    assertThat(repository.refuse(request)).isEqualTo(denial);
    assertThat(repository.refuse(request)).isEqualTo(denial);
    assertThatThrownBy(() -> repository.accept(request)).hasMessage("IDEMPOTENCY_CONFLICT");
    when(row.get("canonical_payload", byte[].class)).thenReturn(new byte[] {1});
    assertThatThrownBy(() -> repository.find(request)).hasMessage("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void unknownAndExpiredAttemptPreserveHoldWithoutCommandOrCommit() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var owner = mock(AccountTenantCapacityOwner.class);
    var receiver = mock(AccountCapacityOriginRecovery.RecoveryCommand.class);
    var now = CapacityAdmissionTestFixture.NOW;
    when(owner.resolveOrigin(request, CapacityAdmissionTestFixture.PEER, false))
        .thenReturn(new AccountCapacityOriginResolution(request, Status.UNKNOWN, null, null));
    when(receiver.beginObservation(request, now.plusSeconds(5)))
        .thenReturn(
            new Permit(
                request.mutationDigest(),
                "sha256:" + "a".repeat(64),
                "owner-a",
                1,
                1,
                now.plusSeconds(5)));
    var recovery =
        new AccountCapacityOriginRecovery(owner, receiver, Clock.fixed(now, ZoneOffset.UTC));
    assertThat(recovery.attempt(request, CapacityAdmissionTestFixture.PEER, false, now))
        .isEqualTo(AccountCapacityOriginRecovery.Outcome.HELD_UNAVAILABLE);
    verifyNoInteractions(owner);
    assertThat(
            recovery.attempt(request, CapacityAdmissionTestFixture.PEER, false, now.plusSeconds(5)))
        .isEqualTo(AccountCapacityOriginRecovery.Outcome.HELD_UNAVAILABLE);
    verify(receiver).beginObservation(request, now.plusSeconds(5));
    verify(receiver, never()).receiveExact(any(), any());
    verify(owner, never()).commit(any(), anyString());
  }

  @Test
  void lostCommandReplyRetriesExactRetainedDenialWithoutRediscoveryCommit() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var owner = mock(AccountTenantCapacityOwner.class);
    var receiver = mock(AccountCapacityOriginRecovery.RecoveryCommand.class);
    var now = CapacityAdmissionTestFixture.NOW;
    var decision =
        new AccountCapacityOriginResolution(
            request, Status.NO_ACCEPT_RETAINED, null, UUID.randomUUID());
    var first =
        new Permit(
            request.mutationDigest(),
            "sha256:" + "a".repeat(64),
            "owner-a",
            1,
            1,
            now.plusSeconds(5));
    var second =
        new Permit(
            request.mutationDigest(),
            "sha256:" + "a".repeat(64),
            "owner-a",
            1,
            2,
            now.plusSeconds(5));
    when(receiver.beginObservation(request, now.plusSeconds(5))).thenReturn(first, second);
    when(owner.resolveOrigin(request, CapacityAdmissionTestFixture.PEER, true))
        .thenReturn(decision);
    doThrow(new IllegalStateException("Lost reply")).when(receiver).receiveExact(decision, first);
    var recovery =
        new AccountCapacityOriginRecovery(owner, receiver, Clock.fixed(now, ZoneOffset.UTC));
    assertThat(
            recovery.attempt(request, CapacityAdmissionTestFixture.PEER, true, now.plusSeconds(5)))
        .isEqualTo(AccountCapacityOriginRecovery.Outcome.HELD_UNAVAILABLE);
    assertThat(
            recovery.attempt(request, CapacityAdmissionTestFixture.PEER, true, now.plusSeconds(5)))
        .isEqualTo(AccountCapacityOriginRecovery.Outcome.DELIVERED);
    var order = inOrder(receiver, owner);
    order.verify(receiver).beginObservation(request, now.plusSeconds(5));
    order.verify(owner).resolveOrigin(request, CapacityAdmissionTestFixture.PEER, true);
    order.verify(receiver).receiveExact(decision, first);
    order.verify(receiver).beginObservation(request, now.plusSeconds(5));
    order.verify(owner).resolveOrigin(request, CapacityAdmissionTestFixture.PEER, true);
    order.verify(receiver).receiveExact(decision, second);
    verify(owner, never()).commit(any(), anyString());
  }

  @Test
  void existingPolicyUsesImmutableExpiryAndFloorsEachDeadlineToMicros() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var policy = AccountCapacityOriginContinuation.preAccount(request);
    assertThat(policy.maxObservations()).isEqualTo(12);
    assertThat(policy.deadline()).isEqualTo(request.evidence().expiresAt().plusSeconds(60));
    var now = CapacityAdmissionTestFixture.NOW.plusNanos(123456789);
    var admission =
        AccountCapacityOriginContinuation.admit(policy, 3, now, now.plusSeconds(30)).orElseThrow();
    assertThat(admission.ordinal()).isEqualTo(4);
    assertThat(admission.deadline())
        .isEqualTo(now.plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    var clamped =
        AccountCapacityOriginContinuation.admit(
                policy, 3, policy.deadline().minusSeconds(1), policy.deadline().plusSeconds(50))
            .orElseThrow();
    assertThat(clamped.deadline()).isEqualTo(policy.deadline());
  }

  @Test
  void exhaustionAndExpiredBudgetNeverReplenishAtDiscoveryOrTransfer() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var policy = AccountCapacityOriginContinuation.preAccount(request);
    assertThat(
            AccountCapacityOriginContinuation.admit(
                policy, 12, CapacityAdmissionTestFixture.NOW, policy.deadline()))
        .isEmpty();
    assertThat(
            AccountCapacityOriginContinuation.admit(
                policy, 1, policy.deadline(), policy.deadline().plusSeconds(5)))
        .isEmpty();
    var moved =
        AccountCapacityOriginContinuation.admit(
                policy, 11, policy.deadline().minusSeconds(2), policy.deadline())
            .orElseThrow();
    assertThat(moved.ordinal()).isEqualTo(12);
    assertThat(moved.deadline()).isEqualTo(policy.deadline());
  }

  @Test
  void acceptedResolutionRequiresStoredReservationPolicyAndPreservesConsumedCount() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var result =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.randomUUID(),
            1,
            request.evidence().expiresAt(),
            net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    var deadline = AccountCapacityOriginContinuation.reconcileDeadline(result.expiresAt());
    var policy = AccountCapacityOriginContinuation.retained(request, result, deadline);
    var resolution =
        new AccountCapacityOriginResolution(
            request, Status.RESERVATION_RETAINED, result, null, policy);
    assertThat(resolution.continuationPolicy()).isEqualTo(policy);
    assertThat(
            AccountCapacityOriginContinuation.admit(
                    policy, 8, CapacityAdmissionTestFixture.NOW, deadline)
                .orElseThrow()
                .ordinal())
        .isEqualTo(9);
    assertThatThrownBy(
            () ->
                AccountCapacityOriginContinuation.retained(
                    request, result, deadline.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountCapacityOriginResolution(
                    request, Status.RESERVATION_RETAINED, result, null))
        .isInstanceOf(NullPointerException.class);
  }
}
