package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered exact held-retention read; it neither creates authority nor settles participation.
 */
public final class AccountSelectedOwnerIntakeAuthorizationReadService {
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository;
  private final TransactionTemplate ownerTransaction;
  private final String namespace;

  public AccountSelectedOwnerIntakeAuthorizationReadService(
      AccountSelectedOwnerIntakeSourceReservationRepository repository,
      PlatformTransactionManager transactions,
      String namespace) {
    this.repository =
        Objects.requireNonNull(repository, "source reservation repository is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /** Confirms the exact finalized authorization and source locks in an independent transaction. */
  public void requireHeld(SelectedOwnerIntakeAuthorizationReadEvidence.Request request) {
    Objects.requireNonNull(request, "authorization-read request is required");
    requirePeer(namespace, request.intendedReader());
    if (!namespace.equals(request.targetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    }
    try {
      ownerTransaction.execute(
          ignored -> {
            repository.readFinalAuthorization(request.binding());
            return null;
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (IllegalArgumentException changedOrAbsent) {
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE.asRuntimeException();
    }
  }

  static void requirePeer(String namespace, String expectedReader) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !peer.uri().equals(expectedReader)
        || !(expectedReader.equals(
                "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service")
            || expectedReader.equals(
                "spiffe://firemud/ns/" + namespace + "/sa/automation-scripting-service"))) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
  }
}
