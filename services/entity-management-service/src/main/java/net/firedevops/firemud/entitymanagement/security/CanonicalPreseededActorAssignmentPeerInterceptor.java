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

/** Method-specific certificate guards for PRESEEDED assignment and receipt access. */
public final class CanonicalPreseededActorAssignmentPeerInterceptor implements ServerInterceptor {
  public static final String METHOD_NAME =
      "entity_management.v1.CanonicalPreseededActorAssignmentService/AssignPreseededActor";
  public static final String RECEIPT_READ_METHOD_NAME =
      "entity_management.v1.CanonicalPreseededActorAssignmentService/"
          + "ReadPreseededActorAssignmentReceipt";
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_GAME_SESSION_PEER =
      Context.key("firemud-preseeded-actor-assignment-peer");
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_WORLD_PEER =
      Context.key("firemud-preseeded-actor-assignment-receipt-peer");

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
    String methodName = call.getMethodDescriptor().getFullMethodName();
    boolean assignmentWrite = METHOD_NAME.equals(methodName);
    boolean receiptRead = RECEIPT_READ_METHOD_NAME.equals(methodName);
    if (!assignmentWrite && !receiptRead) {
      return next.startCall(call, headers);
    }

    GrpcPeerIdentity peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    boolean trustedPeer =
        assignmentWrite
            ? isTrustedGameSessionPeer(peer, trustedNamespace)
            : isTrustedWorldPeer(peer, trustedNamespace);
    if (!trustedPeer
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      call.close(
          Status.PERMISSION_DENIED.withDescription(
              assignmentWrite
                  ? "Trusted Game Session peer required"
                  : "Trusted World peer required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }

    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    context =
        assignmentWrite
            ? context.withValue(VERIFIED_GAME_SESSION_PEER, peer)
            : context.withValue(VERIFIED_WORLD_PEER, peer);
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

  public static GrpcPeerIdentity currentVerifiedWorldPeer() {
    return VERIFIED_WORLD_PEER.get();
  }

  public static void requireVerifiedWorldPeer(String expectedNamespace) {
    GrpcPeerIdentity peer = currentVerifiedWorldPeer();
    if (!isTrustedWorldPeer(peer, expectedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Trusted World peer required")
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

  private static boolean isTrustedWorldPeer(GrpcPeerIdentity peer, String namespace) {
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(namespace)
        && peer.isInNamespace(namespace)
        && peer.isService("world-management-service")
        && peer.uri().equals("spiffe://firemud/ns/" + namespace + "/sa/world-management-service");
  }
}
