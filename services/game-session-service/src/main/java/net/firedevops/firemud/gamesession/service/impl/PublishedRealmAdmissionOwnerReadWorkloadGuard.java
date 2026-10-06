package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;

/** Exact method-local Entity Management workload authorization for published owner readback. */
public final class PublishedRealmAdmissionOwnerReadWorkloadGuard {
  private static final String METHOD_NAME =
      "game_session.v1.GameSessionControlPlaneService/GetPublishedRealmAdmissionOwnerRead";
  private static final String ENTITY_MANAGEMENT_SERVICE = "entity-management-service";

  private final String trustedNamespace;

  public PublishedRealmAdmissionOwnerReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireEntityManagementOwnerReadCaller() {
    GrpcPeerIdentity peerIdentity = GrpcPeerIdentity.current();
    if (!METHOD_NAME.equals(
            GameSessionControlPlaneServiceGrpc.getGetPublishedRealmAdmissionOwnerReadMethod()
                .getFullMethodName())
        || trustedNamespace == null
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peerIdentity == null
        || !ENTITY_MANAGEMENT_SERVICE.equals(peerIdentity.service())
        || !trustedNamespace.equals(peerIdentity.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + ENTITY_MANAGEMENT_SERVICE)
            .equals(peerIdentity.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Published realm admission owner read requires the authenticated Entity Management workload peer identity");
    }
  }

  public void requireConfiguredTargetNamespace(String targetNamespace) {
    if (trustedNamespace == null
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || !trustedNamespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Published realm admission owner read requires the configured target namespace");
    }
  }
}
