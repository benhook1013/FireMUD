package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;

/** Exact same-namespace World workload authorization for canonical owner proof readback. */
public final class CanonicalInitialAdmissionOwnerReadWorkloadGuard {
  private static final String WORLD_MANAGEMENT_SERVICE = "world-management-service";
  private final String trustedNamespace;

  public CanonicalInitialAdmissionOwnerReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireWorldManagementOwnerReadCaller() {
    GrpcPeerIdentity peerIdentity = GrpcPeerIdentity.current();
    if (!"game_session.v1.GameSessionControlPlaneService/GetCanonicalInitialAdmissionOwnerProof"
            .equals(
                GameSessionControlPlaneServiceGrpc.getGetCanonicalInitialAdmissionOwnerProofMethod()
                    .getFullMethodName())
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peerIdentity == null
        || !WORLD_MANAGEMENT_SERVICE.equals(peerIdentity.service())
        || !trustedNamespace.equals(peerIdentity.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + WORLD_MANAGEMENT_SERVICE)
            .equals(peerIdentity.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Canonical initial-admission owner proof requires the same-namespace World Management workload identity");
    }
  }

  public void requireWorldManagementOwnerReadCaller(String targetNamespace) {
    requireWorldManagementOwnerReadCaller();
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || !trustedNamespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Canonical initial-admission owner proof requires the same-namespace World Management workload identity");
    }
  }
}
