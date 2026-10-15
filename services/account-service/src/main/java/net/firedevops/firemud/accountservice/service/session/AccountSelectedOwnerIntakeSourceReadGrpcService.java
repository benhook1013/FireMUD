package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadProtoCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Standalone preliminary Entity/Automation source permission receiver; never registered. */
public final class AccountSelectedOwnerIntakeSourceReadGrpcService
    extends AccountSelectedOwnerIntakeSourceReadServiceGrpc
        .AccountSelectedOwnerIntakeSourceReadServiceImplBase {
  private final AccountSelectedOwnerIntakeSourceReadService owner;
  private final String namespace;

  public AccountSelectedOwnerIntakeSourceReadGrpcService(
      AccountSelectedOwnerIntakeSourceReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "selected-owner source reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readSourceScope(
      SelectedOwnerIntakeSourcePermissionRequest request,
      StreamObserver<SelectedOwnerIntakeSourcePermissionResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (request == null
        || request.getSerializedSize() > SelectedOwnerIntakeSourceReadProtoCodec.MAX_WIRE_BYTES) {
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
      decoded = SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(decoded.targetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    try {
      var confirmed =
          owner.readSourceScope(decoded.scope(), decoded.intendedReader(), decoded.purpose());
      if (confirmed == null
          || !java.util.Arrays.equals(decoded.scope().canonicalBytes(), confirmed.canonicalBytes())
          || !decoded.scope().digest().equals(confirmed.digest())) {
        observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        return;
      }
      observer.onNext(SelectedOwnerIntakeSourceReadProtoCodec.toResponse(decoded));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      var code = Status.fromThrowable(failure).getCode();
      if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.FAILED_PRECONDITION) {
        observer.onError(Status.fromCode(code).asRuntimeException());
      } else {
        observer.onError(Status.UNAVAILABLE.asRuntimeException());
      }
    } catch (IllegalArgumentException | IllegalStateException staleOrChanged) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
