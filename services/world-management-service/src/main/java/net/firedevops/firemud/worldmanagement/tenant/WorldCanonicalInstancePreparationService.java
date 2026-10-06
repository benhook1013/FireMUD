package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Result;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, default-denied owner operation for a canonical generation-free world instance.
 *
 * <p>No production verifier is supplied in this slice. A future authenticated integration must
 * verify the same-namespace Game Session producer, current Game Design release and source, applied
 * participant state, and Account commit authority before the World transaction begins. Its held
 * authority must stay valid until the transaction manager has committed. This class is deliberately
 * not a Spring component and adds no RPC or public activation path.
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
        "Canonical World preparation has no authenticated source/release and Account commit verifier");
  }

  /** Performs remote/current-state checks before returning a continuously held local fence. */
  @FunctionalInterface
  public interface CommitAuthorityVerifier {
    HeldCommitAuthority verifyAndHold(Input input);
  }

  /**
   * A verifier-owned, continuously held authority/fence handle. It stays open throughout the World
   * transaction and its commit; implementations must fail closed from {@link #requireHeld()} if
   * current authority has been lost or is no longer effective. Remote/current-state checks happen
   * before the World transaction; {@code requireHeld()} is a local fence/effective-state assertion
   * and must never perform RPC while World holds database locks. It is not a time lease and cannot
   * extend authority beyond its effective period.
   */
  public interface HeldCommitAuthority extends AutoCloseable {
    void requireHeld();

    @Override
    void close();
  }

  public static final class PreparationDeniedException extends IllegalStateException {
    public PreparationDeniedException(String message) {
      super(message);
    }
  }
}
