package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingInventoryConflictException;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionReadRepository;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.OriginalNewFinalAdmissionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Account-only read of the physical finalized ORIGINAL_NEW session value. */
public final class CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService
    extends OriginalNewFinalAdmissionReadServiceGrpc.OriginalNewFinalAdmissionReadServiceImplBase {
  private static final String FAILURE_DESCRIPTION =
      "Canonical original final admission is unavailable";

  private final CanonicalGameplayOriginalNewFinalAdmissionReadRepository repository;
  private final CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard workloadGuard;
  private final String workloadNamespace;

  public CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService(
      CanonicalGameplayOriginalNewFinalAdmissionReadRepository repository,
      CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard workloadGuard,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "Original NEW read repository required");
    this.workloadGuard = Objects.requireNonNull(workloadGuard, "Account workload guard required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readOriginalNewFinalAdmission(
      ReadOriginalNewFinalAdmissionRequest request,
      StreamObserver<ReadOriginalNewFinalAdmissionResponse> observer) {
    Objects.requireNonNull(observer, "response observer required");
    try {
      // This is deliberately the first request-related operation.
      workloadGuard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }

    final CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector selector;
    try {
      selector = CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    final CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.FinalResult result;
    try {
      result = repository.readActualOriginalFinalAdmission(selector, workloadNamespace);
    } catch (CannotCreateTransactionException
        | DataAccessResourceFailureException
        | TransientDataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    } catch (CanonicalGameplayBindingInventoryConflictException
        | IllegalArgumentException conflict) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (IllegalStateException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (RuntimeException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }

    final ReadOriginalNewFinalAdmissionResponse response;
    try {
      response =
          CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toResponse(selector, result);
    } catch (RuntimeException invalidOwnerResult) {
      fail(observer, Status.INTERNAL);
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) return true;
      }
    }
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription(FAILURE_DESCRIPTION)
            .asRuntimeException());
  }
}
