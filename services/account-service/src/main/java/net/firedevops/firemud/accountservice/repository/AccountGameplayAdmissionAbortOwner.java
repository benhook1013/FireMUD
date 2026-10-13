package net.firedevops.firemud.accountservice.repository;

import io.grpc.Status;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered cancellation owner. Authenticates the original Game Session workload and retains
 * pending orphan cleanup atomically; grants no admission, current authority or token retirement. A
 * supplied decision is correlation only, never a verified Gameplay decision.
 */
public final class AccountGameplayAdmissionAbortOwner {
  private final AccountGameplayAdmissionLeaseRepository repository;
  private final TransactionTemplate transaction;
  private final String namespace;

  public AccountGameplayAdmissionAbortOwner(
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

  public AccountGameplayAdmissionLeaseOperation abort(AbortGameplayAdmissionLeaseRequest request) {
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
    if (request == null || !request.hasLease() || !request.getUnknownFields().asMap().isEmpty())
      throw new IllegalArgumentException("Malformed Account admission abort request");
    var evidence = AccountGameplayAdmissionLeaseWireCodec.parseReference(request.getLease());
    UUID decision =
        request.hasBindingDecisionId() ? decision(request.getBindingDecisionId()) : null;
    if (!namespace.equals(evidence.carrier().get("targetNamespace"))
        || !peer.uri().equals(evidence.carrier().get("callerWorkload")))
      throw Status.PERMISSION_DENIED
          .withDescription("Admission lease caller mismatch")
          .asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Owned Account transaction required")
          .asRuntimeException();
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              AccountGameplayAdmissionLeaseOperation prior;
              try {
                prior =
                    repository
                        .readExact(evidence)
                        .orElseThrow(() -> new AbortDeniedException("ADMISSION_LEASE_UNRESOLVED"));
              } catch (AccountGameplayAdmissionLeaseRepository.IdentityConflictException failure) {
                throw conflict();
              }
              if (prior.state() == State.COMMITTED)
                throw new AbortDeniedException("ADMISSION_LEASE_NOT_ABORTABLE");
              if (prior.state() == State.ABORTED) {
                if (!Objects.equals(prior.bindingDecisionId(), decision)) throw conflict();
                return prior;
              }
              try {
                return repository.recordAborted(evidence, decision, UUID.randomUUID());
              } catch (AccountGameplayAdmissionLeaseRepository.IdentityConflictException failure) {
                throw conflict();
              }
            }));
  }

  private static UUID decision(String text) {
    try {
      UUID value = UUID.fromString(text);
      if (!value.toString().equals(text) || value.version() != 4 || value.variant() != 2)
        throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException ignored) {
      throw new IllegalArgumentException("Malformed Account admission abort request");
    }
  }

  private static AbortDeniedException conflict() {
    return new AbortDeniedException("IDEMPOTENCY_CONFLICT");
  }

  /** Produced domain denial; no evidence or storage failure details are exposed. */
  public static final class AbortDeniedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String code;

    private AbortDeniedException(String code) {
      super("Account admission abort denied");
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
