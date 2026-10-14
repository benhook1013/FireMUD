package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account-bound owner component. Production authority is unavailable/default denied.
 */
public final class WorldDraftGraphApplicationService {
  /**
   * The missing protected Account producer must prove original COMMIT_ORDER remains held until BOTH
   * original owner outcomes settle. A preflight, lease or source capture cannot implement this
   * contract. Test implementations stipulate that authority; they do not prove a Gameplay handoff.
   */
  @FunctionalInterface
  public interface CommitOrderVerifier {
    CommitOrderProof verifyHeldOriginalCommitOrder(WorldDraftTerminalOperation operation);
  }

  /** Typed carrier, deliberately without a boolean authorization shortcut. */
  public static final class CommitOrderProof {
    private final byte[] originalOperation;
    private final byte[] originalAccountBinding;

    public CommitOrderProof(WorldDraftTerminalOperation operation) {
      originalOperation = operation.canonicalBytes();
      originalAccountBinding = operation.accountBindingBytes();
    }

    void requireExact(WorldDraftTerminalOperation operation) {
      if (!Arrays.equals(originalOperation, operation.canonicalBytes())
          || !Arrays.equals(originalAccountBinding, operation.accountBindingBytes())) {
        throw new ConflictException(
            "Account COMMIT_ORDER proof differs from original complete operation");
      }
    }
  }

  private final WorldDraftGraphApplicationRepository repository;
  private final CommitOrderVerifier verifier;
  private final TransactionTemplate transaction;

  public WorldDraftGraphApplicationService(
      WorldDraftGraphApplicationRepository repository, PlatformTransactionManager manager) {
    this(
        repository,
        manager,
        operation -> {
          throw new ConflictException(
              "Protected Account original COMMIT_ORDER producer is unavailable");
        });
  }

  public WorldDraftGraphApplicationService(
      WorldDraftGraphApplicationRepository repository,
      PlatformTransactionManager manager,
      CommitOrderVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public WorldDraftGraphAppliedResult apply(WorldDraftGraphApplication application) {
    requireCommittedBoundary();
    var prior = repository.readCommitted(application);
    if (prior.isPresent()) return prior.orElseThrow();
    CommitOrderProof proof =
        Objects.requireNonNull(
            verifier.verifyHeldOriginalCommitOrder(application.operation()), "COMMIT_ORDER proof");
    proof.requireExact(application.operation());
    return Objects.requireNonNull(
        transaction.execute(status -> repository.apply(application, proof)));
  }

  public Optional<WorldDraftGraphAppliedResult> readCommitted(
      WorldDraftGraphApplication application) {
    requireCommittedBoundary();
    return repository.readCommitted(application);
  }

  public Optional<WorldDraftGraphAppliedResult> readCommitted(
      String targetNamespace, byte[] originalAccountBinding) {
    requireCommittedBoundary();
    return repository.readCommitted(targetNamespace, originalAccountBinding);
  }

  private static void requireCommittedBoundary() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("World graph application requires no active caller transaction");
    }
  }
}
