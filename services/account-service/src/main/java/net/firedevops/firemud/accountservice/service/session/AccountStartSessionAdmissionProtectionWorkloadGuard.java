package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Method-specific admission gate for the same-namespace Game Session workload. */
public final class AccountStartSessionAdmissionProtectionWorkloadGuard {
  private static final String GAME_SESSION_SERVICE = "game-session-service";

  private final String namespace;

  public AccountStartSessionAdmissionProtectionWorkloadGuard(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Authenticates the certificate-derived peer before the receiver examines request fields. */
  public void requireGameSessionCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !GAME_SESSION_SERVICE.equals(peer.service())
        || !namespace.equals(peer.namespace())
        || !expectedPeerUri().equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }

  /** Ensures the complete canonical owner request is for this method's authenticated namespace. */
  public void requireTargetNamespace(AccountStartSessionAdmissionProtectionRequest request) {
    Objects.requireNonNull(request, "Account admission protection request required");
    var tupleScope = request.originalTuple().preAuthorizationTuple().action().scope();
    var hold = request.worldAdmissionHoldIdentity().request();
    if (!namespace.equals(tupleScope.targetNamespace())
        || !namespace.equals(hold.targetNamespace())
        || !tupleScope.tenantId().equals(hold.canonicalTenantId())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Admission protection target namespace is not authorized")
          .asRuntimeException();
    }
  }

  private String expectedPeerUri() {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + GAME_SESSION_SERVICE;
  }
}
