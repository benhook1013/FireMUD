package net.firedevops.firemud.gamedesign.publication;

import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit local seam; upstream authentication/current authority must be established separately.
 */
public final class GameDesignPublicationOperationService {
  private final GameDesignPublicationOperationRepository repository;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public GameDesignPublicationOperationService(
      GameDesignPublicationOperationRepository repository,
      PlatformTransactionManager transactions) {
    this.repository = Objects.requireNonNull(repository);
    write = new TransactionTemplate(transactions);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  public GameDesignPublicationOperationRepository.Readback retainVerifiedOperation(
      GameDesignPublicationOperation operation) {
    requireIndependent();
    var retained = readExact(operation);
    if (retained.isPresent()) return retained.orElseThrow();
    write.executeWithoutResult(status -> repository.reserve(operation));
    return readExact(operation)
        .orElseThrow(() -> new IllegalStateException("Publication operation readback absent"));
  }

  public Optional<GameDesignPublicationOperationRepository.Readback> readExact(
      GameDesignPublicationOperation operation) {
    requireIndependent();
    return read.execute(
        status ->
            repository
                .read(operation.workflowId())
                .map(
                    result -> {
                      GameDesignPublicationOperationRepository.exact(result.operation(), operation);
                      return result;
                    }));
  }

  private static void requireIndependent() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Independent publication read/write boundary required");
  }
}
