package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Result;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, default-denied owner operation for a canonical generation-free world instance.
 *
 * <p>No production verifier is supplied in this slice. A future authenticated integration must
 * verify the same-namespace Game Session producer and exact current Game Design release/terminal
 * source before the World transaction begins. The original Account-authorized APPLIED result is
 * immutable input evidence; preparation must not reauthorize the creator or reuse Draft/publication
 * permission. Its held producer evidence must remain valid through commit. This class is
 * deliberately not a Spring component and adds no RPC or public activation path.
 */
public final class WorldCanonicalInstancePreparationService {
  private final WorldCanonicalInstancePreparationRepository repository;
  private final CommitAuthorityVerifier verifier;

  /** Constructs the production-safe default: all attempts fail closed. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository) {
    this(repository, WorldCanonicalInstancePreparationService::denyByDefault);
  }

  /** Constructor for an explicitly supplied owner verifier, including labeled synthetic tests. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository, CommitAuthorityVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Verifies before opening the owner transaction and returns only committed exact readback.
   * Current lifecycle state is never rewritten by a historical preparation retry.
   */
  public Result prepare(Input input) {
    Objects.requireNonNull(input, "input");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical World preparation must verify outside an ambient transaction");
    }
    WorldCanonicalInstancePreparation.requireGenerationFree(input.topologyPlan());

    HeldCommitAuthority held =
        Objects.requireNonNull(
            verifier.verifyAndHold(input),
            "Canonical preparation verifier returned no held commit authority");
    try (held) {
      held.requireHeld();
      return repository.materialize(input, held);
    }
  }

  private static HeldCommitAuthority denyByDefault(Input input) {
    throw new PreparationDeniedException(
        "Canonical World preparation has no authenticated source/release terminal verifier");
  }

  /** Performs authenticated producer checks before returning a continuously held local fence. */
  @FunctionalInterface
  public interface CommitAuthorityVerifier {
    HeldCommitAuthority verifyAndHold(Input input);
  }

  /**
   * A verifier-owned, continuously held producer-evidence handle. It stays open throughout the
   * World transaction and its commit; implementations must fail closed from {@link #requireHeld()}
   * if the source participation/fence they verified is no longer continuously held.
   * Remote/current-state checks happen before the World transaction; {@code requireHeld()} is a
   * local fence/effective-state assertion and must never perform RPC while World holds database
   * locks. It must not reapply the original ingress-authority expiry after the V35 SQL admission
   * check: an uninterrupted admitted transaction may finish after that bound while the source
   * participation remains protected through exact World COMMITTED or durable ABORTED settlement.
   * {@link #close()} may release only local resources; it must never release durable Account
   * participation merely because this call returned, timed out, or has an uncertain outcome. That
   * participation remains protected until an authenticated exact World COMMITTED result or fenced
   * durable ABORTED settlement is known. It is not a new Account creator permission.
   */
  public interface HeldCommitAuthority extends AutoCloseable {
    void requireHeld();

    /**
     * Returns the exact typed identity this continuously held verifier authenticated. There is no
     * default identity: old generic no-op handles cannot authorize the new World SQL path.
     */
    default WorldCanonicalInstanceExecutionIdentity executionIdentity() {
      throw new PreparationDeniedException(
          "Canonical World preparation requires a complete typed StartSession execution identity");
    }

    @Override
    void close();
  }

  public static final class PreparationDeniedException extends IllegalStateException {
    public PreparationDeniedException(String message) {
      super(message);
    }
  }
}
