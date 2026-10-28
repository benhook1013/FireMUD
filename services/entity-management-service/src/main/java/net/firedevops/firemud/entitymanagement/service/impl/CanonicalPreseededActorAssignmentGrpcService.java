package net.firedevops.firemud.entitymanagement.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.entitymanagement.security.CanonicalPreseededActorAssignmentPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorRuntimeReference;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Unregistered method-guarded assignment write and immutable receipt read. Assignment delegates
 * owner reads, grant revalidation and persistence to the existing Entity assignment service; the
 * receipt read delegates to the existing committed-receipt service.
 */
public final class CanonicalPreseededActorAssignmentGrpcService
    extends CanonicalPreseededActorAssignmentServiceGrpc
        .CanonicalPreseededActorAssignmentServiceImplBase {
  private final String trustedNamespace;
  private final PreseededActorAssignmentService assignmentService;
  private final PreseededActorAssignmentReceiptService receiptService;

  public CanonicalPreseededActorAssignmentGrpcService(
      String trustedNamespace,
      PreseededActorAssignmentService assignmentService,
      PreseededActorAssignmentReceiptService receiptService) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.trustedNamespace = trustedNamespace;
    this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService");
    this.receiptService = Objects.requireNonNull(receiptService, "receiptService");
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

    final AssignPreseededActorResponse response;
    try {
      response = PreseededActorAssignmentGrpcCodec.toResponse(assignmentRequest, result);
    } catch (RuntimeException invalidPersistedResult) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Preseeded actor assignment failed")
              .asRuntimeException());
      return;
    }

    try {
      responseObserver.onNext(response);
    } catch (RuntimeException observerFailure) {
      responseObserver.onError(observerFailure);
      return;
    }
    responseObserver.onCompleted();
  }

  @Override
  public void readPreseededActorAssignmentReceipt(
      ReadPreseededActorAssignmentReceiptRequest request,
      StreamObserver<ReadPreseededActorAssignmentReceiptResponse> responseObserver) {
    try {
      CanonicalPreseededActorAssignmentPeerInterceptor.requireVerifiedWorldPeer(trustedNamespace);
    } catch (StatusRuntimeException unauthorized) {
      responseObserver.onError(unauthorized);
      return;
    }

    final PreseededActorAssignmentGrpcCodec.ReceiptReadRequest receiptReadRequest;
    try {
      receiptReadRequest = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(request);
    } catch (IllegalArgumentException malformedRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Preseeded actor assignment receipt selector is invalid")
              .asRuntimeException());
      return;
    }

    final Optional<PreseededActorAssignmentReceipt> storedReceipt;
    try {
      storedReceipt =
          Objects.requireNonNull(
              receiptService.read(receiptReadRequest.receiptRequest()),
              "receiptService.read result");
    } catch (RuntimeException storageFailure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Preseeded actor assignment receipt read failed")
              .asRuntimeException());
      return;
    }
    if (storedReceipt.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("Exact committed preseeded actor assignment receipt not found")
              .asRuntimeException());
      return;
    }

    PreseededActorAssignmentReceipt receipt = storedReceipt.orElseThrow();
    final ReadPreseededActorAssignmentReceiptResponse response;
    try {
      response =
          PreseededActorAssignmentGrpcCodec.toReceiptReadResponse(receiptReadRequest, receipt);
    } catch (RuntimeException invalidPersistedReceipt) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Preseeded actor assignment receipt is not exact and committed")
              .asRuntimeException());
      return;
    }

    try {
      responseObserver.onNext(response);
    } catch (RuntimeException observerFailure) {
      responseObserver.onError(observerFailure);
      return;
    }
    responseObserver.onCompleted();
  }

  @Override
  public void readPreseededActorRuntimeReference(
      ReadPreseededActorRuntimeReferenceRequest request,
      StreamObserver<ReadPreseededActorRuntimeReferenceResponse> responseObserver) {
    try {
      CanonicalPreseededActorAssignmentPeerInterceptor.requireVerifiedRuntimeReferencePeer(
          trustedNamespace);
    } catch (StatusRuntimeException unauthorized) {
      responseObserver.onError(unauthorized);
      return;
    }
    final PreseededActorAssignmentReceiptRequest selector;
    try {
      selector = PreseededActorAssignmentGrpcCodec.fromRuntimeReferenceRequest(request);
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Runtime reference selector is invalid")
              .asRuntimeException());
      return;
    }
    final Optional<PreseededActorRuntimeReference> stored;
    try {
      stored = Objects.requireNonNull(receiptService.readRuntimeReference(selector));
    } catch (TransientDataAccessException
        | DataAccessResourceFailureException
        | CannotCreateTransactionException
        | TransactionTimedOutException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Runtime reference owner is unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException unexpected) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("Runtime reference read failed").asRuntimeException());
      return;
    }
    if (stored.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("Exact current runtime projection not found")
              .asRuntimeException());
      return;
    }
    final ReadPreseededActorRuntimeReferenceResponse response;
    try {
      response =
          PreseededActorAssignmentGrpcCodec.toRuntimeReferenceResponse(
              selector, stored.orElseThrow());
    } catch (RuntimeException contradictory) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Runtime projection provenance is contradictory")
              .asRuntimeException());
      return;
    }
    try {
      responseObserver.onNext(response);
    } catch (RuntimeException observerFailure) {
      responseObserver.onError(observerFailure);
      return;
    }
    responseObserver.onCompleted();
  }

  private static StatusRuntimeException toAssignmentFailureStatus(RuntimeException failure) {
    if (failure instanceof StatusRuntimeException statusFailure) {
      Status.Code code = statusFailure.getStatus().getCode();
      if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.NOT_FOUND) {
        return Status.FAILED_PRECONDITION
            .withDescription("Preseeded actor assignment owner evidence is not current")
            .asRuntimeException();
      }
      if (code == Status.Code.UNAVAILABLE
          || code == Status.Code.DEADLINE_EXCEEDED
          || code == Status.Code.PERMISSION_DENIED
          || code == Status.Code.RESOURCE_EXHAUSTED
          || code == Status.Code.ABORTED
          || code == Status.Code.CANCELLED
          || code == Status.Code.UNAUTHENTICATED) {
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
    if (failure instanceof PreseededActorAssignmentService.PreconditionException) {
      return Status.FAILED_PRECONDITION
          .withDescription("Preseeded actor assignment preconditions are not satisfied")
          .asRuntimeException();
    }
    return Status.INTERNAL
        .withDescription("Preseeded actor assignment failed")
        .asRuntimeException();
  }
}
