package net.firedevops.firemud.entitymanagement.bootstrap;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/**
 * Purpose-specific certificate guard runs before protobuf decoding; no canonical GS allowlist
 * change.
 */
public final class AccountBootstrapRosterPeerInterceptor implements ServerInterceptor {
  public static final String METHOD_NAME =
      "entity_management.v1.AccountBootstrapRosterReadService/ReadAccountBootstrapRoster";
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_ACCOUNT =
      Context.key("firemud-account-bootstrap-roster-peer");
  private final String namespace;

  public AccountBootstrapRosterPeerInterceptor(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!METHOD_NAME.equals(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }
    var peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    if (peer == null) {
      call.close(
          Status.UNAUTHENTICATED.withDescription("Authenticated Account workload required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    if (!trusted(peer, namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      call.close(
          Status.PERMISSION_DENIED.withDescription("Exact Account workload required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return Contexts.interceptCall(
        Context.current()
            .withValue(VERIFIED_ACCOUNT, peer)
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer),
        call,
        headers,
        next);
  }

  static boolean trusted(GrpcPeerIdentity peer, String namespace) {
    return peer != null && peer.isService("account-service") && peer.isInNamespace(namespace);
  }

  static GrpcPeerIdentity currentVerifiedPeer() {
    return VERIFIED_ACCOUNT.get();
  }
}
