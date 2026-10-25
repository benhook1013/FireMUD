package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Immutable owner-allocated tuple for one binding-local account-index member repair. */
public record CanonicalGameplayAccountMemberRepairOperation(
    UUID transitionId,
    UUID operationId,
    BigInteger operationFence,
    UUID coverageFence,
    BigInteger inventorySnapshotRevision,
    CanonicalGameplayBindingRef bindingRef,
    UUID accountId,
    UUID tenantId,
    BigInteger bindingGeneration,
    UUID accountIndexFence,
    CanonicalGameplayBindingAccountIndexObligation originalObligation,
    String member,
    BigInteger accountControlEpoch) {
  public CanonicalGameplayAccountMemberRepairOperation {
    requireNonNil(transitionId, "transitionId");
    requireNonNil(operationId, "operationId");
    if (operationId.equals(transitionId)) {
      throw new IllegalArgumentException("operationId must be distinct from transitionId");
    }
    requirePositive(operationFence, "operationFence");
    requireNonNil(coverageFence, "coverageFence");
    requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
    Objects.requireNonNull(bindingRef, "bindingRef");
    requireNonNil(accountId, "accountId");
    requireNonNil(tenantId, "tenantId");
    requirePositive(bindingGeneration, "bindingGeneration");
    requireNonNil(accountIndexFence, "accountIndexFence");
    Objects.requireNonNull(originalObligation, "originalObligation");
    Objects.requireNonNull(member, "member");
    Objects.requireNonNull(accountControlEpoch, "accountControlEpoch");
    if (accountControlEpoch.signum() < 0) {
      throw new IllegalArgumentException("accountControlEpoch must not be negative");
    }
    if (coverageFence.equals(accountIndexFence)
        || coverageFence.equals(operationId)
        || coverageFence.equals(transitionId)) {
      throw new IllegalArgumentException(
          "coverageFence must be distinct from operation identities");
    }
    if (!bindingRef.tenantId().equals(tenantId.toString())) {
      throw new IllegalArgumentException("bindingRef tenant segment differs from tenantId");
    }
    if (!originalObligation.transitionId().equals(transitionId)
        || originalObligation.ordinal() != 0
        || !originalObligation.bindingRef().equals(bindingRef)
        || !originalObligation.accountId().equals(accountId)
        || !originalObligation.tenantId().equals(tenantId)
        || originalObligation.action()
            != CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN
        || originalObligation.expectedPriorGeneration() != null
        || !originalObligation.bindingGeneration().equals(bindingGeneration)
        || !originalObligation.accountIndexFence().equals(accountIndexFence)
        || originalObligation.executionPhase()
            != CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS
        || originalObligation.status()
            != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
        || (originalObligation.projectionState()
                != CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED
            && originalObligation.projectionState()
                != CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                    .PRESENT_AWAITING_COVERAGE)) {
      throw new IllegalArgumentException(
          "originalObligation is not the exact outstanding NEW account-index obligation");
    }
    if (originalObligation.inventoryRevision().compareTo(inventorySnapshotRevision) > 0) {
      throw new IllegalArgumentException(
          "original obligation is newer than the immutable inventory snapshot");
    }
    String expectedMember =
        new CanonicalGameplayAccountIndexMember(bindingRef, bindingGeneration, accountIndexFence)
            .value();
    if (!expectedMember.equals(member)) {
      throw new IllegalArgumentException("member differs from the exact binding tuple");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
