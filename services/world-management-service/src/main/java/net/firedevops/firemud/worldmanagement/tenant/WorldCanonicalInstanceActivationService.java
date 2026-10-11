package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Default-denied, unregistered canonical World lifecycle operation. A standalone transport adapter
 * does not register this operation or supply its production source/current-Account verifier.
 */
public final class WorldCanonicalInstanceActivationService {
  private final WorldCanonicalInstanceActivationRepository repository;

  /**
   * Production-safe default: request-only transports cannot start activation without the distinct
   * authenticated Account activation-purpose protection path and its held source authority.
   */
  public WorldCanonicalInstanceActivationService(
      WorldCanonicalInstanceActivationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * Retained only for existing isolated callers. A request-only World verifier is no longer
   * sufficient authority to start a fresh activation; fresh activation requires the distinct
   * Account activation-purpose protection path below.
   */
  public WorldCanonicalInstanceActivationService(
      WorldCanonicalInstanceActivationRepository repository, ActivationAuthorityVerifier ignored) {
    this.repository = Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(ignored, "verifier");
  }

  /**
   * Replays an exact retained legacy result. Request-only fresh activation is denied: it cannot
   * carry Account's distinct source-protection receipt, and this path intentionally cannot expose
   * the new protected terminal result without that receipt.
   */
  public WorldCanonicalInstanceActivation.Result activate(
      WorldCanonicalInstanceActivation.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction();
    var prior = repository.readResult(request);
    if (prior.isPresent()) return prior.orElseThrow();
    throw new ActivationDeniedException(
        "Fresh canonical activation requires complete Account activation-purpose protection evidence");
  }

  /**
   * Runs the local storage/CAS half using an authority held by the separately authenticated
   * activation-protection producer. The supplied integrity evidence does not itself authenticate
   * Account or prove currentness; {@code held} must come from that separate owner boundary.
   */
  public WorldOriginalStartSessionActivationTerminalEvidence activateProtected(
      AccountStartSessionActivationProtectionEvidence protection, HeldActivationAuthority held) {
    Objects.requireNonNull(protection, "protection");
    Objects.requireNonNull(held, "held");
    requireNoActiveTransaction();
    try (held) {
      held.requireHeld();
      WorldOriginalStartSessionActivationTerminalEvidence terminal =
          repository.activateProtected(protection, held);
      held.requireHeld();
      return terminal;
    }
  }

  /**
   * Reads only an exact immutable terminal receipt for the same Account protection identity.
   * Callers must authenticate its World provenance before treating it as settlement proof.
   */
  public Optional<WorldOriginalStartSessionActivationTerminalEvidence> readTerminalEvidence(
      AccountStartSessionActivationProtectionEvidence protection) {
    Objects.requireNonNull(protection, "protection");
    requireNoActiveTransaction();
    return repository.readTerminalEvidence(protection);
  }

  /**
   * Positively records an exact ABORTED terminal under the same durable World operation fence. It
   * does not settle or release Account source protection.
   */
  public WorldOriginalStartSessionActivationTerminalEvidence abortProtected(
      AccountStartSessionActivationProtectionEvidence protection, String terminalCode) {
    Objects.requireNonNull(protection, "protection");
    requireNoActiveTransaction();
    return repository.abortProtected(protection, terminalCode);
  }

  private static void requireNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical World activation must verify outside an ambient transaction");
    }
  }

  @FunctionalInterface
  /**
   * Legacy request-only verifier retained for source compatibility; it cannot authorize a write.
   */
  public interface ActivationAuthorityVerifier {
    HeldActivationAuthority verifyAndHold(WorldCanonicalInstanceActivation.Request request);
  }

  /**
   * Distinct, operation-specific activation authority supplied by the Account-side producer.
   * Closing this local handle releases only local resources; it must never settle or release
   * Account's source-protection hold.
   */
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
