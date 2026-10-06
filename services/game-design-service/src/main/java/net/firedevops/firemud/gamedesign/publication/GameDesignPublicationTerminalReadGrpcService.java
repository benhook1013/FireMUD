package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;

/** Standalone authenticated read adapter. Deliberately has no runtime registration. */
public final class GameDesignPublicationTerminalReadGrpcService
    extends GameDesignPublicationTerminalReadServiceGrpc
        .GameDesignPublicationTerminalReadServiceImplBase {
  private final GameDesignPublicationOperationService owner;
  private final String namespace;

  public GameDesignPublicationTerminalReadGrpcService(
      GameDesignPublicationOperationService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readGameDesignPublicationTerminal(
      ReadGameDesignPublicationTerminalRequest wire,
      StreamObserver<ReadGameDesignPublicationTerminalResponse> observer) {
    var peer = GrpcPeerIdentity.current();
    String prefix = "spiffe://firemud/ns/" + namespace + "/sa/";
    if (peer == null
        || !(peer.uri().equals(prefix + "account-service")
            || peer.uri().equals(prefix + "world-management-service"))
        || SessionContext.hasAuthenticatedCallerContext()) {
      fail(
          observer,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Account or World workload without end-user context required");
      return;
    }
    final ReadRequest request;
    try {
      request = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wire);
    } catch (RuntimeException malformed) {
      fail(
          observer,
          Status.INVALID_ARGUMENT,
          "Complete canonical publication terminal read request required");
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      fail(
          observer,
          Status.PERMISSION_DENIED,
          "Terminal read namespace differs from authenticated peer");
      return;
    }
    final ReadResult result;
    try {
      var operation = GameDesignPublicationOperation.fromStored(request.operationBytes());
      // readExact owns the independent read-only REPEATABLE_READ transaction and exact binding.
      var stored = owner.readExact(operation);
      if (stored.isEmpty() || stored.orElseThrow().outcome().equals("PENDING")) {
        result =
            new ReadResult(
                request,
                GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN,
                Optional.empty());
      } else {
        byte[] bytes = stored.orElseThrow().terminalEvidenceBytes();
        if (bytes == null)
          throw new IllegalStateException("Historical terminal result has no captured evidence");
        var terminal = GameDesignPublicationTerminalEvidence.fromStored(bytes);
        result =
            new ReadResult(
                request,
                GameDesignPublicationTerminalReadEvidence.Status.valueOf(
                    stored.orElseThrow().outcome()),
                Optional.of(terminal));
      }
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE, "Publication terminal owner storage unavailable");
      return;
    } catch (IllegalArgumentException | IllegalStateException conflict) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "Retained publication terminal evidence unavailable or inconsistent");
      return;
    } catch (RuntimeException unexpected) {
      fail(observer, Status.INTERNAL, "Publication terminal owner read failed");
      return;
    }
    observer.onNext(GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, result));
    observer.onCompleted();
  }

  private static void fail(StreamObserver<?> observer, Status status, String message) {
    observer.onError(status.withDescription(message).asRuntimeException());
  }
}
