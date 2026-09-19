package net.firedevops.firemud.common.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Makes the authenticated TLS peer identity available to gRPC handlers.
 *
 * <p>Identity extraction is intentionally non-blocking for unrelated RPCs. A missing, ambiguous, or
 * malformed identity is represented as an absent context value; a protected handler must then deny
 * the call through its explicit method guard.
 */
public final class GrpcPeerIdentityInterceptor implements ServerInterceptor {
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    GrpcPeerIdentity peerIdentity =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peerIdentity);
    return Contexts.interceptCall(context, call, headers, next);
  }
}
