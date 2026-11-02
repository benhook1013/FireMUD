package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OutcomeStatus;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcomeReadServiceGrpc.OriginalTenantCreationOutcomeReadServiceImplBase;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

/** Exact terminal original-outcome read for the same-namespace Account workload; unregistered. */
public final class OriginalTenantCreationOutcomeReadGrpcService
    extends OriginalTenantCreationOutcomeReadServiceImplBase {
  private final GameTenantCreationOriginalOutcomeRepository outcomeRepository;
  private final GameTenantCreationRepository creationRepository;
  private final String workloadNamespace;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected owner repositories are private internal persistence collaborators.")
  public OriginalTenantCreationOutcomeReadGrpcService(
      GameTenantCreationOriginalOutcomeRepository outcomeRepository,
      GameTenantCreationRepository creationRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.outcomeRepository = outcomeRepository;
    this.creationRepository = creationRepository;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveOriginalTenantCreationOutcome(
      ResolveOriginalTenantCreationOutcomeRequest request,
      StreamObserver<ResolveOriginalTenantCreationOutcomeResponse> responseObserver) {
    if (SessionContext.hasAuthenticatedCallerContext() || !isAccountPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Verified same-namespace Account workload identity without caller context is "
                      + "required")
              .asRuntimeException());
      return;
    }

    final ReadRequest readRequest;
    try {
      readRequest =
          GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest(workloadNamespace, request);
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical exact original tenant outcome request is required")
              .asRuntimeException());
      return;
    }

    final OriginalOutcomeSnapshot snapshot;
    try {
      OriginalOutcomeBinding repositoryBinding = toRepositoryBinding(readRequest.binding());
      if (!toEvidenceBinding(repositoryBinding).equals(readRequest.binding())) {
        failPrecondition(responseObserver);
        return;
      }
      Optional<OriginalOutcomeSnapshot> stored = outcomeRepository.readCommitted(repositoryBinding);
      if (stored.isEmpty()) {
        responseObserver.onError(
            Status.NOT_FOUND
                .withDescription("No committed terminal outcome matches the exact original")
                .asRuntimeException());
        return;
      }
      snapshot = stored.orElseThrow();
      if (!toEvidenceBinding(snapshot.binding()).equals(readRequest.binding())) {
        failPrecondition(responseObserver);
        return;
      }
      if (snapshot.status() == OutcomeStatus.PENDING) {
        failPrecondition(responseObserver);
        return;
      }
    } catch (IllegalArgumentException | IllegalStateException exception) {
      failPrecondition(responseObserver);
      return;
    } catch (RuntimeException exception) {
      failStorage(responseObserver, exception);
      return;
    }

    final Optional<FreshTenantCreationEvidence> committedReceipt;
    try {
      committedReceipt =
          creationRepository.read(readRequest.binding().creationRequestId(), workloadNamespace);
    } catch (org.jooq.exception.TooManyRowsException
        | IllegalArgumentException
        | IllegalStateException exception) {
      failPrecondition(responseObserver);
      return;
    } catch (RuntimeException exception) {
      failStorage(responseObserver, exception);
      return;
    }

    final GameTenantCreationOriginalOutcomeEvidence evidence;
    if (snapshot.status() == OutcomeStatus.NOT_CREATED) {
      if (snapshot.creationOperationId() != null
          || snapshot.creationEvidenceDigest() != null
          || committedReceipt.isPresent()) {
        failPrecondition(responseObserver);
        return;
      }
      evidence =
          new GameTenantCreationOriginalOutcomeEvidence(
              1, toEvidenceBinding(snapshot.binding()), Outcome.NOT_CREATED, Optional.empty());
    } else if (snapshot.status() == OutcomeStatus.CREATED) {
      if (committedReceipt.isEmpty()) {
        failPrecondition(responseObserver);
        return;
      }
      FreshTenantCreationEvidence receipt = committedReceipt.orElseThrow();
      if (!snapshot.creationOperationId().equals(receipt.operationId())
          || !snapshot.creationEvidenceDigest().equals(receipt.evidenceDigest())
          || !snapshot.binding().targetNamespace().equals(receipt.targetNamespace())
          || !snapshot.binding().creationRequestId().equals(receipt.creationRequestId())
          || !snapshot.binding().creationRequestDigest().equals(receipt.requestDigest())) {
        failPrecondition(responseObserver);
        return;
      }
      evidence =
          new GameTenantCreationOriginalOutcomeEvidence(
              1, toEvidenceBinding(snapshot.binding()), Outcome.CREATED, Optional.of(receipt));
    } else {
      failPrecondition(responseObserver);
      return;
    }

    final ResolveOriginalTenantCreationOutcomeResponse response;
    try {
      response = GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(readRequest, evidence);
    } catch (IllegalArgumentException exception) {
      failPrecondition(responseObserver);
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean isAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service");
  }

  private static OriginalOutcomeBinding toRepositoryBinding(
      GameTenantCreationOriginalOutcomeEvidence.Binding binding) {
    return new OriginalOutcomeBinding(
        binding.targetNamespace(),
        binding.creationRequestId(),
        binding.accountAuthorizationOperationId(),
        binding.initiatingAccountId(),
        binding.creationRequestDigest(),
        binding.accountAuthorizationDigest());
  }

  private static GameTenantCreationOriginalOutcomeEvidence.Binding toEvidenceBinding(
      OriginalOutcomeBinding binding) {
    return new GameTenantCreationOriginalOutcomeEvidence.Binding(
        binding.targetNamespace(),
        binding.creationRequestId(),
        binding.accountAuthorizationOperationId(),
        binding.initiatingAccountId(),
        binding.creationRequestDigest(),
        binding.accountAuthorizationDigest());
  }

  private static void failPrecondition(
      StreamObserver<ResolveOriginalTenantCreationOutcomeResponse> responseObserver) {
    responseObserver.onError(
        Status.FAILED_PRECONDITION
            .withDescription("Original tenant outcome or its exact V30 receipt is inconsistent")
            .asRuntimeException());
  }

  private static void failStorage(
      StreamObserver<ResolveOriginalTenantCreationOutcomeResponse> responseObserver,
      RuntimeException failure) {
    Status storageStatus = storageFailureStatus(failure);
    responseObserver.onError(
        (storageStatus == null
                ? Status.INTERNAL.withDescription("Original tenant outcome could not be read")
                : storageStatus)
            .asRuntimeException());
  }

  private static Status storageFailureStatus(Throwable failure) {
    if (failure instanceof TransactionException) {
      boolean retryable =
          (failure instanceof org.springframework.transaction.CannotCreateTransactionException
                  && !(failure
                      instanceof
                      org.springframework.transaction.NestedTransactionNotSupportedException))
              || failure instanceof org.springframework.transaction.TransactionTimedOutException;
      return Status.fromCode(retryable ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL)
          .withDescription(
              retryable
                  ? "Original tenant outcome is temporarily unavailable"
                  : "Original tenant outcome could not be read");
    }
    if (failure instanceof DataAccessResourceFailureException
        || failure instanceof TransientDataAccessException) {
      return Status.UNAVAILABLE.withDescription(
          "Original tenant outcome is temporarily unavailable");
    }
    if (failure instanceof DataAccessException
        || failure instanceof org.jooq.exception.DataAccessException) {
      boolean connectionFailure = hasConnectionFailureSqlState(failure);
      return Status.fromCode(connectionFailure ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL)
          .withDescription(
              connectionFailure
                  ? "Original tenant outcome is temporarily unavailable"
                  : "Original tenant outcome could not be read");
    }
    return null;
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
          return true;
        }
      }
    }
    return false;
  }
}
