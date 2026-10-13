package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicitly gated World owner boundary for terminalizing a canonical first-open hold.
 *
 * <p>The injected verifier must obtain and hold an operation-specific authenticated Game Session
 * owner readback. This class has no permissive verifier and does not make elapsed time, missing
 * evidence, or a pending result terminal.
 */
public final class WorldCanonicalInitialAdmissionHoldFinalizationService {
  private final WorldCanonicalInitialAdmissionHoldFinalizationRepository repository;
  private final OwnerProofVerifier verifier;

  public WorldCanonicalInitialAdmissionHoldFinalizationService(
      WorldCanonicalInitialAdmissionHoldFinalizationRepository repository,
      OwnerProofVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Finalizes one exact acquired identity while the authenticated owner proof remains held through
   * the independent World commit and exact readback.
   */
  public GameSessionCanonicalInitialAdmissionOwnerProof finalizeHold(
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(expectedOutcome, "expectedOutcome");
    requireNoAmbientTransaction();
    if (expectedOutcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw denied("PENDING Game Session owner evidence cannot terminalize a World hold");
    }

    HeldOwnerProof held =
        Objects.requireNonNull(
            verifier.verifyAndHold(expectedIdentity, expectedOutcome),
            "Game Session owner proof verifier returned no held proof");
    try {
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          Objects.requireNonNull(held.proof(), "held owner proof");
      requireExactProof(expectedIdentity, expectedOutcome, proof);
      held.requireHeld();
      GameSessionCanonicalInitialAdmissionOwnerProof stored =
          repository.finalizeTerminal(expectedIdentity, proof, held);
      held.requireHeld();
      return stored;
    } finally {
      held.close();
    }
  }

  /**
   * Reads the actual exact Game Session outcome for restart reconciliation. PENDING is a successful
   * read but leaves World unchanged; only authenticated terminal proof reaches the existing
   * serialized finalizer.
   */
  public Optional<GameSessionCanonicalInitialAdmissionOwnerProof> observeAndFinalizeHold(
      HoldIdentity expectedIdentity) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    requireNoAmbientTransaction();

    HeldOwnerProof held =
        Objects.requireNonNull(
            verifier.verifyAndHoldObserved(expectedIdentity),
            "Game Session owner proof verifier returned no held proof");
    try {
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          Objects.requireNonNull(held.proof(), "held owner proof");
      held.requireHeld();
      if (!expectedIdentity.equals(proof.holdIdentity())) {
        throw denied("Game Session owner proof differs from the exact requested hold");
      }
      if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
        return Optional.empty();
      }

      requireExactProof(expectedIdentity, proof.outcome(), proof);
      GameSessionCanonicalInitialAdmissionOwnerProof stored =
          repository.finalizeTerminal(expectedIdentity, proof, held);
      held.requireHeld();
      return Optional.of(stored);
    } finally {
      held.close();
    }
  }

  /** Explicit fail-closed verifier for configurations that have no authenticated GS adapter. */
  public static OwnerProofVerifier denyAllVerifier() {
    return (identity, outcome) -> {
      throw denied(
          "Authenticated Game Session canonical initial-admission owner proof is unavailable");
    };
  }

  private static void requireExactProof(
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    if (!expectedIdentity.equals(proof.holdIdentity()) || proof.outcome() != expectedOutcome) {
      throw denied("Game Session owner proof differs from the exact requested hold or outcome");
    }
    if (proof.terminalAt().getNano() % 1_000 != 0) {
      throw denied("Game Session terminal timestamp exceeds World storage precision");
    }
    if (expectedOutcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      long expectedPointerVersion;
      try {
        expectedPointerVersion =
            switch (expectedIdentity.request().initialAdmissionOrigin()) {
              case NO_PRIOR_POINTER -> 1L;
              case EXPECT_CLOSED ->
                  Math.addExact(expectedIdentity.request().expectedPriorPointerVersion(), 1L);
            };
      } catch (ArithmeticException overflow) {
        throw denied("Expected prior pointer version cannot advance without overflow");
      }
      if (!Long.valueOf(expectedPointerVersion).equals(proof.committedPointerVersion())
          || proof.positiveDurableAbort()) {
        throw denied("Committed Game Session owner proof has a noncanonical pointer version");
      }
    } else if (!proof.positiveDurableAbort()
        || proof.committedPointerVersion() != null
        || proof.auditEventId() != null) {
      throw denied("Aborted Game Session owner proof lacks positive durable fencing");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World canonical initial-admission hold finalization must start outside an ambient transaction");
    }
  }

  private static FinalizationDeniedException denied(String message) {
    return new FinalizationDeniedException(message);
  }

  /** Operation-specific verifier for the complete authenticated Game Session outcome. */
  @FunctionalInterface
  public interface OwnerProofVerifier {
    HeldOwnerProof verifyAndHold(
        HoldIdentity expectedIdentity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome);

    /** Actual-outcome lookup used only by the explicit World restart reconciler. */
    default HeldOwnerProof verifyAndHoldObserved(HoldIdentity expectedIdentity) {
      throw denied(
          "Authenticated Game Session canonical initial-admission outcome read is unavailable");
    }
  }

  /** A verified exact owner outcome held valid until the enclosing World commit returns. */
  public interface HeldOwnerProof extends AutoCloseable {
    GameSessionCanonicalInitialAdmissionOwnerProof proof();

    void requireHeld();

    @Override
    void close();
  }

  public static final class FinalizationDeniedException extends IllegalStateException {
    public FinalizationDeniedException(String message) {
      super(message);
    }
  }
}
