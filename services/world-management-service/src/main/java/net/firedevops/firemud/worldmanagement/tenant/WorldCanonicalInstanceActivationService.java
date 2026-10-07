package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Default-denied, unregistered canonical World lifecycle operation. No transport or production
 * source/current-Account verifier is installed by this component slice.
 */
public final class WorldCanonicalInstanceActivationService {
  private final WorldCanonicalInstanceActivationRepository repository;
  private final ActivationAuthorityVerifier verifier;

  /**
   * Production-safe default: new activations remain denied until an explicit verifier is supplied.
   */
  public WorldCanonicalInstanceActivationService(
      WorldCanonicalInstanceActivationRepository repository) {
    this(repository, WorldCanonicalInstanceActivationService::denyByDefault);
  }

  /** Constructor for an explicitly supplied owner verifier, including isolated proof fixtures. */
  public WorldCanonicalInstanceActivationService(
      WorldCanonicalInstanceActivationRepository repository, ActivationAuthorityVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Replays an exact immutable prior result without rechecking current lifecycle state. A new
   * operation verifies all remote/current authority outside World transactions and keeps its
   * operation-specific local fence held through commit.
   */
  public WorldCanonicalInstanceActivation.Result activate(
      WorldCanonicalInstanceActivation.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction();
    var prior = repository.readResult(request);
    if (prior.isPresent()) return prior.orElseThrow();

    HeldActivationAuthority held =
        Objects.requireNonNull(
            verifier.verifyAndHold(request),
            "Canonical activation verifier returned no held operation authority");
    try (held) {
      held.requireHeld();
      WorldCanonicalInstanceActivation.Result result = repository.activate(request, held);
      held.requireHeld();
      return result;
    }
  }

  private static HeldActivationAuthority denyByDefault(
      WorldCanonicalInstanceActivation.Request request) {
    throw new ActivationDeniedException(
        "Canonical World activation has no authenticated current source/release and Account operation-authority verifier");
  }

  private static void requireNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical World activation must verify outside an ambient transaction");
    }
  }

  @FunctionalInterface
  public interface ActivationAuthorityVerifier {
    HeldActivationAuthority verifyAndHold(WorldCanonicalInstanceActivation.Request request);
  }

  /** Distinct, operation-specific fence; a Draft/publication fence is not activation authority. */
  public interface HeldActivationAuthority extends AutoCloseable {
    void requireHeld();

    @Override
    void close();
  }

  public static final class ActivationDeniedException extends IllegalStateException {
    public ActivationDeniedException(String message) {
      super(message);
    }
  }
}
