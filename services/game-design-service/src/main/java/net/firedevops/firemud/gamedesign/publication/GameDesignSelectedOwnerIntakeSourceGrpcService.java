package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceProtoCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GameDesignSelectedOwnerIntakeSourceServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceRequest;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceResponse;

/** Unregistered complete selected-content endpoint; never supplies an owner retention grant. */
public final class GameDesignSelectedOwnerIntakeSourceGrpcService
    extends GameDesignSelectedOwnerIntakeSourceServiceGrpc
        .GameDesignSelectedOwnerIntakeSourceServiceImplBase {
  private final GameDesignSelectedOwnerIntakeSourceReadService owner;
  private final String namespace;

  public GameDesignSelectedOwnerIntakeSourceGrpcService(
      GameDesignSelectedOwnerIntakeSourceReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "selected-source owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void readSelectedSource(
      SelectedOwnerIntakeSourceRequest request,
      StreamObserver<SelectedOwnerIntakeSourceResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/account-service").equals(peer.uri())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (request == null
        || request.getSerializedSize() > SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(request.getTargetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    final net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence
            .Request
        decoded;
    try {
      decoded = SelectedOwnerIntakeSourceProtoCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      var export = owner.readSource(decoded.scope());
      if (export == null) {
        observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        return;
      }
      var content =
          SelectedOwnerIntakeSourceContent.fromStored(
              export.canonicalBytes(), decoded.scope(), export.digest());
      observer.onNext(SelectedOwnerIntakeSourceProtoCodec.toResponse(decoded, content));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      var code = Status.fromThrowable(failure).getCode();
      if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.FAILED_PRECONDITION) {
        observer.onError(Status.fromCode(code).asRuntimeException());
      } else {
        observer.onError(Status.UNAVAILABLE.asRuntimeException());
      }
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
