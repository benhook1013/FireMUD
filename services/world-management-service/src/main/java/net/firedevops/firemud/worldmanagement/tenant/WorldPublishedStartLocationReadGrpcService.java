package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationGrpcCodec;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedStartLocationReadServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;

/** Standalone authenticated read-only adapter; intentionally not registered as a runtime RPC. */
public final class WorldPublishedStartLocationReadGrpcService
    extends WorldPublishedStartLocationReadServiceGrpc
        .WorldPublishedStartLocationReadServiceImplBase {
  private final WorldPublishedStartLocationRepository repository;
  private final String trustedNamespace;

  public WorldPublishedStartLocationReadGrpcService(
      WorldPublishedStartLocationRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readWorldPublishedStartLocation(
      ReadWorldPublishedStartLocationRequest request,
      StreamObserver<ReadWorldPublishedStartLocationResponse> responseObserver) {
    if (!requireAuthenticatedGameDesignPeer(responseObserver)) return;

    WorldPublishedStartLocationEvidence.Request readRequest;
    try {
      readRequest = WorldPublishedStartLocationGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical World published selector request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(readRequest.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("World selector namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    Optional<WorldPublishedStartLocationSource> stored;
    try {
      stored = repository.readCommitted(toCaptureRequest(readRequest));
    } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World selector conflicts with exact frozen or APPLIED evidence")
              .asRuntimeException());
      return;
    } catch (TransientDataAccessException | DataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("World published selector storage is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("World published selector read failed")
              .asRuntimeException());
      return;
    }
    if (stored.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No exact frozen World selector matches the complete selection")
              .asRuntimeException());
      return;
    }

    ReadWorldPublishedStartLocationResponse response;
    try {
      WorldPublishedStartLocationSource source = stored.orElseThrow();
      var receipt = source.selectorReceipt();
      byte[] receiptBytes =
          new WorldDraftStartLocationEvidence(
                  receipt.targetNamespace(),
                  receipt.operationId(),
                  receipt.requestId(),
                  receipt.commitId(),
                  receipt.authorizationFenceId(),
                  receipt.accountBindingDigest(),
                  receipt.bindingDigest(),
                  receipt.startLocation(),
                  receipt.graphDigest(),
                  receipt.receiptDigest())
              .canonicalBytes();
      var evidence =
          new WorldPublishedStartLocationEvidence(
              readRequest,
              receiptBytes,
              source.appliedResult().application().operation().accountBindingBytes(),
              source.appliedResult().canonicalBytes());
      response = WorldPublishedStartLocationGrpcCodec.toResponse(readRequest, evidence);
    } catch (IllegalArgumentException | IllegalStateException invalidEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World published selector evidence is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedGameDesignPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-design-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription(
                "Only the verified same-namespace Game Design workload without end-user context is allowed")
            .asRuntimeException());
    return false;
  }

  private static CaptureRequest toCaptureRequest(
      WorldPublishedStartLocationEvidence.Request request) {
    return new CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        request.worldAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList());
  }
}
