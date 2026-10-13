package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly unwired complete owner-local storage component, with a fail-closed permission seam.
 */
public final class WorldDraftRegionCommitService {
  /**
   * Must fail unless the exact complete operation has durable Account ordering that remains held
   * continuously through its terminal reconciliation. A preflight permission check is not reusable
   * authorization for a later commit. No authenticated implementation is supplied in this slice;
   * fixture implementations establish component storage prerequisites only.
   */
  @FunctionalInterface
  public interface PermissionVerifier {
    void verify(WorldDraftRegionCommitPlan plan);
  }

  private final WorldDraftRegionCommitRepository repository;
  private final PermissionVerifier verifier;
  private final TransactionTemplate transaction;

  /** Builds an unwired, denied component by default. */
  public WorldDraftRegionCommitService(
      WorldDraftRegionCommitRepository repository, PlatformTransactionManager manager) {
    this(
        repository,
        manager,
        plan -> {
          throw new ConflictException(
              "Authenticated Account Draft commit permission is unavailable");
        });
  }

  public WorldDraftRegionCommitService(
      WorldDraftRegionCommitRepository repository,
      PlatformTransactionManager manager,
      PermissionVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  /** Verifies before opening the dedicated transaction; no external call runs while it is open. */
  public WorldDraftRegionCommitEvidence store(WorldDraftRegionCommitPlan plan) {
    Objects.requireNonNull(plan, "plan");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException(
          "World complete storage cannot start inside an existing transaction");
    }
    verifier.verify(plan);
    return Objects.requireNonNull(transaction.execute(status -> repository.store(plan)));
  }

  /**
   * Reads original committed storage evidence without invoking permission verification or a writer
   * transaction. The result remains permission-unverified; absence is unknown rather than an abort
   * or definitive no-commit outcome.
   */
  public Optional<WorldDraftRegionCommitEvidence> readCommitted(WorldDraftRegionCommitPlan plan) {
    Objects.requireNonNull(plan, "plan");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException(
          "World complete storage readback requires no active caller transaction");
    }
    return repository.readCommitted(plan);
  }
}
