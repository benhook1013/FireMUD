package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionCurrentAttemptReadServiceGrpc;

/** Exact same-namespace Account workload authorization for the live original-attempt read. */
public final class OriginalStartSessionCurrentAttemptReadWorkloadGuard {
  private static final String METHOD_NAME =
      "game_session.v1.OriginalStartSessionCurrentAttemptReadService/"
          + "ReadOriginalStartSessionCurrentAttempt";
  private static final String ALLOWED_SERVICE = "account-service";

  private final String trustedNamespace;

  public OriginalStartSessionCurrentAttemptReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireAccountCurrentAttemptReadCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (!METHOD_NAME.equals(
            OriginalStartSessionCurrentAttemptReadServiceGrpc
                .getReadOriginalStartSessionCurrentAttemptMethod()
                .getFullMethodName())
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peer == null
        || !ALLOWED_SERVICE.equals(peer.service())
        || !trustedNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + ALLOWED_SERVICE)
            .equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Current original StartSession evidence requires the exact same-namespace Account workload without caller context");
    }
  }

  public void requireConfiguredTargetNamespace(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || !trustedNamespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Current original StartSession attempt target namespace is not configured here");
    }
  }
}
