package net.firedevops.firemud.gamelogic.authoring;

import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, default-denied Game Logic owner operation for a fresh empty rule manifest.
 *
 * <p>No production verifier is supplied. A future integration must validate the exact current Game
 * Design source and Version proof plus Account commit authority for this complete binding, then
 * continuously hold that authority through the owner transaction. This class has no RPC and is
 * deliberately not a Spring component.
 */
public final class GameLogicEmptyRuleInputManifestService {
  private final GameLogicRuleInputManifestRepository repository;
  private final CommitAuthorityVerifier verifier;

  /** Constructs the production-safe default: all owner applications fail closed. */
  public GameLogicEmptyRuleInputManifestService(GameLogicRuleInputManifestRepository repository) {
    this(repository, GameLogicEmptyRuleInputManifestService::denyByDefault);
  }

  /** Constructor for an explicitly supplied exact-source/Account authority verifier. */
  public GameLogicEmptyRuleInputManifestService(
      GameLogicRuleInputManifestRepository repository, CommitAuthorityVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Applies the one exact empty-manifest intent and returns only a committed, independently
   * verified immutable owner row. The public digest handler is not wired to this storage path.
   */
  public GameLogicRuleInputManifest apply(DraftCommitBinding binding) {
    Objects.requireNonNull(binding, "binding");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Logic rule manifest application must verify outside an ambient transaction");
    }
    GameLogicRuleInputManifest input = GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding);
    HeldCommitAuthority held =
        Objects.requireNonNull(
            verifier.verifyAndHold(binding),
            "Game Logic verifier returned no held Account/source authority");
    try (held) {
      held.requireHeld();
      repository.apply(input, held);
      // TransactionTemplate has committed before this final held-state check.
      held.requireHeld();
      return repository
          .read(input.tenantId(), input.versionId())
          .orElseThrow(
              () ->
                  new GameLogicRuleInputManifestRepository.InvalidStoredManifestException(
                      "Committed Game Logic manifest has no exact owner readback"));
    }
  }

  private static HeldCommitAuthority denyByDefault(DraftCommitBinding binding) {
    throw new ApplicationDeniedException(
        "Game Logic has no authenticated current-source and Account commit-authority verifier");
  }

  /** Validates the complete current-source binding and holds Account authority through commit. */
  @FunctionalInterface
  public interface CommitAuthorityVerifier {
    HeldCommitAuthority verifyAndHold(DraftCommitBinding binding);
  }

  /**
   * A verifier-owned continuously effective authorization/fence handle. Implementations must assert
   * local held state without issuing RPC while Game Logic holds database locks.
   */
  public interface HeldCommitAuthority extends AutoCloseable {
    void requireHeld();

    @Override
    void close();
  }

  public static final class ApplicationDeniedException extends IllegalStateException {
    public ApplicationDeniedException(String message) {
      super(message);
    }
  }
}
