package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadServiceGrpc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Standalone authenticated read-only adapter for exact World definitive-abort evidence.
 * Authentication is checked before decoding the binding or querying terminal storage. This type is
 * deliberately not registered as a runtime gRPC service in this slice.
 */
public final class WorldDraftTerminalReadGrpcService
    extends WorldDraftTerminalReadServiceGrpc.WorldDraftTerminalReadServiceImplBase {
  private final WorldDraftTerminalOutcomeRepository repository;
  private final String trustedNamespace;

  public WorldDraftTerminalReadGrpcService(
      WorldDraftTerminalOutcomeRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readWorldDraftTerminalOutcome(
      ReadWorldDraftTerminalOutcomeRequest request,
      StreamObserver<ReadWorldDraftTerminalOutcomeResponse> responseObserver) {
    if (!requireAuthenticatedAccountPeer(responseObserver)) {
      return;
    }

    WorldDraftTerminalReadEvidence.Request readRequest;
    try {
      readRequest = WorldDraftTerminalReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalidRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical World terminal read request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(readRequest.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("World terminal read namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    Optional<WorldDraftTerminalOutcome> outcome;
    try {
      outcome =
          repository.readDefinitiveAbort(trustedNamespace, readRequest.originalAccountBinding());
    } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal identity conflicts with retained owner evidence")
              .asRuntimeException());
      return;
    } catch (TransientDataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("World terminal readback is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException | org.jooq.exception.DataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("World terminal storage could not complete the read")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalidBinding) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical original Account binding is required")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("World terminal readback failed").asRuntimeException());
      return;
    }

    Optional<DraftAuthorizationFenceBinding.OwnerReadback> readback =
        outcome.map(WorldDraftTerminalReadGrpcService::toOwnerReadback);
    ReadWorldDraftTerminalOutcomeResponse response;
    try {
      response = WorldDraftTerminalReadGrpcCodec.toResponse(readRequest, readback);
    } catch (IllegalArgumentException invalidEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal readback is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedAccountPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null && peer.isService("account-service") && peer.isInNamespace(trustedNamespace)) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription("Verified same-namespace Account workload is required")
            .asRuntimeException());
    return false;
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback toOwnerReadback(
      WorldDraftTerminalOutcome outcome) {
    WorldDraftTerminalOperation operation = outcome.operation();
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        DraftAuthorizationFenceBinding.Owner.WORLD,
        DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED,
        operation.operationId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        operation.binding().digest(),
        operation.accountBindingBytes(),
        outcome.canonicalBytes());
  }
}
