package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceResponse;

/**
 * Directly constructible, deliberately unregistered historical evidence producer.
 *
 * <p>This service has no Spring registration annotation and is not added to active gRPC wiring.
 */
public final class HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService
    extends HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
        .HistoricalOriginalStartSessionOwnerEvidenceReadServiceImplBase {
  private final CanonicalGameInstanceLaunchAssociationRepository repository;
  private final HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard workloadGuard;

  public HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(
      CanonicalGameInstanceLaunchAssociationRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.workloadGuard =
        new HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard(trustedNamespace);
  }

  @Override
  public void readHistoricalOriginalStartSessionOwnerEvidence(
      ReadHistoricalOriginalStartSessionOwnerEvidenceRequest request,
      StreamObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> responseObserver) {
    Objects.requireNonNull(responseObserver, "responseObserver");
    HistoricalOriginalStartSessionOwnerEvidence.Request decodedRequest;
    try {
      workloadGuard.requireHistoricalOwnerReadCaller();
      decodedRequest = HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(request);
      workloadGuard.requireConfiguredTargetNamespace(
          decodedRequest.associationSelector().targetNamespace());
    } catch (AdminAuthorizationException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Historical evidence caller is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Historical evidence request is malformed")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("Historical evidence read failed").asRuntimeException());
      return;
    }

    ReadHistoricalOriginalStartSessionOwnerEvidenceResponse response;
    boolean unavailable;
    try {
      Optional<HistoricalOriginalStartSessionOwnerEvidence.Result> retained =
          repository.readHistoricalOriginalStartSessionOwnerEvidence(decodedRequest);
      unavailable = retained.isEmpty();
      if (unavailable) {
        response = null;
      } else {
        var evidence = retained.orElseThrow();
        unavailable = !decodedRequest.equals(evidence.request());
        response =
            unavailable
                ? null
                : HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toResponse(evidence);
      }
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("Historical evidence read failed").asRuntimeException());
      return;
    }

    if (unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact historical StartSession evidence is unavailable")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
