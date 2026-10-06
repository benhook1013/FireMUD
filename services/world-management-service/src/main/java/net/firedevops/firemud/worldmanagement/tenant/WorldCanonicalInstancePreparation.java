package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed input and storage result for the owner-private canonical instance materialization cut.
 *
 * <p>The input preserves the caller's exact Game Session read tuple, World V26/V27 receipts, and
 * the original immutable frozen topology source. It is component input only: construction does not
 * authenticate a producer, authorize a release, prove publication delivery, or enable lifecycle
 * activation or gameplay admission.
 */
public final class WorldCanonicalInstancePreparation {
  private WorldCanonicalInstancePreparation() {}

  /** Complete immutable request to materialize one generation-free selected canonical graph. */
  public record Input(
      WorldCanonicalInstanceAssociation.GameSessionReadRequest gameSessionReadRequest,
      WorldCanonicalInstanceAssociation.GameSessionReadEvidence gameSessionReadEvidence,
      WorldCompleteLaunchBindingReceipt completeLaunchBinding,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldCanonicalInstanceTopologyPlan topologyPlan) {
    public Input {
      Objects.requireNonNull(gameSessionReadRequest, "gameSessionReadRequest");
      Objects.requireNonNull(gameSessionReadEvidence, "gameSessionReadEvidence");
      Objects.requireNonNull(completeLaunchBinding, "completeLaunchBinding");
      Objects.requireNonNull(versionIdentity, "versionIdentity");
      Objects.requireNonNull(topologyPlan, "topologyPlan");

      // Claim performs the repository's full request-echo and exact V26/V27 source checks without
      // allocating or writing anything. The placeholder row id is deliberately not retained.
      new WorldCanonicalInstanceAssociation.Claim(
          gameSessionReadRequest,
          gameSessionReadEvidence,
          1L,
          completeLaunchBinding,
          versionIdentity);

      var owner = topologyPlan.sourceBinding().plan().ownerBinding();
      if (!topologyPlan.tenantId().equals(versionIdentity.canonicalTenantId())
          || !topologyPlan.versionId().equals(versionIdentity.canonicalVersionId())
          || !owner.targetNamespace().equals(versionIdentity.targetNamespace())
          || !owner.canonicalTenantId().equals(versionIdentity.canonicalTenantId())
          || !owner.canonicalVersionId().equals(versionIdentity.canonicalVersionId())
          || !owner.versionIdentityOperationId().equals(versionIdentity.operationId())
          || !topologyPlan
              .sourceBinding()
              .plan()
              .binding()
              .commitId()
              .toString()
              .equals(topologyPlan.sourceBinding().freeze().appliedCommitId())) {
        throw new IllegalArgumentException(
            "Canonical preparation topology differs from its exact V27 identity and frozen commit");
      }
      requireGenerationFree(topologyPlan);
      if (topologyPlan.regions().isEmpty()) {
        throw new IllegalArgumentException("Canonical preparation requires at least one region");
      }
    }

    public UUID canonicalGameInstanceId() {
      return gameSessionReadEvidence.canonicalGameInstanceId();
    }

    public UUID captureId() {
      return topologyPlan.captureId();
    }
  }

  /** Durable result reconstructed only after the owner transaction has committed. */
  public record Result(
      WorldCanonicalInstanceAssociation association,
      UUID captureId,
      String graphSha256,
      String inputDigest,
      int regionCount,
      int zoneCount,
      int roomCount,
      int exitCount,
      String storageStatus) {
    public Result {
      Objects.requireNonNull(association, "association");
      Objects.requireNonNull(captureId, "captureId");
      Objects.requireNonNull(graphSha256, "graphSha256");
      Objects.requireNonNull(inputDigest, "inputDigest");
      Objects.requireNonNull(storageStatus, "storageStatus");
      if (regionCount <= 0 || zoneCount < 0 || roomCount < 0 || exitCount < 0) {
        throw new IllegalArgumentException("Canonical materialization counts are invalid");
      }
      if (!"MATERIALIZED_UNVERIFIED".equals(storageStatus)) {
        throw new IllegalArgumentException("Unsupported canonical preparation result status");
      }
    }
  }

  /** Required intent is rejected before an owner transaction or identifier allocation begins. */
  public static final class GenerationIntentNotSupportedException extends IllegalArgumentException {
    public GenerationIntentNotSupportedException(String message) {
      super(message);
    }
  }

  static void requireGenerationFree(WorldCanonicalInstanceTopologyPlan topologyPlan) {
    Objects.requireNonNull(topologyPlan, "topologyPlan");
    if (!topologyPlan.generationRules().isEmpty() || !topologyPlan.spawnBindings().isEmpty()) {
      throw new GenerationIntentNotSupportedException(
          "Canonical preparation cannot allocate or materialize a graph with configured generation or spawn intent");
    }
  }

  static int entryCount(Input input, EntryType family) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(family, "family");
    return (int)
        input.topologyPlan().entries().stream().filter(entry -> matches(entry, family)).count();
  }

  private static boolean matches(WorldCanonicalInstanceTopologyPlan.Entry entry, EntryType family) {
    return switch (family) {
      case REGION -> entry instanceof WorldCanonicalInstanceTopologyPlan.Region;
      case ZONE -> entry instanceof WorldCanonicalInstanceTopologyPlan.Zone;
      case ROOM -> entry instanceof WorldCanonicalInstanceTopologyPlan.Room;
      case ROOM_EXIT -> entry instanceof WorldCanonicalInstanceTopologyPlan.RoomExit;
    };
  }

  enum EntryType {
    REGION,
    ZONE,
    ROOM,
    ROOM_EXIT
  }
}
