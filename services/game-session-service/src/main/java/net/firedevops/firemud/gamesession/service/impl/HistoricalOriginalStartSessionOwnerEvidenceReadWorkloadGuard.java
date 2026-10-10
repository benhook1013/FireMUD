package net.firedevops.firemud.gamesession.service.impl;

import java.util.Set;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;

/** Exact same-namespace World or Account workload authorization for the historical read. */
public final class HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard {
  private static final String METHOD_NAME =
      "game_session.v1.HistoricalOriginalStartSessionOwnerEvidenceReadService/"
          + "ReadHistoricalOriginalStartSessionOwnerEvidence";
  private static final Set<String> ALLOWED_SERVICES =
      Set.of("world-management-service", "account-service");

  private final String trustedNamespace;

  public HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireHistoricalOwnerReadCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (!METHOD_NAME.equals(
            HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
                .getReadHistoricalOriginalStartSessionOwnerEvidenceMethod()
                .getFullMethodName())
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peer == null
        || !ALLOWED_SERVICES.contains(peer.service())
        || !trustedNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + peer.service()).equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Historical StartSession evidence requires the exact same-namespace World or Account workload without caller context");
    }
  }

  public void requireConfiguredTargetNamespace(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || !trustedNamespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Historical StartSession evidence target namespace is not configured here");
    }
  }
}
