package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

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
      requireExactReleaseGraph(completeLaunchBinding.evidence().releaseAttestation(), topologyPlan);
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
      String storageStatus,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId) {
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
      if ((startLocation == null) != (runtimeRoomInstanceId == null)
          || runtimeRoomInstanceId != null && runtimeRoomInstanceId <= 0) {
        throw new IllegalArgumentException(
            "Canonical selector and runtime mapping must be complete together");
      }
    }

    public Result(
        WorldCanonicalInstanceAssociation association,
        UUID captureId,
        String graphSha256,
        String inputDigest,
        int regionCount,
        int zoneCount,
        int roomCount,
        int exitCount,
        String storageStatus) {
      this(
          association,
          captureId,
          graphSha256,
          inputDigest,
          regionCount,
          zoneCount,
          roomCount,
          exitCount,
          storageStatus,
          null,
          null);
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

  /** Joins immutable release evidence to the exact selected World checkpoint, including retries. */
  static void requireExactReleaseGraph(
      AuthoredWorldReleaseAttestationEvidence release,
      WorldCanonicalInstanceTopologyPlan topologyPlan) {
    Objects.requireNonNull(release, "release");
    Objects.requireNonNull(topologyPlan, "topologyPlan");
    var source = topologyPlan.sourceBinding();
    var binding = source.plan().binding();
    var owner = source.plan().ownerBinding();
    var freeze = source.freeze();
    var worldParticipants =
        release.participantDigests().stream()
            .filter(participant -> "WORLD_MANAGEMENT".equals(participant.participantKey()))
            .toList();
    if (!release.targetNamespace().equals(owner.targetNamespace())
        || !release.targetNamespace().equals(freeze.targetNamespace())
        || !release.canonicalTenantId().equals(binding.target().canonicalTenantId())
        || !release.canonicalTenantId().equals(topologyPlan.tenantId())
        || !release.canonicalTenantId().equals(freeze.canonicalTenantId())
        || !release.canonicalVersionId().equals(binding.target().canonicalVersionId())
        || !release.canonicalVersionId().equals(topologyPlan.versionId())
        || !release.canonicalVersionId().equals(freeze.canonicalVersionId())
        || !release.commitId().equals(binding.commitId().toString())
        || !release.commitId().equals(freeze.appliedCommitId())
        || !release.publishWorkflowId().equals(freeze.publishWorkflowId())
        || worldParticipants.size() != 1) {
      throw new IllegalArgumentException(
          "Canonical preparation release differs from the exact selected frozen World graph");
    }
    var world = worldParticipants.getFirst();
    if (!world.scopeValue().equals(Long.toString(owner.gameDesignVersionId()))
        || world.baseVersionIdPresent()
        || world.baseVersionId() != null
        || !world.appliedCommitId().equals(freeze.appliedCommitId())
        || !world.contentDigest().equals(freeze.contentDigest())
        || world.digestSchemaVersion()
            != AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                "WORLD_MANAGEMENT")
        || world.digestSchemaVersion() != freeze.digestSchemaVersion()) {
      throw new IllegalArgumentException(
          "Canonical preparation World participant differs from its exact frozen checkpoint");
    }
    // The freeze observes the Draft epoch; the descriptor attests the later published Version
    // epoch. Those distinct lifecycle observations must not be treated as an equality join.
    requireExactSelector(release, topologyPlan);
  }

  /** V1 remains inert history; v2 must carry the complete original selector and frozen request. */
  static void requireExactSelector(
      AuthoredWorldReleaseAttestationEvidence release,
      WorldCanonicalInstanceTopologyPlan topologyPlan) {
    if (release.schemaVersion() == AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION) return;
    var selector =
        Objects.requireNonNull(release.worldStartLocationEvidence(), "World selector evidence");
    var freeze = topologyPlan.sourceBinding().freeze();
    var expected =
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            freeze.suppliedOwnedAffectedTuples().stream()
                .map(
                    tuple ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            tuple.owner(),
                            tuple.aggregateType(),
                            tuple.aggregateId(),
                            tuple.scopeType(),
                            tuple.scopeId(),
                            tuple.expectedEpoch()))
                .toList());
    var receipt = WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    var declaration =
        topologyPlan
            .sourceBinding()
            .plan()
            .graph()
            .freshGraphDeclaration()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Selected World graph has no original fresh declaration"));
    if (!selector.request().equals(expected)
        || !declaration.startLocation().equals(receipt.startLocation())
        || !receipt.bindingDigest().equals(topologyPlan.sourceBinding().plan().binding().digest())
        || topologyPlan.rooms().stream()
                .filter(
                    room ->
                        room.identity()
                            .templateId()
                            .equals(receipt.startLocation().roomTemplateId()))
                .count()
            != 1) {
      throw new IllegalArgumentException(
          "Release selector differs from the exact frozen request, declaration or ROOM");
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
