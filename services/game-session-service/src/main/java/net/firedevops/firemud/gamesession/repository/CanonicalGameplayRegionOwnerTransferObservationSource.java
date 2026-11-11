package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Current SQL transfer intent/item or sealed completion, never transfer mutation authority. */
@FunctionalInterface
interface CanonicalGameplayRegionOwnerTransferObservationSource {
  Context readCurrent(UUID transferId, long targetOrdinal);

  /** Independent topology read; SQL implementations retain their operation's admitted budget. */
  default CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
      UUID transitionId, CanonicalGameplayTenantGameplayTagRepository tags) {
    return tags.assignmentForTransition(transitionId);
  }

  /**
   * One frozen item and its original owner, with its exact current control phase. A COMPLETED
   * context requires independently current sealed successor evidence and is read-only currentness,
   * never permission for mutation. Mutable per-item progress is deliberately excluded: completing a
   * different item must not change this item's observation identity.
   */
  record Context(
      UUID transferId,
      long targetOrdinal,
      String transferIntentDigest,
      String frozenSourceDigest,
      String oldRegionOwnerId,
      long oldRegionOwnerFence,
      UUID tenantId,
      UUID gameInstanceId,
      UUID regionId,
      long regionEpoch,
      UUID transitionId,
      UUID requestId,
      UUID accountId,
      UUID playableStateNamespaceId,
      String executionDigest,
      String candidateDigest,
      String tenantGameplayTag,
      String workloadNamespace,
      int allocationVersion,
      String controlState,
      String completionDigest) {
    public Context {
      requireNonNil(transferId, "transferId");
      requireNonNil(tenantId, "tenantId");
      requireNonNil(gameInstanceId, "gameInstanceId");
      requireNonNil(regionId, "regionId");
      requireNonNil(transitionId, "transitionId");
      requireNonNil(requestId, "requestId");
      requireNonNil(accountId, "accountId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      requireDigest(transferIntentDigest, "transferIntentDigest");
      requireDigest(frozenSourceDigest, "frozenSourceDigest");
      requireDigest(executionDigest, "executionDigest");
      requireDigest(candidateDigest, "candidateDigest");
      if (targetOrdinal < 0L || oldRegionOwnerFence <= 0L || regionEpoch <= 0L) {
        throw new IllegalArgumentException(
            "Transfer ordinal, owner fence or region epoch is invalid");
      }
      if (oldRegionOwnerId == null
          || oldRegionOwnerId.isBlank()
          || !oldRegionOwnerId.equals(oldRegionOwnerId.trim())
          || oldRegionOwnerId.codePointCount(0, oldRegionOwnerId.length()) > 120) {
        throw new IllegalArgumentException("Transfer original region owner id is invalid");
      }
      CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(tenantGameplayTag);
      if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace) || allocationVersion <= 0) {
        throw new IllegalArgumentException("Transfer workload or allocation version is invalid");
      }
      if ("FROZEN".equals(controlState)) {
        if (completionDigest != null) {
          throw new IllegalArgumentException("FROZEN transfer context cannot contain a completion");
        }
      } else if ("COMPLETED".equals(controlState)) {
        requireDigest(completionDigest, "completionDigest");
      } else {
        throw new IllegalArgumentException("Transfer observation control state is invalid");
      }
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must not be nil");
    }
  }

  private static void requireDigest(String value, String field) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be canonical SHA-256");
    }
  }
}
