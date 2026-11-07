package net.firedevops.firemud.gamesession.security;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.PrivateWorldPreparationReadServiceGrpc;

/** Exact World-only purpose; the separate Account lifecycle guard is unchanged. */
public final class PrivateWorldPreparationReadWorkloadGuard implements ServerInterceptor {
  private final String namespace;

  public PrivateWorldPreparationReadWorkloadGuard(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
  }

  public void requireWorldCaller() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!namespace.equals(peer.namespace())
        || !"world-management-service".equals(peer.service())
        || !("spiffe://firemud/ns/" + namespace + "/sa/world-management-service").equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null)
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!PrivateWorldPreparationReadServiceGrpc.getReadPrivateWorldPreparationMethod()
            .getFullMethodName()
            .equals(call.getMethodDescriptor().getFullMethodName())
        && !PrivateWorldPreparationReadServiceGrpc.getReadPrivateWorldPreparationBuildMethod()
            .getFullMethodName()
            .equals(call.getMethodDescriptor().getFullMethodName())) {
      call.close(Status.PERMISSION_DENIED, new Metadata());
      return new ServerCall.Listener<>() {};
    }
    try {
      requireWorldCaller();
    } catch (StatusRuntimeException denied) {
      call.close(Status.fromCode(denied.getStatus().getCode()), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return next.startCall(call, headers);
  }
}
