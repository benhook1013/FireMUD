package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

/** Standalone exact authored-source owner read; deliberately not registered as a gRPC service. */
public final class AuthoredWorldSourceReadGrpcService
    extends AuthoredWorldSourceReadServiceGrpc.AuthoredWorldSourceReadServiceImplBase {
  private final GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  private final String workloadNamespace;

  public AuthoredWorldSourceReadGrpcService(
      GameAuthoredWorldSourceRepository authoredWorldSourceRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.authoredWorldSourceRepository = authoredWorldSourceRepository;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveAuthoredWorldSource(
      ResolveAuthoredWorldSourceRequest request,
      StreamObserver<ResolveAuthoredWorldSourceResponse> responseObserver) {
    if (SessionContext.hasAuthenticatedCallerContext() || !isWorldManagementPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Verified same-namespace World Management workload identity without caller "
                      + "context is required")
              .asRuntimeException());
      return;
    }

    final AuthoredWorldSourceGrpcCodec.ReadRequest readRequest;
    try {
      readRequest = AuthoredWorldSourceGrpcCodec.fromReadRequest(workloadNamespace, request);
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical exact authored-world source request is required")
              .asRuntimeException());
      return;
    }

    Optional<AuthoredWorldSourceEvidence> resolved;
    try {
      resolved =
          authoredWorldSourceRepository.read(
              readRequest.operationId(),
              readRequest.canonicalTenantId(),
              readRequest.worldSlug(),
              workloadNamespace);
    } catch (GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException
        | GameAuthoredWorldSourceRepository.RegistrationConflictException
        | org.jooq.exception.TooManyRowsException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source evidence is incomplete or inconsistent")
              .asRuntimeException());
      return;
    } catch (RuntimeException exception) {
      Status storageStatus = storageFailureStatus(exception);
      responseObserver.onError(
          (storageStatus == null
                  ? Status.INTERNAL.withDescription(
                      "Authored-world source evidence could not be read")
                  : storageStatus)
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No authored-world source matches the exact selector")
              .asRuntimeException());
      return;
    }

    final ResolveAuthoredWorldSourceResponse response;
    try {
      response = AuthoredWorldSourceGrpcCodec.toReadResponse(readRequest, resolved.orElseThrow());
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source readback does not match the exact request")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  /** Returns the existing owner storage classification, or {@code null} for non-storage errors. */
  static Status storageFailureStatus(Throwable failure) {
    if (failure instanceof DataAccessResourceFailureException
        || failure instanceof TransientDataAccessException
        || failure instanceof TransactionException) {
      return Status.UNAVAILABLE.withDescription(
          "Authored-world source evidence is temporarily unavailable");
    }
    if (failure instanceof DataAccessException
        || failure instanceof org.jooq.exception.DataAccessException) {
      boolean connectionFailure = hasConnectionFailureSqlState(failure);
      return Status.fromCode(connectionFailure ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL)
          .withDescription(
              connectionFailure
                  ? "Authored-world source evidence is temporarily unavailable"
                  : "Authored-world source evidence could not be read");
    }
    return null;
  }

  private boolean isWorldManagementPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service");
  }

  static boolean hasConnectionFailureSqlState(Throwable failure) {
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
