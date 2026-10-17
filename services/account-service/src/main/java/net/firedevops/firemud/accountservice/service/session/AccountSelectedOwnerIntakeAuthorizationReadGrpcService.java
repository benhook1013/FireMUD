package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerIntakeAuthorizationResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Standalone receiver: authenticate the exact owner workload before parsing or looking up data. */
public final class AccountSelectedOwnerIntakeAuthorizationReadGrpcService
    extends AccountSelectedOwnerIntakeAuthorizationReadServiceGrpc
        .AccountSelectedOwnerIntakeAuthorizationReadServiceImplBase {
  private final AccountSelectedOwnerIntakeAuthorizationReadService owner;
  private final String namespace;

  public AccountSelectedOwnerIntakeAuthorizationReadGrpcService(
      AccountSelectedOwnerIntakeAuthorizationReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "selected-owner authorization reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readHeldSelectedOwnerIntakeAuthorization(
      ReadHeldSelectedOwnerIntakeAuthorizationRequest request,
      StreamObserver<ReadHeldSelectedOwnerIntakeAuthorizationResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    String peerUri = peer.uri();
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !(peerUri.equals("spiffe://firemud/ns/" + namespace + "/sa/entity-management-service")
            || peerUri.equals(
                "spiffe://firemud/ns/" + namespace + "/sa/automation-scripting-service"))) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > SelectedOwnerIntakeAuthorizationReadGrpcCodec.MAX_WIRE_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(request.getTargetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    final SelectedOwnerIntakeAuthorizationReadEvidence.Request decoded;
    try {
      decoded = SelectedOwnerIntakeAuthorizationReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(decoded.targetNamespace()) || !peerUri.equals(decoded.intendedReader())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    try {
      owner.requireHeld(decoded);
      observer.onNext(SelectedOwnerIntakeAuthorizationReadGrpcCodec.toHeldResponse(decoded));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      Status.Code code = Status.fromThrowable(failure).getCode();
      if (code != Status.Code.UNAUTHENTICATED
          && code != Status.Code.PERMISSION_DENIED
          && code != Status.Code.FAILED_PRECONDITION) {
        code = Status.Code.UNAVAILABLE;
      }
      observer.onError(Status.fromCode(code).asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
