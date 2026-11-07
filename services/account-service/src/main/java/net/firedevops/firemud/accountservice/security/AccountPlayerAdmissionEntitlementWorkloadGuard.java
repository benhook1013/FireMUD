package net.firedevops.firemud.accountservice.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.firedevops.firemud.account.v1.AccountPlayerAdmissionEntitlementReadServiceGrpc;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/**
 * Exact method/Game Session gate deriving its private assertion from transport TLS, never caller
 * context.
 */
public final class AccountPlayerAdmissionEntitlementWorkloadGuard implements ServerInterceptor {
  private final Context.Key<String> authenticatedCaller =
      Context.key("player-admission-game-session-peer");
  private final String expectedWorkload;

  public AccountPlayerAdmissionEntitlementWorkloadGuard(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    expectedWorkload = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
  }

  /** Only this guard's TLS interception can supply the private method-authorized assertion. */
  public String requireAuthenticatedGameSessionCaller() {
    if (!expectedWorkload.equals(authenticatedCaller.get()) || hasSessionContext()) {
      throw Status.UNAUTHENTICATED
          .withDescription("Protected Game Session workload call required")
          .asRuntimeException();
    }
    return expectedWorkload;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    var peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
    if (peer.isEmpty()) return deny(call, Status.UNAUTHENTICATED);
    if (!expectedWorkload.equals(peer.orElseThrow().uri())
        || hasSessionContext()
        || !AccountPlayerAdmissionEntitlementReadServiceGrpc
            .getReadPlayerAdmissionEntitlementMethod()
            .getFullMethodName()
            .equals(call.getMethodDescriptor().getFullMethodName())) {
      return deny(call, Status.PERMISSION_DENIED);
    }
    return Contexts.interceptCall(
        Context.current().withValue(authenticatedCaller, peer.orElseThrow().uri()),
        call,
        headers,
        next);
  }

  private static boolean hasSessionContext() {
    return SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null;
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(
      ServerCall<ReqT, RespT> call, Status status) {
    call.close(
        status.withDescription("Exact protected Game Session entitlement method required"),
        new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
