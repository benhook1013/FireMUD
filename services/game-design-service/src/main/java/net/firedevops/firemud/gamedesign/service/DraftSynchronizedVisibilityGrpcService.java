package net.firedevops.firemud.gamedesign.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CommitSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerState;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.VisibilityFence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.WorkflowState;
import net.firedevops.firemud.gamedesign.v1.DraftSynchronizedVisibilityServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityRequest;
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityResponse;
import org.springframework.dao.DataAccessException;

/** Standalone, unregistered receiver for the exact durable synchronized Draft visibility fence. */
public final class DraftSynchronizedVisibilityGrpcService
    extends DraftSynchronizedVisibilityServiceGrpc.DraftSynchronizedVisibilityServiceImplBase {
  private final DraftCommitCoordinatorRepository coordinator;
  private final String workloadNamespace;

  public DraftSynchronizedVisibilityGrpcService(
      DraftCommitCoordinatorRepository coordinator, String workloadNamespace) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Design workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readDraftSynchronizedVisibility(
      ReadDraftSynchronizedVisibilityRequest request,
      StreamObserver<ReadDraftSynchronizedVisibilityResponse> responseObserver) {
    if (!isWorldManagementPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified same-namespace World Management identity is required")
              .asRuntimeException());
      return;
    }

    final DraftSynchronizedVisibilityEvidence.Request decoded;
    try {
      decoded = DraftSynchronizedVisibilityGrpcCodec.fromRequest(request);
      if (!workloadNamespace.equals(decoded.targetNamespace())) {
        responseObserver.onError(
            Status.PERMISSION_DENIED
                .withDescription("Visibility target namespace must match this Game Design owner")
                .asRuntimeException());
        return;
      }
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete synchronized Draft target is required")
              .asRuntimeException());
      return;
    }

    try {
      DraftSynchronizedVisibilityEvidence evidence = readExactFence(decoded);
      responseObserver.onNext(DraftSynchronizedVisibilityGrpcCodec.toResponse(evidence));
      responseObserver.onCompleted();
    } catch (StatusRuntimeException exception) {
      responseObserver.onError(exception);
    } catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design synchronized Draft state is temporarily unavailable")
              .asRuntimeException());
    } catch (IllegalStateException | IllegalArgumentException exception) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("A complete synchronized Draft visibility fence is unavailable")
              .asRuntimeException());
    } catch (RuntimeException exception) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Game Design could not read synchronized Draft visibility")
              .asRuntimeException());
    }
  }

  private DraftSynchronizedVisibilityEvidence readExactFence(
      DraftSynchronizedVisibilityEvidence.Request request) {
    VisibilityFence fence =
        coordinator
            .readVisibilityFence(request.target())
            .orElseThrow(
                () ->
                    Status.UNAVAILABLE
                        .withDescription("No synchronized Draft visibility fence is available")
                        .asRuntimeException());
    if (!fence.target().equals(request.target())) {
      throw unavailable();
    }
    CommitSnapshot snapshot =
        coordinator
            .read(request.target(), fence.requestId())
            .orElseThrow(
                () ->
                    Status.UNAVAILABLE
                        .withDescription("The fenced Draft commit is unavailable")
                        .asRuntimeException());
    DraftCommitBinding binding = snapshot.binding();
    if (snapshot.workflowState() != WorkflowState.SYNCHRONIZED
        || !binding.target().equals(request.target())
        || !binding.requestId().equals(fence.requestId())
        || !binding.commitId().equals(fence.commitId())
        || !binding.digest().equals(fence.inputDigest())) {
      throw unavailable();
    }
    if (snapshot.ownerStates().size() != binding.requiredOwners().size()) {
      throw unavailable();
    }
    for (DraftCommitBinding.Owner owner : binding.requiredOwners()) {
      OwnerState state = snapshot.ownerStates().get(owner);
      if (state == null || state.status() != OwnerStatus.APPLIED || state.outcome().isEmpty()) {
        throw unavailable();
      }
    }

    DraftSynchronizedVisibilityEvidence evidence;
    try {
      evidence =
          new DraftSynchronizedVisibilityEvidence(
              request,
              binding,
              snapshot.workflowState().name(),
              new DraftSynchronizedVisibilityEvidence.Fence(
                  fence.requestId(),
                  fence.commitId(),
                  fence.inputDigest(),
                  fence.resultVectorJson(),
                  fence.createdAt()));
    } catch (IllegalArgumentException exception) {
      throw unavailable();
    }
    for (DraftCommitBinding.Owner owner : binding.requiredOwners()) {
      OwnerOutcome outcome = snapshot.ownerStates().get(owner).outcome().orElseThrow();
      if (!outcomeMatches(evidence.appliedOwnerResult(owner), outcome)) {
        throw unavailable();
      }
    }
    return evidence;
  }

  private static boolean outcomeMatches(
      DraftSynchronizedVisibilityEvidence.AppliedOwnerResult expected, OwnerOutcome actual) {
    if (actual.status() != OwnerStatus.APPLIED
        || expected.owner() != actual.owner()
        || !expected.commitId().equals(actual.commitId())
        || !expected.bindingDigest().equals(actual.bindingDigest())
        || !expected.resultIdentity().equals(actual.resultIdentity())
        || !java.util.Arrays.equals(expected.resultBytes(), actual.resultBytes())) {
      return false;
    }
    List<DraftSynchronizedVisibilityEvidence.AppliedEpoch> expectedEpochs =
        expected.appliedEpochs();
    if (expectedEpochs.size() != actual.appliedEpochs().size()) {
      return false;
    }
    for (int index = 0; index < expectedEpochs.size(); index++) {
      var left = expectedEpochs.get(index);
      var right = actual.appliedEpochs().get(index);
      if (!left.aggregateType().equals(right.aggregateType())
          || !left.aggregateId().equals(right.aggregateId())
          || !left.scopeType().equals(right.scopeType())
          || !left.scopeId().equals(right.scopeId())
          || !left.expectedEpoch().equals(right.expectedEpoch())
          || !left.resultingEpoch().equals(right.resultingEpoch())) {
        return false;
      }
    }
    return true;
  }

  private boolean isWorldManagementPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && peer.isService("world-management-service")
        && peer.isInNamespace(workloadNamespace);
  }

  private static StatusRuntimeException unavailable() {
    return Status.UNAVAILABLE
        .withDescription("A complete synchronized Draft visibility fence is unavailable")
        .asRuntimeException();
  }
}
