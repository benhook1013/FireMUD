package net.firedevops.firemud.common.security;

import io.grpc.Status;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Authorizes a certificate-bound workload caller without ambient account or service claims. */
public final class GrpcWorkloadOnlyGuard {
  private GrpcWorkloadOnlyGuard() {}

  public static Optional<Status> refusal(
      GrpcPeerIdentity peer, String expectedService, String expectedNamespace) {
    if (peer == null) {
      return Optional.of(Status.UNAUTHENTICATED);
    }
    if (!peer.isService(expectedService)
        || !peer.isInNamespace(expectedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      return Optional.of(Status.PERMISSION_DENIED);
    }
    return Optional.empty();
  }
}
