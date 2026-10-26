package net.firedevops.firemud.worldmanagement.tenant;

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

/** Exact same-namespace Game Session certificate guard for initial-location owner RPCs. */
public final class CanonicalInitialPlayerLocationPeerInterceptor implements ServerInterceptor {
  public static final String PLACE_METHOD_NAME =
      "world_management.v1.CanonicalInitialPlayerLocationService/PlaceInitialPlayer";
  public static final String READ_METHOD_NAME =
      "world_management.v1.CanonicalInitialPlayerLocationService/ReadInitialPlayerLocation";
  public static final String READ_REGION_METHOD_NAME =
      "world_management.v1.CanonicalInitialPlayerLocationService/ReadInitialPlayerLocationRegion";
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_GAME_SESSION_PEER =
      Context.key("firemud-canonical-initial-player-location-peer");

  private final String trustedNamespace;

  public CanonicalInitialPlayerLocationPeerInterceptor(String trustedNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String methodName = call.getMethodDescriptor().getFullMethodName();
    if (!PLACE_METHOD_NAME.equals(methodName)
        && !READ_METHOD_NAME.equals(methodName)
        && !READ_REGION_METHOD_NAME.equals(methodName)) {
      return next.startCall(call, headers);
    }

    GrpcPeerIdentity peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    if (!isTrustedGameSessionPeer(peer, trustedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      Status status = peer == null ? Status.UNAUTHENTICATED : Status.PERMISSION_DENIED;
      call.close(
          status.withDescription("Exact same-namespace Game Session workload required"),
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
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }

  private static boolean isTrustedGameSessionPeer(GrpcPeerIdentity peer, String namespace) {
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(namespace)
        && peer.isService("game-session-service")
        && peer.isInNamespace(namespace)
        && peer.uri().equals("spiffe://firemud/ns/" + namespace + "/sa/game-session-service");
  }
}
