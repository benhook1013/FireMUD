package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered owner read of a distinct retained publication order; never a terminal result. */
public final class AccountPublicationAuthorizationReadService {
  private final AccountPublicationAuthorizationRepository repository;
  private final String trustedNamespace;
  private final TransactionTemplate ownerTransaction;

  public AccountPublicationAuthorizationReadService(
      AccountPublicationAuthorizationRepository repository,
      PlatformTransactionManager transactions,
      String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository);
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.trustedNamespace = trustedNamespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  public void requireHeld(AccountPublicationAuthorizationReadEvidence.Request request) {
    requirePeer(trustedNamespace);
    Objects.requireNonNull(request);
    if (!trustedNamespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Account publication namespace differs from peer")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent Account publication owner transaction required")
          .asRuntimeException();
    }
    try {
      ownerTransaction.execute(
          status -> {
            repository.readHeld(request.binding());
            return null;
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (IllegalArgumentException inconsistent) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact held Account publication authorization unavailable")
          .asRuntimeException();
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Account publication owner read unavailable")
          .asRuntimeException();
    }
  }

  static void requirePeer(String namespace) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Design workload required")
          .asRuntimeException();
    }
  }
}
