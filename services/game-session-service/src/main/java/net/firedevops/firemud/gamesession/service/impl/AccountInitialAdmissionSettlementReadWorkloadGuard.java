package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact same-namespace Account workload only; bearer/player context is never admitted. */
public final class AccountInitialAdmissionSettlementReadWorkloadGuard {
  private final String namespace;

  public AccountInitialAdmissionSettlementReadWorkloadGuard(String namespace) {
    this.namespace = namespace;
  }

  public void requireAccountCaller() {
    var peer = GrpcPeerIdentity.current();
    if (!GrpcPeerIdentity.isValidNamespace(namespace)
        || peer == null
        || !"account-service".equals(peer.service())
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/account-service").equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Settlement read requires the same-namespace Account workload");
    }
  }

  public void requireAccountCaller(String targetNamespace) {
    requireAccountCaller();
    if (!namespace.equals(targetNamespace)) {
      throw new AdminAuthorizationException(
          "Settlement read requires the same-namespace Account workload");
    }
  }
}
