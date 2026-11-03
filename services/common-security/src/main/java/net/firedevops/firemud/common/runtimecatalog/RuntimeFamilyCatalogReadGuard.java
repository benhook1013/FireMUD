package net.firedevops.firemud.common.runtimecatalog;

import io.grpc.Status;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared catalog receiver security policy; owner messages, codecs and storage types remain local.
 */
public final class RuntimeFamilyCatalogReadGuard {
  private RuntimeFamilyCatalogReadGuard() {}

  /** Authentication and caller isolation must be checked before decode or owner storage. */
  public static Optional<Refusal> refuseBeforeDecode(GrpcPeerIdentity peer, String namespace) {
    if (peer == null) return Optional.of(Refusal.UNAUTHENTICATED);
    if (!peer.isService("game-session-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      return Optional.of(Refusal.PERMISSION_DENIED);
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      return Optional.of(Refusal.FAILED_PRECONDITION);
    }
    return Optional.empty();
  }

  /** Compares the already decoded namespace with the receiver's fixed owner configuration. */
  public static boolean matchesNamespace(String expectedNamespace, String requestNamespace) {
    return expectedNamespace != null && expectedNamespace.equals(requestNamespace);
  }

  /** Owner-specific descriptions must be selected without changing these refusal statuses. */
  public enum Refusal {
    UNAUTHENTICATED(Status.UNAUTHENTICATED),
    PERMISSION_DENIED(Status.PERMISSION_DENIED),
    FAILED_PRECONDITION(Status.FAILED_PRECONDITION);

    private final Status status;

    Refusal(Status status) {
      this.status = status;
    }

    public Status status() {
      return status;
    }
  }
}
