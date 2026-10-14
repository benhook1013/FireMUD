package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-private, explicitly unwired capture; this adds no publication or runtime entry point. */
public final class WorldCanonicalFrozenTopologyService {
  private final WorldCanonicalFrozenTopologyRepository repository;
  private final TransactionTemplate transaction;

  public WorldCanonicalFrozenTopologyService(
      WorldCanonicalFrozenTopologyRepository repository, PlatformTransactionManager manager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** Exact retry reads the journal first, including after terminal state, without a writer. */
  public WorldCanonicalFrozenTopology capture(Request request) {
    requireNoCallerTransaction();
    var prior = repository.readCommitted(request);
    if (prior.isPresent()) return prior.orElseThrow();
    return Objects.requireNonNull(transaction.execute(status -> repository.capture(request)));
  }

  public Optional<WorldCanonicalFrozenTopology> readCommitted(Request request) {
    requireNoCallerTransaction();
    return repository.readCommitted(request);
  }

  private static void requireNoCallerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("Frozen topology requires an independent owner operation");
    }
  }
}
