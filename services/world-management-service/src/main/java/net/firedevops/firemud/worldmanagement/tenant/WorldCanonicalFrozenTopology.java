package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;

/**
 * Immutable component capture, never Account authorization or complete participant/release proof.
 */
public final class WorldCanonicalFrozenTopology {
  public static final String STATUS = "CAPTURED_UNVERIFIED";

  public record Request(WorldDraftTopologyCommitPlan plan, CaptureRequest freeze) {
    public Request {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(freeze, "freeze");
      var owner = plan.ownerBinding();
      if (!freeze.targetNamespace().equals(owner.targetNamespace())
          || !freeze.canonicalTenantId().equals(owner.canonicalTenantId())
          || !freeze.canonicalVersionId().equals(owner.canonicalVersionId())
          || !freeze.intakeRequestId().equals(owner.intakeRequestId())
          || !freeze.appliedCommitId().equals(plan.binding().commitId().toString())) {
        throw new IllegalArgumentException(
            "Frozen topology must bind the exact selected complete commit and source");
      }
      new WorldDesignPublicationFenceEvidence(
          owner.targetNamespace(),
          owner.canonicalTenantId(),
          owner.canonicalVersionId(),
          owner.versionIdentityOperationId(),
          owner.gameDesignVersionId(),
          owner.intakeRequestId(),
          owner.intakeOperationId(),
          owner.intakeRequestDigest(),
          owner.sourceOperationId(),
          owner.sourceEvidenceDigest(),
          owner.intakeReceiptDigest(),
          freeze.publicationRequestId(),
          freeze.requestDigest(),
          freeze.versionStateEpoch(),
          freeze.publishWorkflowId());
      var expected =
          plan.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .map(
                  unit ->
                      new OwnedAffectedTuple(
                          unit.owner().name(),
                          unit.aggregateType(),
                          unit.aggregateId(),
                          unit.scopeType(),
                          unit.scopeId(),
                          unit.expectedEpoch()))
              .toList();
      if (expected.size() != freeze.suppliedOwnedAffectedTuples().size()
          || !new java.util.HashSet<>(expected)
              .equals(new java.util.HashSet<>(freeze.suppliedOwnedAffectedTuples()))) {
        throw new IllegalArgumentException(
            "Frozen topology must retain every exact original World affected tuple");
      }
    }
  }

  private final UUID captureId;
  private final Request request;
  private final WorldAuthoredVersionIdentityReceipt sourceIdentity;
  private final WorldCanonicalAuthoredGraph graph;
  private final byte[] graphBytes;
  private final byte[] storageResultBytes;
  private final byte[] resultBytes;

  WorldCanonicalFrozenTopology(
      UUID captureId,
      Request request,
      WorldAuthoredVersionIdentityReceipt sourceIdentity,
      WorldCanonicalAuthoredGraph graph,
      byte[] graphBytes,
      byte[] storageResultBytes,
      byte[] resultBytes) {
    this.captureId = Objects.requireNonNull(captureId, "captureId");
    if (captureId.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Capture ID must be non-nil");
    this.request = Objects.requireNonNull(request, "request");
    this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.graphBytes = Objects.requireNonNull(graphBytes, "graphBytes").clone();
    this.storageResultBytes =
        Objects.requireNonNull(storageResultBytes, "storageResultBytes").clone();
    this.resultBytes = Objects.requireNonNull(resultBytes, "resultBytes").clone();
  }

  public UUID captureId() {
    return captureId;
  }

  public Request request() {
    return request;
  }

  public WorldAuthoredVersionIdentityReceipt sourceIdentity() {
    return sourceIdentity;
  }

  public WorldCanonicalAuthoredGraph graph() {
    return graph;
  }

  public byte[] graphBytes() {
    return graphBytes.clone();
  }

  public byte[] storageResultBytes() {
    return storageResultBytes.clone();
  }

  public byte[] resultBytes() {
    return resultBytes.clone();
  }

  public String status() {
    return STATUS;
  }
}
