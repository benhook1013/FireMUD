package net.firedevops.firemud.gamedesign.draft;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.GameDesignSelectedDraftPublicationReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationResponse;

/**
 * Standalone, unregistered immutable selection owner read. Does not inspect mutable Version state
 * or establish actor authorization, source holds, World freeze, release, or runtime admission.
 */
public final class GameDesignSelectedDraftPublicationReadGrpcService
    extends GameDesignSelectedDraftPublicationReadServiceGrpc
        .GameDesignSelectedDraftPublicationReadServiceImplBase {
  private final AuthoredDraftPublishSelectionRepository repository;
  private final String trustedNamespace;

  public GameDesignSelectedDraftPublicationReadGrpcService(
      AuthoredDraftPublishSelectionRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readSelectedDraftPublication(
      ReadSelectedDraftPublicationRequest request,
      StreamObserver<ReadSelectedDraftPublicationResponse> observer) {
    try {
      requirePeer();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    final AuthoredDraftPublishSelectionReadEvidence.Request decoded;
    try {
      decoded = AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical selected Draft publication read required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Selection namespace differs from peer")
              .asRuntimeException());
      return;
    }
    final ReadSelectedDraftPublicationResponse response;
    try {
      var intent = decoded.binding().intent();
      var retained =
          repository.read(
              intent.canonicalTenantId(), intent.canonicalVersionId(), intent.publishRequestId());
      if (retained.isEmpty()) {
        throw Status.NOT_FOUND
            .withDescription("Exact selected Draft publication unavailable")
            .asRuntimeException();
      }
      var selection = retained.orElseThrow().selection();
      if (!Arrays.equals(selection.canonicalBytes(), decoded.originalSelection())
          || !selection.digest().equals(decoded.selectionDigest())) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Retained selection differs from request")
            .asRuntimeException();
      }
      response = AuthoredDraftPublishSelectionReadGrpcCodec.toSelectedResponse(decoded);
    } catch (StatusRuntimeException failure) {
      observer.onError(
          Status.fromCode(Status.fromThrowable(failure).getCode())
              .withDescription("Selected Draft publication read denied or unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design selection storage unavailable")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + trustedNamespace + "/sa/account-service").equals(peer.uri())
        && !("spiffe://firemud/ns/" + trustedNamespace + "/sa/world-management-service")
            .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Account or World Management workload required")
          .asRuntimeException();
    }
  }
}
