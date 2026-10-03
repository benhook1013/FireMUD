package net.firedevops.firemud.worldmanagement.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;

/** Method-local Game Session workload authorization for initial-bind hold acquisition. */
public final class InitialAdmissionBindWorkloadGuard {
  private final String trustedNamespace;

  public InitialAdmissionBindWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireGameSessionAcquireCaller() {
    GrpcPeerIdentity peerIdentity = GrpcPeerIdentity.current();
    if (!WorldManagementServiceGrpc.getAcquireInitialAdmissionBindHoldMethod()
            .getFullMethodName()
            .equals("world_management.v1.WorldManagementService/AcquireInitialAdmissionBindHold")
        || trustedNamespace == null
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peerIdentity == null
        || !peerIdentity.isService("game-session-service")
        || !peerIdentity.isInNamespace(trustedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Initial admission hold requires only the authenticated Game Session workload peer identity");
    }
  }
}
