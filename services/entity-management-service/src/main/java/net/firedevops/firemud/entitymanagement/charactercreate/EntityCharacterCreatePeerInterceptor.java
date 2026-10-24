package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Unregistered certificate-only method allowlist; JWT/header claims never substitute for mTLS. */
public final class EntityCharacterCreatePeerInterceptor implements ServerInterceptor {
  private static final String PREFIX = "entity_management.v1.EntityCharacterCreateService/";
  private static final Set<String> OWNERS =
      Set.of("account-service", "game-session-service", "world-management-service");
  private static final Context.Key<GrpcPeerIdentity> VERIFIED_PEER =
      Context.key("entity-character-create-verified-peer");
  private final String namespace;

  public EntityCharacterCreatePeerInterceptor(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String method = call.getMethodDescriptor().getFullMethodName();
    if (!method.startsWith(PREFIX)) return next.startCall(call, headers);
    var peer =
        GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .orElse(null);
    boolean read = method.equals(PREFIX + "ReadEntityCharacterCreateTerminal");
    boolean mutation =
        method.equals(PREFIX + "CommitProtectedCharacterCreate")
            || method.equals(PREFIX + "AbortProtectedCharacterCreate");
    if ((!read && !mutation)
        || peer == null
        || !namespace.equals(peer.namespace())
        || !(read ? OWNERS.contains(peer.service()) : "account-service".equals(peer.service()))
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      call.close(
          Status.PERMISSION_DENIED.withDescription("Exact creation owner workload required"),
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

  static void requirePeer(String namespace, boolean mutation) {
    var peer = VERIFIED_PEER.get();
    if (peer == null
        || !namespace.equals(peer.namespace())
        || !(mutation ? "account-service".equals(peer.service()) : OWNERS.contains(peer.service()))
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact authenticated creation owner required")
          .asRuntimeException();
    }
  }
}
