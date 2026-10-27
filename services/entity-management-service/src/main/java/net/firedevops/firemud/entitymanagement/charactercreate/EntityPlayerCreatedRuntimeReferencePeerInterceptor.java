package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact-method certificate allowlist for the manually bound runtime reference reads. */
public final class EntityPlayerCreatedRuntimeReferencePeerInterceptor implements ServerInterceptor {
  private static final String PREFIX =
      "entity_management.v1.CanonicalPlayerCreatedRuntimeReferenceService/";
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_PEER =
      Context.key("entity-player-created-runtime-reference-verified-peer");

  private final String namespace;
  private final String method;
  private final String ownerService;

  public EntityPlayerCreatedRuntimeReferencePeerInterceptor(
      String namespace, String method, String ownerService) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical namespace required");
    }
    if (!"ReadPlayerCreatedActorPlacementReceipt".equals(method)
        && !"ReadPlayerCreatedActorRuntimeReference".equals(method)) {
      throw new IllegalArgumentException("Exact runtime reference method required");
    }
    if (!"world-management-service".equals(ownerService)
        && !"game-session-service".equals(ownerService)) {
      throw new IllegalArgumentException("Exact runtime reference owner required");
    }
    this.namespace = namespace;
    this.method = PREFIX + method;
    this.ownerService = ownerService;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!method.equals(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }
    var peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    if (peer == null
        || !namespace.equals(peer.namespace())
        || !ownerService.equals(peer.service())
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      call.close(
          Status.PERMISSION_DENIED.withDescription("Exact runtime reference owner required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return Contexts.interceptCall(
        Context.current()
            .withValue(VERIFIED_PEER, peer)
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer),
        call,
        headers,
        next);
  }

  static void requirePeer(String namespace, String rpcMethod) {
    Objects.requireNonNull(namespace, "namespace");
    String requiredService =
        switch (rpcMethod) {
          case "ReadPlayerCreatedActorPlacementReceipt" -> "world-management-service";
          case "ReadPlayerCreatedActorRuntimeReference" -> "game-session-service";
          default -> throw new IllegalArgumentException("Exact runtime reference method required");
        };
    var peer = VERIFIED_PEER.get();
    if (peer == null
        || !namespace.equals(peer.namespace())
        || !requiredService.equals(peer.service())
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact authenticated runtime reference owner required")
          .asRuntimeException();
    }
  }
}
