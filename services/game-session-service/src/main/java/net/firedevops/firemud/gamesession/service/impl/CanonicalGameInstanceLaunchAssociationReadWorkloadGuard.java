package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;

/** Same-namespace World Management workload-only authorization for the non-mutating owner read. */
public final class CanonicalGameInstanceLaunchAssociationReadWorkloadGuard {
  private static final String METHOD_NAME =
      "game_session.v1.GameSessionControlPlaneService/GetCanonicalGameInstanceLaunchAssociation";
  private static final String WORLD_MANAGEMENT_SERVICE = "world-management-service";

  private final String trustedNamespace;

  public CanonicalGameInstanceLaunchAssociationReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireWorldManagementOwnerReadCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (!METHOD_NAME.equals(
            GameSessionControlPlaneServiceGrpc.getGetCanonicalGameInstanceLaunchAssociationMethod()
                .getFullMethodName())
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peer == null
        || !WORLD_MANAGEMENT_SERVICE.equals(peer.service())
        || !trustedNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + WORLD_MANAGEMENT_SERVICE)
            .equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Canonical Game Session owner read requires the exact same-namespace World Management workload without caller context");
    }
  }

  public void requireConfiguredTargetNamespace(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || !trustedNamespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Canonical Game Session owner read target namespace is not configured here");
    }
  }
}
