package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Non-authorizing internal storage boundary for exact World definitive-abort evidence. */
public final class WorldDraftTerminalOutcomeService {
  private final WorldDraftTerminalOutcomeRepository repository;
  private final TransactionTemplate transaction;

  public WorldDraftTerminalOutcomeService(
      WorldDraftTerminalOutcomeRepository repository, PlatformTransactionManager manager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  /**
   * Records a definitive no-commit result only after checking all World application history while
   * holding the shared V25 row. This method does not authenticate the supplied Account bytes,
   * authorize a caller, or release Account's fence.
   */
  public WorldDraftTerminalOutcome recordDefinitiveAbort(WorldDraftTerminalOperation operation) {
    Objects.requireNonNull(operation, "operation");
    Optional<WorldDraftTerminalOutcome> original = repository.readDefinitiveAbort(operation);
    if (original.isPresent()) {
      return original.orElseThrow();
    }
    return Objects.requireNonNull(
        transaction.execute(status -> repository.recordDefinitiveAbort(operation)));
  }

  /** Committed readback; an absent row is UNKNOWN and never means no-commit. */
  public Optional<WorldDraftTerminalOutcome> readDefinitiveAbort(
      WorldDraftTerminalOperation operation) {
    return repository.readDefinitiveAbort(operation);
  }
}
