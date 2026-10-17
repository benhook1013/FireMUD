package net.firedevops.firemud.entitymanagement.security;

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

/** Certificate-only guard for canonical roster reads; unrelated RPCs pass through unchanged. */
public final class CanonicalGameplayRosterPeerInterceptor implements ServerInterceptor {
  public static final String METHOD_NAME =
      "entity_management.v1.CanonicalGameplayRosterService/ListPreseededRoster";
  public static final String SELECTED_ASSIGNMENT_METHOD_NAME =
      "entity_management.v1.CanonicalGameplayRosterService/ReadSelectedPreseededAssignment";
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_GAME_SESSION_PEER =
      Context.key("firemud-canonical-gameplay-roster-peer");

  private final String trustedNamespace;

  public CanonicalGameplayRosterPeerInterceptor(String trustedNamespace) {
    this.trustedNamespace =
        GrpcPeerIdentity.isValidNamespace(trustedNamespace) ? trustedNamespace : "";
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String methodName = call.getMethodDescriptor().getFullMethodName();
    if (!METHOD_NAME.equals(methodName) && !SELECTED_ASSIGNMENT_METHOD_NAME.equals(methodName)) {
      return next.startCall(call, headers);
    }

    GrpcPeerIdentity peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    if (!isTrustedGameSessionPeer(peer, trustedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      call.close(
          Status.PERMISSION_DENIED.withDescription("Trusted Game Session peer required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }

    Context context =
        Context.current()
            .withValue(VERIFIED_GAME_SESSION_PEER, peer)
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    return Contexts.interceptCall(context, call, headers, next);
  }

  public static boolean isTrustedGameSessionPeer(GrpcPeerIdentity peer, String expectedNamespace) {
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        && peer.isService("game-session-service")
        && peer.isInNamespace(expectedNamespace);
  }

  public static GrpcPeerIdentity currentVerifiedPeer() {
    return VERIFIED_GAME_SESSION_PEER.get();
  }
}
