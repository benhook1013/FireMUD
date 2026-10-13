package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;

/** Exact same-namespace Entity workload authorization for roster and assignment owner reads. */
public final class CanonicalGameplayRosterOwnerReadWorkloadGuard {
  private static final String ROSTER_METHOD_NAME =
      "game_session.v1.CanonicalGameplayRosterOwnerReadService/GetCanonicalGameplayRosterOwnerRead";
  private static final String ASSIGNMENT_METHOD_NAME =
      "game_session.v1.CanonicalGameplayRosterOwnerReadService/GetPreseededActorAssignmentOwnerRead";
  private static final String ENTITY_MANAGEMENT_SERVICE = "entity-management-service";

  private final String trustedNamespace;

  public CanonicalGameplayRosterOwnerReadWorkloadGuard(String trustedNamespace) {
    this.trustedNamespace = trustedNamespace;
  }

  public void requireEntityOwnerReadCaller() {
    requireEntityOwnerReadCaller(
        CanonicalGameplayRosterOwnerReadServiceGrpc.getGetCanonicalGameplayRosterOwnerReadMethod()
            .getFullMethodName());
  }

  public void requireEntityAssignmentOwnerReadCaller() {
    requireEntityOwnerReadCaller(
        CanonicalGameplayRosterOwnerReadServiceGrpc.getGetPreseededActorAssignmentOwnerReadMethod()
            .getFullMethodName());
  }

  private void requireEntityOwnerReadCaller(String expectedMethod) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if ((!ROSTER_METHOD_NAME.equals(expectedMethod)
            && !ASSIGNMENT_METHOD_NAME.equals(expectedMethod))
        || !GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || peer == null
        || !ENTITY_MANAGEMENT_SERVICE.equals(peer.service())
        || !trustedNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + trustedNamespace + "/sa/" + ENTITY_MANAGEMENT_SERVICE)
            .equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Game Session owner read requires the exact same-namespace "
              + "Entity Management workload without caller context");
    }
  }

  public void requireConfiguredTargetNamespace(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)
        || !trustedNamespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Game Session owner-read target namespace is not configured here");
    }
  }
}
