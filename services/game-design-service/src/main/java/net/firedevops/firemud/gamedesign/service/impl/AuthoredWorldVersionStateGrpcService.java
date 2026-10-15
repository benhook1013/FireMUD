package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Standalone handler for the source-qualified version-state RPC.
 *
 * <p>This handler is deliberately not registered as a Spring gRPC service. The authenticated World
 * intake remains disabled until the owning orchestrator explicitly wires and proves this boundary.
 */
public final class AuthoredWorldVersionStateGrpcService
    extends AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceImplBase {
  private final AuthoredWorldVersionStateService service;
  private final String workloadNamespace;

  public AuthoredWorldVersionStateGrpcService(
      AuthoredWorldVersionStateService service, String workloadNamespace) {
    this.service = Objects.requireNonNull(service, "service");
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void getAuthoredWorldVersionState(
      GetAuthoredWorldVersionStateRequest request,
      StreamObserver<GetAuthoredWorldVersionStateResponse> responseObserver) {
    if (SessionContext.hasAuthenticatedCallerContext() || !isWorldManagementPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified same-namespace World Management identity is required")
              .asRuntimeException());
      return;
    }

    final net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence.Request
        decodedRequest;
    try {
      decodedRequest = AuthoredWorldVersionStateGrpcCodec.fromRequest(request);
      if (!workloadNamespace.equals(decodedRequest.targetNamespace())) {
        throw new IllegalArgumentException(
            "Target namespace does not match this Game Design owner");
      }
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical authored-world version-state request is required")
              .asRuntimeException());
      return;
    }

    try {
      var evidence = service.read(decodedRequest);
      responseObserver.onNext(AuthoredWorldVersionStateGrpcCodec.toResponse(evidence));
      responseObserver.onCompleted();
    } catch (AuthoredWorldVersionStateService.NotFoundException exception) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No version state exists for the exact authored-world source")
              .asRuntimeException());
    } catch (GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException
        | IllegalArgumentException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source or version ownership is inconsistent")
              .asRuntimeException());
    } catch (org.jooq.exception.TooManyRowsException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source or version ownership is ambiguous")
              .asRuntimeException());
    } catch (DataAccessResourceFailureException | TransientDataAccessException exception) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design owner storage is temporarily unavailable")
              .asRuntimeException());
    } catch (DataAccessException exception) {
      Status.Code code =
          hasConnectionFailureSqlState(exception) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Game Design owner storage is temporarily unavailable"
                      : "Game Design owner storage could not be read")
              .asRuntimeException());
    } catch (org.jooq.exception.DataAccessException exception) {
      Status.Code code =
          hasConnectionFailureSqlState(exception) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Game Design owner storage is temporarily unavailable"
                      : "Game Design owner storage could not be read")
              .asRuntimeException());
    } catch (TransactionException exception) {
      Status storageStatus = AuthoredWorldSourceReadGrpcService.storageFailureStatus(exception);
      responseObserver.onError(
          storageStatus
              .withDescription(
                  storageStatus.getCode() == Status.Code.UNAVAILABLE
                      ? "Game Design owner snapshot is temporarily unavailable"
                      : "Game Design owner snapshot could not be read")
              .asRuntimeException());
    } catch (IllegalStateException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source or version state is invalid")
              .asRuntimeException());
    } catch (RuntimeException exception) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Game Design could not read authored-world version state")
              .asRuntimeException());
    }
  }

  private boolean isWorldManagementPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service");
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
