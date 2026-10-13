package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalResponse;

/** Standalone, unregistered transport over the actual complete terminal owner snapshot read. */
public final class GameDesignPublicationTerminalReadGrpcService
    extends GameDesignPublicationTerminalReadServiceGrpc
        .GameDesignPublicationTerminalReadServiceImplBase {
  private final GameDesignPublicationTerminalReadService owner;
  private final String workloadNamespace;

  public GameDesignPublicationTerminalReadGrpcService(
      GameDesignPublicationTerminalReadService owner, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readPublicationTerminal(
      ReadPublicationTerminalRequest request,
      StreamObserver<ReadPublicationTerminalResponse> observer) {
    try {
      requirePeer();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    final GameDesignPublicationTerminalReadEvidence.Request decoded;
    try {
      decoded = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical complete publication terminal read required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Publication terminal namespace differs from peer")
              .asRuntimeException());
      return;
    }
    final ReadPublicationTerminalResponse response;
    try {
      var terminal = owner.read(decoded.targetNamespace(), decoded.originalOperation());
      response = GameDesignPublicationTerminalReadGrpcCodec.toResponse(decoded, terminal);
    } catch (StatusRuntimeException failure) {
      observer.onError(
          Status.fromCode(failure.getStatus().getCode())
              .withDescription("Publication terminal owner read denied or unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Complete publication terminal evidence unavailable")
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
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service").equals(peer.uri())
        && !("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")
            .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Account or World Management workload required")
          .asRuntimeException();
    }
  }
}
