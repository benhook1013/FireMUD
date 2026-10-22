package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Standalone receiver: authenticate exact World before parsing a caller-carried binding. */
public final class AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService
    extends AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc
        .AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceImplBase {
  private final AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService owner;
  private final String namespace;

  public AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService(
      AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "World closure authorization reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readHeldSelectedOwnerWorldClosureAuthorization(
      ReadHeldSelectedOwnerWorldClosureAuthorizationRequest request,
      StreamObserver<ReadHeldSelectedOwnerWorldClosureAuthorizationResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    String peerUri = peer.uri();
    String expectedReader = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !expectedReader.equals(peerUri)) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(request.getTargetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    final SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request decoded;
    try {
      decoded = SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromRequest(request);
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
      observer.onNext(
          SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toHeldResponse(decoded));
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
