package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;

/** Exact method-local World Management workload authorization for owner-proof readback. */
public final class InitialAdmissionBindOwnerReadWorkloadGuard {
  private static final String METHOD_NAME =
      "game_session.v1.GameSessionControlPlaneService/GetInitialAdmissionBindProof";
  private static final String WORLD_MANAGEMENT_SERVICE = "world-management-service";

  private final String trustedNamespace;

  public InitialAdmissionBindOwnerReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireWorldManagementOwnerReadCaller() {
    GrpcPeerIdentity peerIdentity = GrpcPeerIdentity.current();
    if (!METHOD_NAME.equals(
            GameSessionControlPlaneServiceGrpc.getGetInitialAdmissionBindProofMethod()
                .getFullMethodName())
        || trustedNamespace == null
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peerIdentity == null
        || !WORLD_MANAGEMENT_SERVICE.equals(peerIdentity.service())
        || !trustedNamespace.equals(peerIdentity.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + WORLD_MANAGEMENT_SERVICE)
            .equals(peerIdentity.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Initial admission owner proof requires the authenticated World Management workload peer identity");
    }
  }
}
