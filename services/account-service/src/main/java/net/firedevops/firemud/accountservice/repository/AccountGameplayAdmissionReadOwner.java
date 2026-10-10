package net.firedevops.firemud.accountservice.repository;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, non-authorizing exact readback for retained negative admission state.
 *
 * <p>The owner accepts only the original same-namespace Game Session workload and a complete
 * expected lease carrier. It returns exact PENDING or ABORTED storage evidence; missing operations,
 * identity mismatches, and raw storage COMMITTED are unresolved and never become admission results.
 */
public final class AccountGameplayAdmissionReadOwner {
  private final AccountGameplayAdmissionLeaseRepository repository;
  private final TransactionTemplate transaction;
  private final String namespace;

  public AccountGameplayAdmissionReadOwner(
      AccountGameplayAdmissionLeaseRepository repository,
      PlatformTransactionManager transactionManager,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
  }

  public AccountGameplayAdmissionLeaseOperation read(ReadGameplayAdmissionLeaseRequest request) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null)
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    if (!GrpcPeerIdentity.isValidNamespace(namespace)
        || !peer.uri().equals("spiffe://firemud/ns/" + namespace + "/sa/game-session-service"))
      throw Status.PERMISSION_DENIED
          .withDescription("Game Session workload required")
          .asRuntimeException();
    if (request == null
        || !request.hasExpectedLease()
        || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Malformed Account admission lease read request");
    }

    var evidence =
        AccountGameplayAdmissionLeaseWireCodec.parseReference(request.getExpectedLease());
    if (!request.getRequestId().equals(evidence.carrier().get("requestId"))) {
      throw new IllegalArgumentException("Malformed Account admission lease read request");
    }
    if (!namespace.equals(evidence.carrier().get("targetNamespace"))
        || !peer.uri().equals(evidence.carrier().get("callerWorkload"))) {
      throw Status.PERMISSION_DENIED
          .withDescription("Admission lease caller mismatch")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Owned Account transaction required")
          .asRuntimeException();
    }

    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              AccountGameplayAdmissionLeaseOperation stored;
              try {
                stored =
                    repository
                        .readExact(evidence)
                        .orElseThrow(AccountGameplayAdmissionReadOwner::unresolved);
              } catch (AccountGameplayAdmissionLeaseRepository.IdentityConflictException failure) {
                throw unresolved();
              } catch (IllegalArgumentException failure) {
                // The request was already parsed; a parser failure here is corrupt stored evidence.
                throw unresolved();
              }
              if (stored.state() == State.COMMITTED) throw unresolved();
              return stored;
            }));
  }

  private static RuntimeException unresolved() {
    return Status.FAILED_PRECONDITION
        .withDescription("Exact Account admission lease readback unavailable")
        .asRuntimeException();
  }
}
