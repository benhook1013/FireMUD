package net.firedevops.firemud.gamedesign.draft;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeResponse;
import org.springframework.dao.DataAccessException;

/**
 * Standalone authenticated read-only adapter for exact Game Design Draft terminal evidence.
 * Authentication is checked before decoding the original Account binding or reading owner storage.
 * This type is deliberately not registered as a runtime gRPC service in this slice.
 */
public final class GameDesignDraftTerminalReadGrpcService
    extends GameDesignDraftTerminalReadServiceGrpc.GameDesignDraftTerminalReadServiceImplBase {
  private final GameDesignDraftTerminalOutcomeRepository repository;
  private final String trustedNamespace;

  public GameDesignDraftTerminalReadGrpcService(
      GameDesignDraftTerminalOutcomeRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("Game Design workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readGameDesignDraftTerminalOutcome(
      ReadGameDesignDraftTerminalOutcomeRequest request,
      StreamObserver<ReadGameDesignDraftTerminalOutcomeResponse> responseObserver) {
    if (!requireAuthenticatedAccountPeer(responseObserver)) {
      return;
    }

    final GameDesignDraftTerminalReadEvidence.Request readRequest;
    try {
      readRequest = GameDesignDraftTerminalReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalidRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical Game Design terminal read request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(readRequest.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Game Design terminal read namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    final Optional<GameDesignDraftTerminalOutcome> outcome;
    try {
      outcome = repository.readAccountBound(readRequest.originalAccountBinding());
    } catch (DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException conflict) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Game Design terminal identity conflicts with retained owner evidence")
              .asRuntimeException());
      return;
    } catch (DataAccessException | org.jooq.exception.DataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design terminal storage could not complete the read")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalidBinding) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical original Account binding is required")
              .asRuntimeException());
      return;
    } catch (IllegalStateException inconsistentEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design terminal evidence is inconsistent")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Game Design terminal readback failed")
              .asRuntimeException());
      return;
    }

    Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback =
        outcome.map(GameDesignDraftTerminalOutcome::toOwnerReadback);
    final ReadGameDesignDraftTerminalOutcomeResponse response;
    try {
      response = GameDesignDraftTerminalReadGrpcCodec.toResponse(readRequest, ownerReadback);
    } catch (IllegalArgumentException invalidEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design terminal readback is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedAccountPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedPeer = "spiffe://firemud/ns/" + trustedNamespace + "/sa/account-service";
    if (peer != null && expectedPeer.equals(peer.uri())) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription("Verified same-namespace Account workload is required")
            .asRuntimeException());
    return false;
  }
}
