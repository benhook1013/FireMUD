package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, default-denied receiver for one authenticated Game Design terminal publication. */
public final class WorldPublicationTerminalService {
  private final WorldPublicationTerminalRepository repository;
  private final TerminalAuthorityVerifier verifier;

  /**
   * Production-safe default until an authenticated same-namespace Game Design verifier is wired.
   */
  public WorldPublicationTerminalService(WorldPublicationTerminalRepository repository) {
    this(repository, WorldPublicationTerminalService::denyByDefault);
  }

  /** Explicit verifier seam for a future authenticated producer and isolated component fixtures. */
  public WorldPublicationTerminalService(
      WorldPublicationTerminalRepository repository, TerminalAuthorityVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Authenticates and reconciles the producer before opening the World owner transaction. The
   * continuously held fence is checked locally through commit; no remote call occurs under locks.
   */
  public GameDesignPublicationTerminalEvidence complete(
      byte[] operationBytes, byte[] terminalBytes) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World publication terminal must authenticate outside an ambient transaction");
    }
    VerifiedTerminal verified =
        Objects.requireNonNull(
            verifier.authenticateAndVerifyAndHold(operationBytes.clone(), terminalBytes.clone()),
            "World publication terminal verifier returned no authenticated evidence");
    WorldPublicationTerminal.Request request = Objects.requireNonNull(verified.request());
    if (!request.exactBytes(terminalBytes)
        || !java.util.Arrays.equals(request.operationBytes(), operationBytes)) {
      verified.authority().close();
      throw new IllegalArgumentException(
          "Authenticated Game Design terminal evidence differs from the submitted immutable bytes");
    }
    HeldTerminalAuthority held = Objects.requireNonNull(verified.authority());
    try (held) {
      held.requireHeld();
      GameDesignPublicationTerminalEvidence result = repository.complete(request, held);
      held.requireHeld();
      return result;
    }
  }

  private static VerifiedTerminal denyByDefault(byte[] operationBytes, byte[] terminalBytes) {
    throw new TerminalDeniedException(
        "World publication terminal has no authenticated exact Game Design producer verifier");
  }

  @FunctionalInterface
  public interface TerminalAuthorityVerifier {
    /** Must authenticate the configured Game Design peer before decoding the supplied bytes. */
    VerifiedTerminal authenticateAndVerifyAndHold(byte[] operationBytes, byte[] terminalBytes);
  }

  public record VerifiedTerminal(
      WorldPublicationTerminal.Request request, HeldTerminalAuthority authority) {
    public VerifiedTerminal {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(authority, "authority");
    }
  }

  /**
   * Local held-authentication/currentness assertion; must not perform RPC while World locks exist.
   */
  public interface HeldTerminalAuthority extends AutoCloseable {
    void requireHeld();

    @Override
    void close();
  }

  public static final class TerminalDeniedException extends IllegalStateException {
    public TerminalDeniedException(String message) {
      super(message);
    }
  }
}
