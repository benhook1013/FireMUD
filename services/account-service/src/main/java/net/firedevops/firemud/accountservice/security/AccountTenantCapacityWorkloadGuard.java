package net.firedevops.firemud.accountservice.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Sole producer of the receiver's private exact-method TLS caller assertion. */
public final class AccountTenantCapacityWorkloadGuard implements ServerInterceptor {
  private final Context.Key<String> caller = Context.key("tenant-capacity-authenticated-peer");
  private final Set<String> allowed;

  public AccountTenantCapacityWorkloadGuard(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    allowed =
        Set.of(
            TenantCapacityAdmission.expectedWorkload(namespace, Owner.GAME_SESSION),
            TenantCapacityAdmission.expectedWorkload(namespace, Owner.WORLD_MANAGEMENT));
  }

  public String requireAuthenticatedCaller() {
    String value = caller.get();
    if (!allowed.contains(value == null ? "" : value) || ambient())
      throw Status.UNAUTHENTICATED.asRuntimeException();
    return value;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    var peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
    if (peer.isEmpty()) return deny(call, Status.UNAUTHENTICATED);
    if (ambient()
        || !allowed.contains(peer.orElseThrow().uri())
        || !AccountServiceGrpc.getCommitTenantCapacityAdmissionMethod()
            .getFullMethodName()
            .equals(call.getMethodDescriptor().getFullMethodName()))
      return deny(call, Status.PERMISSION_DENIED);
    return Contexts.interceptCall(
        Context.current().withValue(caller, peer.orElseThrow().uri()), call, headers, next);
  }

  private static boolean ambient() {
    return SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null;
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(
      ServerCall<ReqT, RespT> call, Status status) {
    call.close(
        status.withDescription("Exact capacity owner workload and method required"),
        new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
