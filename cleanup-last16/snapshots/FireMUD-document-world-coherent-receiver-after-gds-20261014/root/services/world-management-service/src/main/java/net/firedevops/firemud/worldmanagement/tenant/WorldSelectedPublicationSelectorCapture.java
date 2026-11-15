package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;

/** Owner-local selector capture for one exact committed selected-publication freeze. */
final class WorldSelectedPublicationSelectorCapture {
  private final String workloadNamespace;
  private final WorldCanonicalFrozenTopologyService canonicalFrozenTopology;

  WorldSelectedPublicationSelectorCapture(
      String workloadNamespace, WorldCanonicalFrozenTopologyService canonicalFrozenTopology) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.canonicalFrozenTopology =
        Objects.requireNonNull(canonicalFrozenTopology, "canonicalFrozenTopology");
  }

  /**
   * Captures through WorldCanonicalFrozenTopologyService.capture() from the exact owner records.
   */
  WorldCanonicalFrozenTopology capture(
      WorldDraftGraphApplication application, Acknowledgement acknowledgement) {
    Objects.requireNonNull(application, "application");
    Objects.requireNonNull(acknowledgement, "acknowledgement");
    var request = acknowledgement.request();
    var accountBinding = request.accountBinding();
    var selection = accountBinding.input().selection();
    var plan = application.plan();
    var operation = application.operation();
    var binding = plan.binding();
    var owner = operation.ownerBinding();
    if (!workloadNamespace.equals(request.targetNamespace())
        || !workloadNamespace.equals(owner.targetNamespace())
        || !plan.ownerBinding().equals(owner)
        || !selection.selectedCommit().equals(binding)
        || !Arrays.equals(selection.selectedCommit().canonicalBytes(), binding.canonicalBytes())
        || !operation.binding().equals(binding)
        || !Arrays.equals(operation.binding().canonicalBytes(), binding.canonicalBytes())
        || !operation.commitId().equals(binding.commitId())
        || !request.canonicalTenantId().equals(operation.canonicalTenantId())
        || !request.canonicalVersionId().equals(operation.canonicalVersionId())
        || !request.canonicalTenantId().equals(owner.canonicalTenantId())
        || !request.canonicalVersionId().equals(owner.canonicalVersionId())
        || selection.target().gameDesignVersionRowId() != owner.gameDesignVersionId()
        || !acknowledgement.intakeRequestId().equals(owner.intakeRequestId())
        || !acknowledgement.appliedCommitId().equals(binding.commitId().toString())
        || acknowledgement.versionStateEpoch() != request.expectedVersionStateEpoch()
        || !plan.graph().freshGraphDeclaration().isPresent()) {
      throw conflict(
          "Committed World freeze differs from the exact retained selected APPLIED application");
    }

    var tuples =
        binding.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var captureRequest =
        new CaptureRequest(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.canonicalVersionId(),
            acknowledgement.intakeRequestId(),
            acknowledgement.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            acknowledgement.versionStateEpoch(),
            PublicationDigestRequestBinding.full(
                    request.canonicalTenantId().toString(),
                    Long.toString(binding.target().gameDesignVersionRowId()),
                    request.publicationRequestId())
                .derivedWorkflowIdentity(),
            acknowledgement.appliedCommitId(),
            acknowledgement.contentDigest(),
            acknowledgement.digestSchemaVersion(),
            tuples);
    return canonicalFrozenTopology.capture(
        new WorldCanonicalFrozenTopology.Request(plan, captureRequest));
  }

  private static ConflictException conflict(String message) {
    return new ConflictException(message);
  }
}
