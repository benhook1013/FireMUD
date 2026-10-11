package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionAdmissionTerminalReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Deliberately unregistered Account-facing reader for exact original-admission terminal proof. */
public final class OriginalStartSessionAdmissionTerminalReadGrpcService
    extends OriginalStartSessionAdmissionTerminalReadServiceGrpc
        .OriginalStartSessionAdmissionTerminalReadServiceImplBase {
  private static final String METHOD_NAME =
      "game_session.v1.OriginalStartSessionAdmissionTerminalReadService/"
          + "ReadOriginalStartSessionAdmissionTerminal";
  private static final String ACCOUNT_SERVICE = "account-service";

  private final GameSessionCanonicalInitialAdmissionRepository repository;
  private final String workloadNamespace;

  public OriginalStartSessionAdmissionTerminalReadGrpcService(
      GameSessionCanonicalInitialAdmissionRepository repository, String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readOriginalStartSessionAdmissionTerminal(
      ReadOriginalStartSessionAdmissionTerminalRequest request,
      StreamObserver<ReadOriginalStartSessionAdmissionTerminalResponse> observer) {
    Objects.requireNonNull(observer, "observer");

    try {
      // Authenticate the exact Account workload before inspecting request bytes or storage.
      requireAccountWorkload();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    if (request == null) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete original-admission terminal selector required")
              .asRuntimeException());
      return;
    }

    final net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest
        decoded;
    try {
      decoded = OriginalStartSessionAdmissionTerminalGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete canonical original-admission terminal selector required")
              .asRuntimeException());
      return;
    }

    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Terminal selector namespace differs from configured owner")
              .asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Independent Game Session terminal snapshot required")
              .asRuntimeException());
      return;
    }

    try {
      var retained = repository.readTerminal(decoded);
      if (retained.isEmpty()) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact terminal owner evidence is unavailable")
                .asRuntimeException());
        return;
      }
      var result = retained.orElseThrow();
      if (!decoded.equals(result.request())) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Terminal owner evidence differs from complete Account evidence")
                .asRuntimeException());
        return;
      }
      observer.onNext(OriginalStartSessionAdmissionTerminalGrpcCodec.toResponse(result));
      observer.onCompleted();
    } catch (DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Session terminal owner storage unavailable")
              .asRuntimeException());
    } catch (IllegalArgumentException | IllegalStateException incompleteOrConflicting) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete exact terminal owner evidence is unavailable")
              .asRuntimeException());
    }
  }

  private void requireAccountWorkload() {
    var peer = GrpcPeerIdentity.current();
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !METHOD_NAME.equals(
            OriginalStartSessionAdmissionTerminalReadServiceGrpc
                .getReadOriginalStartSessionAdmissionTerminalMethod()
                .getFullMethodName())
        || peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified same-namespace Account workload required")
          .asRuntimeException();
    }
    if (!ACCOUNT_SERVICE.equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/" + ACCOUNT_SERVICE)
            .equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace Account workload without end-user context required")
          .asRuntimeException();
    }
  }
}
