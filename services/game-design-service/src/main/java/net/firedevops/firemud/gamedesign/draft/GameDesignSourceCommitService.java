package net.firedevops.firemud.gamedesign.draft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CoordinatorProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered original creator-to-Account-to-local-source composition. This bounded entry accepts
 * only a complete Game Design-only owner vector. The generic multi-owner coordinator is unchanged.
 * Account participation remains pending until the existing authenticated terminal settlement.
 */
public final class GameDesignSourceCommitService {
  private final AccountOriginalDraftOrderClient account;
  private final DraftCommitCoordinatorRepository coordinator;
  private final GameDesignDraftTerminalOutcomeRepository terminals;
  private final GameDesignSourceRepository sources;
  private final TransactionTemplate write;
  private final TransactionTemplate read;
  private final String namespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repositories are intentionally shared service collaborators.")
  public GameDesignSourceCommitService(
      AccountOriginalDraftOrderClient account,
      DraftCommitCoordinatorRepository coordinator,
      GameDesignDraftTerminalOutcomeRepository terminals,
      GameDesignSourceRepository sources,
      PlatformTransactionManager transactions,
      String namespace) {
    this.account = Objects.requireNonNull(account);
    this.coordinator = Objects.requireNonNull(coordinator);
    this.terminals = Objects.requireNonNull(terminals);
    this.sources = Objects.requireNonNull(sources);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
    write = new TransactionTemplate(Objects.requireNonNull(transactions));
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  /**
   * Internal application entry only. Exact terminal replay uses durable operation/source identity
   * without reauthenticating a creator credential; this is not an authenticated public recovery
   * API.
   */
  public GameDesignDraftTerminalOutcome commit(
      DraftAuthorizationFenceBinding original, String originalCreatorCredential) {
    outsideSql();
    var operation = new GameDesignDraftTerminalOperation(original);
    DraftCommitBinding binding = operation.gameDesignBinding();
    if (!binding.requiredOwners().equals(List.of(Owner.GAME_DESIGN_CONTROL_PLANE))
        || !original
            .requiredOwners()
            .equals(List.of(DraftAuthorizationFenceBinding.Owner.GAME_DESIGN)))
      throw new IllegalArgumentException("Complete Game Design-only owner vector required");
    // Terminal retry reads its exact retained operation and source snapshots before any mutable
    // creator/currentness recapture. A changed operation conflicts in the terminal repository.
    Optional<GameDesignDraftTerminalOutcome> retained =
        read.execute(ignored -> terminal(operation));
    if (Objects.requireNonNull(retained).isPresent()) return retained.orElseThrow();
    return begin(operation, originalCreatorCredential);
  }

  private GameDesignDraftTerminalOutcome begin(
      GameDesignDraftTerminalOperation operation, String credential) {
    var original = operation.accountBinding();
    var binding = operation.gameDesignBinding();
    Optional<GameDesignDraftTerminalOutcome> admitted =
        write.execute(
            ignored -> {
              coordinator.lockVersionTarget(binding.target());
              var retained = terminal(operation);
              if (retained.isPresent()) return retained;
              coordinator.claim(binding, original);
              coordinator.claimApplicationSlot(binding);
              return Optional.empty();
            });
    if (Objects.requireNonNull(admitted).isPresent()) return admitted.orElseThrow();
    outsideSql();
    try {
      var request =
          AccountOriginalDraftOrderGrpcCodec.Request.create(namespace, original, credential);
      var ordered = account.claim(request);
      if (ordered == null || !ordered.matches(request))
        throw new IllegalStateException("Exact authenticated original Account order required");
    } catch (RuntimeException unavailableOrder) {
      // A lost/denied Account response cannot prove Account absence. Instead the local owner
      // durably excludes its own application; a late Account order settles against that terminal.
      write.executeWithoutResult(ignored -> coordinator.abortUnattemptedLocalSource(operation));
      outsideSql();
      return Objects.requireNonNull(read.execute(ignored -> terminal(operation).orElseThrow()));
    }
    outsideSql();
    write.executeWithoutResult(
        ignored -> {
          coordinator.lockVersionTarget(binding.target());
          if (terminal(operation).isPresent()) return;
          // The exact retained reservation precedes Account ordering and survives local rollback.
          var claimed = coordinator.claim(binding, original);
          if (!claimed.binding().equals(binding))
            throw new IllegalStateException("Exact local source binding required");
          coordinator.claimApplicationSlot(binding);
          var owner = claimed.ownerStates().get(Owner.GAME_DESIGN_CONTROL_PLANE);
          if (owner == null) throw new IllegalStateException("Complete local owner state required");
          DraftCommitCoordinatorRepository.OwnerOutcome outcome;
          if (owner.status() == OwnerStatus.NOT_ATTEMPTED) {
            coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
            outcome = sources.apply(binding).ownerOutcome();
          } else if (owner.status() == OwnerStatus.APPLIED) {
            // Recovery uses the original retained result; it cannot apply source mutations twice.
            outcome = owner.outcome().orElseThrow();
          } else {
            throw new IllegalStateException("Unresolved local owner requires exact recovery");
          }
          if (outcome.owner() != Owner.GAME_DESIGN_CONTROL_PLANE
              || outcome.status() != OwnerStatus.APPLIED
              || !binding.commitId().equals(outcome.commitId())
              || !binding.digest().equals(outcome.bindingDigest()))
            throw new IllegalStateException("Exact local APPLIED source result required");
          coordinator.advanceSourceVisibilityFence(
              binding, new CoordinatorProof(binding, List.of(outcome)));
          coordinator.releaseApplicationSlot(binding);
          terminal(operation)
              .orElseThrow(() -> new IllegalStateException("Terminal source readback unavailable"));
        });
    outsideSql();
    return Objects.requireNonNull(read.execute(ignored -> terminal(operation).orElseThrow()));
  }

  private Optional<GameDesignDraftTerminalOutcome> terminal(
      GameDesignDraftTerminalOperation operation) {
    var retained = terminals.read(operation);
    if (retained.isEmpty()) return Optional.empty();
    var outcome = retained.orElseThrow();
    if (!operation.exactlyMatches(outcome.operation()))
      throw new IllegalStateException("Terminal source operation conflict");
    if (outcome.result() == GameDesignDraftTerminalOutcome.Result.COMMITTED) {
      var binding = operation.gameDesignBinding();
      var synchronizedSources =
          sources
              .readSynchronized(binding.target(), binding.commitId())
              .orElseThrow(
                  () -> new IllegalStateException("Synchronized source readback unavailable"));
      if (!binding.equals(synchronizedSources.command().binding()))
        throw new IllegalStateException("Synchronized source binding conflict");
    }
    return retained;
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Original source commit requires no ambient transaction");
  }
}
