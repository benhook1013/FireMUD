package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalReadServiceGrpc;

/** Standalone Account-authenticated transport; deliberately not registered at runtime. */
public final class WorldPublicationTerminalReadGrpcService
    extends WorldPublicationTerminalReadServiceGrpc.WorldPublicationTerminalReadServiceImplBase {
  private final WorldPublicationTerminalReadService owner;
  private final String workloadNamespace;

  public WorldPublicationTerminalReadGrpcService(
      WorldPublicationTerminalReadService owner, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readPublicationTerminal(
      ReadPublicationTerminalRequest request,
      StreamObserver<ReadPublicationTerminalResponse> observer) {
    try {
      requireAuthenticatedAccountPeer();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    final WorldPublicationTerminalReadEvidence.Request decoded;
    try {
      // Do not parse the supplied operation or terminal until the Account workload is
      // authenticated.
      decoded = WorldPublicationTerminalReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical World publication terminal read is required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("World publication terminal namespace differs from the owner")
              .asRuntimeException());
      return;
    }

    final ReadPublicationTerminalResponse response;
    try {
      GameDesignPublicationTerminalEvidence terminal = owner.read(decoded);
      response = WorldPublicationTerminalReadGrpcCodec.toResponse(decoded, terminal);
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
      return;
    } catch (IllegalArgumentException inconsistent) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World publication terminal evidence is inconsistent")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      observer.onError(
          Status.INTERNAL
              .withDescription("World publication terminal read failed")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private void requireAuthenticatedAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service").equals(peer.uri())
        || !peer.isService("account-service")
        || !peer.isInNamespace(workloadNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Account workload required")
          .asRuntimeException();
    }
  }
}
