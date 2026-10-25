package net.firedevops.firemud.entitymanagement.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.entitymanagement.security.CanonicalPreseededActorAssignmentPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;

/**
 * Unregistered certificate-guarded assignment ingress. It delegates all owner reads, grant
 * revalidation and persistence to the existing Entity assignment service.
 */
public final class CanonicalPreseededActorAssignmentGrpcService
    extends CanonicalPreseededActorAssignmentServiceGrpc
        .CanonicalPreseededActorAssignmentServiceImplBase {
  private final String trustedNamespace;
  private final PreseededActorAssignmentService assignmentService;

  public CanonicalPreseededActorAssignmentGrpcService(
      String trustedNamespace, PreseededActorAssignmentService assignmentService) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.trustedNamespace = trustedNamespace;
    this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService");
  }

  @Override
  public void assignPreseededActor(
      AssignPreseededActorRequest request,
      StreamObserver<AssignPreseededActorResponse> responseObserver) {
    try {
      CanonicalPreseededActorAssignmentPeerInterceptor.requireVerifiedPeer(trustedNamespace);
    } catch (StatusRuntimeException unauthorized) {
      responseObserver.onError(unauthorized);
      return;
    }

    final PreseededActorAssignmentRequest assignmentRequest;
    try {
      assignmentRequest = PreseededActorAssignmentGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformedRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Preseeded actor assignment request is invalid")
              .asRuntimeException());
      return;
    }

    final PreseededActorAssignmentResult result;
    try {
      result = assignmentService.assign(assignmentRequest);
    } catch (RuntimeException failure) {
      responseObserver.onError(toAssignmentFailureStatus(failure));
      return;
    }

    try {
      responseObserver.onNext(
          PreseededActorAssignmentGrpcCodec.toResponse(assignmentRequest, result));
      responseObserver.onCompleted();
    } catch (RuntimeException invalidPersistedResult) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Preseeded actor assignment failed")
              .asRuntimeException());
    }
  }

  private static StatusRuntimeException toAssignmentFailureStatus(RuntimeException failure) {
    if (failure instanceof StatusRuntimeException statusFailure) {
      Status.Code code = statusFailure.getStatus().getCode();
      if (code == Status.Code.PERMISSION_DENIED) {
        return Status.PERMISSION_DENIED
            .withDescription("Preseeded actor assignment is not authorized")
            .asRuntimeException();
      }
      if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.NOT_FOUND) {
        return Status.FAILED_PRECONDITION
            .withDescription("Preseeded actor assignment owner evidence is not current")
            .asRuntimeException();
      }
      if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
        return Status.UNAVAILABLE
            .withDescription("Preseeded actor assignment owner is unavailable")
            .asRuntimeException();
      }
      return Status.INTERNAL
          .withDescription("Preseeded actor assignment failed")
          .asRuntimeException();
    }
    if (failure instanceof AdminAuthorizationException) {
      return Status.PERMISSION_DENIED
          .withDescription("Preseeded actor assignment is not authorized")
          .asRuntimeException();
    }
    if (failure
        instanceof PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException) {
      return Status.UNAVAILABLE
          .withDescription("Preseeded actor assignment owner is unavailable")
          .asRuntimeException();
    }
    if (failure instanceof IllegalStateException) {
      return Status.FAILED_PRECONDITION
          .withDescription("Preseeded actor assignment preconditions are not satisfied")
          .asRuntimeException();
    }
    return Status.INTERNAL
        .withDescription("Preseeded actor assignment failed")
        .asRuntimeException();
  }
}
