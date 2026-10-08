package net.firedevops.firemud.gamesession.config;

import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact same-namespace Account mTLS guard for the isolated Game Session Pod receiver. */
public final class GameSessionJwtReadinessProbeOwnerWorkloadGuard {
  private static final String ACCOUNT_SERVICE = "account-service";
  private static final String METHOD_NAME =
      "game_session.v1.GameSessionJwtReadinessReceiverService/ReceiveReadinessProbe";

  private final String trustedNamespace;

  public GameSessionJwtReadinessProbeOwnerWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace == null ? "" : trustedNamespace.trim();
  }

  /** Requires the exact Account workload peer and an otherwise empty application auth context. */
  public AuthenticatedCaller requireAccountReceiverCaller(String fullMethodName) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (!METHOD_NAME.equals(fullMethodName)
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peer == null
        || !ACCOUNT_SERVICE.equals(peer.service())
        || !trustedNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + ACCOUNT_SERVICE)
            .equals(peer.uri())
        || hasSessionContext()) {
      throw new ReceiverCallerDeniedException();
    }
    return new AuthenticatedCaller(peer);
  }

  /** Rejects peer or application-context drift across the crypto and owner-read sequence. */
  public void requireUnchanged(AuthenticatedCaller before, String fullMethodName) {
    Objects.requireNonNull(before, "Initial authenticated caller is required");
    AuthenticatedCaller after = requireAccountReceiverCaller(fullMethodName);
    if (!before.peer().equals(after.peer())) {
      throw new ReceiverCallerDeniedException();
    }
  }

  private static boolean hasSessionContext() {
    return SessionContext.getAccountId() != null
        || !SessionContext.getGlobalRoles().isEmpty()
        || !SessionContext.getScopedRolesMap().isEmpty()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null;
  }

  public record AuthenticatedCaller(GrpcPeerIdentity peer) {
    public AuthenticatedCaller {
      Objects.requireNonNull(peer);
    }
  }

  public static final class ReceiverCallerDeniedException extends SecurityException {
    public ReceiverCallerDeniedException() {
      super("Authenticated same-namespace Account workload is required");
    }
  }
}
