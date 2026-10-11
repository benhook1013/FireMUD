package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Deliberately unregistered same-namespace World-only historical owner evidence reader. */
public final class HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService
    extends HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
        .HistoricalOriginalStartSessionOwnerEvidenceReadServiceImplBase {
  private static final String METHOD_NAME =
      "game_session.v1.HistoricalOriginalStartSessionOwnerEvidenceReadService/"
          + "ReadHistoricalOriginalStartSessionOwnerEvidence";
  private static final String WORLD_SERVICE = "world-management-service";

  private final GameSessionOriginalStartSessionSelectionPinService service;
  private final String workloadNamespace;

  public HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(
      GameSessionOriginalStartSessionSelectionPinService service, String workloadNamespace) {
    this.service = Objects.requireNonNull(service, "service");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readHistoricalOriginalStartSessionOwnerEvidence(
      ReadHistoricalOriginalStartSessionOwnerEvidenceRequest request,
      StreamObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> observer) {
    Objects.requireNonNull(observer, "observer");
    try {
      requireWorldManagementWorkload();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    if (request == null) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete historical original StartSession selector required")
              .asRuntimeException());
      return;
    }
    final net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence
            .Request
        decoded;
    try {
      decoded = HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete canonical historical StartSession selector required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.associationSelector().targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Historical selector differs from configured Game Session owner")
              .asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Independent Game Session historical snapshot required")
              .asRuntimeException());
      return;
    }
    try {
      var result = service.readHistorical(decoded);
      if (result.isEmpty()) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact retained historical StartSession pin is unavailable")
                .asRuntimeException());
        return;
      }
      var evidence = result.orElseThrow();
      if (!decoded.equals(evidence.request())) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Historical owner evidence differs from the complete selector")
                .asRuntimeException());
        return;
      }
      observer.onNext(HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toResponse(evidence));
      observer.onCompleted();
    } catch (DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Session historical owner storage unavailable")
              .asRuntimeException());
    } catch (IllegalArgumentException | IllegalStateException incompleteOrConflicting) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete exact historical StartSession evidence is unavailable")
              .asRuntimeException());
    }
  }

  private void requireWorldManagementWorkload() {
    var peer = GrpcPeerIdentity.current();
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !METHOD_NAME.equals(
            HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
                .getReadHistoricalOriginalStartSessionOwnerEvidenceMethod()
                .getFullMethodName())
        || peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified same-namespace World Management workload required")
          .asRuntimeException();
    }
    if (!WORLD_SERVICE.equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/" + WORLD_SERVICE).equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace World Management workload without end-user context required")
          .asRuntimeException();
    }
  }
}
