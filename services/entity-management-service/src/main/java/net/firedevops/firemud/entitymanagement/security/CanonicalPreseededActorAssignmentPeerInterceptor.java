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

/** Certificate-only guard for the dedicated run-owned PRESEEDED assignment method. */
public final class CanonicalPreseededActorAssignmentPeerInterceptor implements ServerInterceptor {
  public static final String METHOD_NAME =
      "entity_management.v1.CanonicalPreseededActorAssignmentService/AssignPreseededActor";
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_GAME_SESSION_PEER =
      Context.key("firemud-preseeded-actor-assignment-peer");

  private final String trustedNamespace;

  public CanonicalPreseededActorAssignmentPeerInterceptor(String trustedNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!METHOD_NAME.equals(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }

    GrpcPeerIdentity peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    if (!isTrustedGameSessionPeer(peer, trustedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
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

  public static GrpcPeerIdentity currentVerifiedPeer() {
    return VERIFIED_GAME_SESSION_PEER.get();
  }

  public static void requireVerifiedPeer(String expectedNamespace) {
    GrpcPeerIdentity peer = currentVerifiedPeer();
    if (!isTrustedGameSessionPeer(peer, expectedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Trusted Game Session peer required")
          .asRuntimeException();
    }
  }

  private static boolean isTrustedGameSessionPeer(GrpcPeerIdentity peer, String namespace) {
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(namespace)
        && peer.isInNamespace(namespace)
        && peer.isService("game-session-service")
        && peer.uri().equals("spiffe://firemud/ns/" + namespace + "/sa/game-session-service");
  }
}
