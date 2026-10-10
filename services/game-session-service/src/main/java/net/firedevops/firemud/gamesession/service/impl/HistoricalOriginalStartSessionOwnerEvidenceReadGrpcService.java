package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
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
    try {
      workloadGuard.requireHistoricalOwnerReadCaller();
      var decodedRequest =
          HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(request);
      workloadGuard.requireConfiguredTargetNamespace(
          decodedRequest.associationSelector().targetNamespace());
      var evidence =
          repository
              .readHistoricalOriginalStartSessionOwnerEvidence(decodedRequest)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Exact historical StartSession evidence is unavailable"));
      if (!decodedRequest.equals(evidence.request())) {
        throw new IllegalStateException(
            "Retained historical StartSession evidence changed the exact read request echo");
      }
      responseObserver.onNext(
          HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toResponse(evidence));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED.withDescription(denied.getMessage()).asRuntimeException());
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
    } catch (IllegalStateException unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(unavailable.getMessage())
              .asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("Historical evidence read failed").asRuntimeException());
    }
  }
}
