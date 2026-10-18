package net.firedevops.firemud.accountservice.service.session;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Shared authenticated-peer classification for Account activation-protection boundaries. */
final class ActivationProtectionPeers {
  enum WorldPeerStatus {
    VERIFIED,
    MISSING,
    INVALID
  }

  private ActivationProtectionPeers() {}

  static WorldPeerStatus worldPeerStatus(String workloadNamespace) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      return WorldPeerStatus.MISSING;
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")) {
      return WorldPeerStatus.INVALID;
    }
    return WorldPeerStatus.VERIFIED;
  }
}
