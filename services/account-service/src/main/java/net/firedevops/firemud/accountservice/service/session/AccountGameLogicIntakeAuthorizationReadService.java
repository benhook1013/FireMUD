package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered exact held-source read; settled orders cannot mint fresh HELD evidence. */
public final class AccountGameLogicIntakeAuthorizationReadService {
  private final AccountGameLogicIntakeAuthorizationRepository repository;
  private final TransactionTemplate transaction;
  private final String namespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected repository is an intentionally shared service collaborator.")
  public AccountGameLogicIntakeAuthorizationReadService(
      AccountGameLogicIntakeAuthorizationRepository repository,
      PlatformTransactionManager transactions,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  public void requireHeld(GameLogicIntakeAuthorizationReadEvidence.Request request) {
    requirePeer(namespace);
    if (!namespace.equals(request.targetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    try {
      transaction.execute(
          ignored -> {
            repository.readHeld(request.binding());
            return null;
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (IllegalArgumentException changed) {
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE.asRuntimeException();
    }
  }

  static void requirePeer(String namespace) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    String prefix = "spiffe://firemud/ns/" + namespace + "/sa/";
    if (!(prefix + "game-logic-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }
}
