package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseEvidence;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import org.jooq.DSLContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the two actual Game Design sources under one caller-owned coordinator transaction. */
public final class GameDesignSourceRepository {
  private final DSLContext dsl;
  private final RealmPolicySourceRepository policies;
  private final CommandSourceRepository commands;
  private final DraftCommitCoordinatorRepository coordinator;

  public GameDesignSourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    policies = new RealmPolicySourceRepository(dsl);
    commands = new CommandSourceRepository(dsl);
    coordinator = new DraftCommitCoordinatorRepository(dsl);
  }

  /**
   * Claim the exact reviewed proposal against actual owner evidence before any source dispatch.
   * Account authorization and current permission through commit remain caller obligations.
   */
  public DraftCommitCoordinatorRepository.CommitSnapshot claimReviewed(
      DraftCommitBinding binding, GameDesignReviewedBaseEvidence reviewedEvidence) {
    requireWrite();
    return new GameDesignReviewedBaseRepository(dsl).claimReviewed(binding, reviewedEvidence);
  }

  /** Call only on the actual new full-Draft Version insertion branch, in that transaction. */
  public Genesis enrollFreshDraft(TargetProof target) {
    requireWrite();
    Objects.requireNonNull(target, "target");
    RealmPolicyGenesis policy = policies.recordFreshGenesis(target);
    CommandSource.NewDraftGenesisReceipt command =
        commands.enrollNewDraftGenesis(
            new CommandSource.NewDraftGenesisReceipt(
                target, policy.receiptId(), policy.creationTransactionId()));
    Genesis result = new Genesis(policy, command);
    if (!result.equals(
        readGenesis(target)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "GAME_DESIGN_SOURCE_GENESIS_READBACK_UNAVAILABLE")))) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_READBACK_CONFLICT");
    }
    return result;
  }

  public Optional<Genesis> readGenesis(TargetProof target) {
    Objects.requireNonNull(target, "target");
    var policy = policies.readGenesis(target);
    var command = commands.readNewDraftGenesis(target);
    if (policy.isEmpty() && command.isEmpty()) return Optional.empty();
    if (policy.isEmpty() || command.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
    }
    return Optional.of(new Genesis(policy.orElseThrow(), command.orElseThrow()));
  }

  /** Stage actual source writes and record exactly one complete local owner outcome. */
  public Application apply(DraftCommitBinding binding) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    List<CommandSource.Mutation> commandMutations = CommandSource.mutations(binding);
    boolean policyMutation = CommandSource.hasRealmPolicyRevision(binding);
    requireCompleteScopes(binding, !commandMutations.isEmpty(), policyMutation);
    requireGenesis(binding.target());
    requireReviewed(binding);
    Optional<CommandApplication> command = commands.apply(binding);
    if (command.isPresent() != !commandMutations.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_COMMAND_APPLICATION_INCOMPLETE");
    }
    Optional<RealmPolicyApplication> policy = Optional.empty();
    if (policyMutation) {
      // The policy-only path retains its existing exact owner result. Mixed input stages policy
      // first, then records the combined result using the command store's actual readback bytes.
      policy =
          Optional.of(
              command.isPresent() ? policies.applyMutation(binding) : policies.apply(binding));
    }
    OwnerOutcome expected;
    if (policy.isPresent() && command.isPresent()) {
      var policyResult = policy.orElseThrow();
      var commandResult = command.orElseThrow();
      List<AppliedEpoch> epochs =
          List.of(
              commandResult.commandAppliedEpoch(),
              policyResult.ownerOutcome().appliedEpochs().getFirst());
      policies.recordCombinedOwnerOutcome(binding, commandResult.canonicalBytes(), epochs);
      expected = policyResult.combinedOwnerOutcome(commandResult.canonicalBytes(), epochs);
    } else if (policy.isPresent()) {
      expected = policy.orElseThrow().ownerOutcome();
    } else {
      CommandApplication commandResult = command.orElseThrow();
      expected = commandOnlyOutcome(binding, commandResult);
      coordinator.recordOwnerOutcome(binding, expected);
    }
    OwnerOutcome retained =
        coordinator
            .read(binding.target(), binding.requestId())
            .orElseThrow(() -> new IllegalStateException("GAME_DESIGN_COMMIT_READBACK_UNAVAILABLE"))
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE)
            .outcome()
            .orElseThrow(
                () -> new IllegalStateException("GAME_DESIGN_OWNER_RESULT_READBACK_UNAVAILABLE"));
    if (!expected.equals(retained)) {
      throw new IllegalStateException("GAME_DESIGN_OWNER_RESULT_READBACK_CONFLICT");
    }
    return new Application(binding, command, policy, retained);
  }

  /** Invoke after the actual coordinator fence advances, before releasing its application slot. */
  public SynchronizedSources captureSynchronized(DraftCommitBinding binding) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    requireGenesis(binding.target());
    requireReviewed(binding);
    CommandSnapshot command = commands.captureSynchronized(binding);
    RealmPolicySnapshot policy = policies.captureSynchronized(binding);
    if (!binding.equals(command.binding()) || !binding.equals(policy.binding())) {
      throw new IllegalStateException("GAME_DESIGN_SYNCHRONIZED_SOURCE_BINDING_CONFLICT");
    }
    return new SynchronizedSources(command, policy);
  }

  /** Freeze both complete, selected source snapshots against the same retained operation. */
  public Capture freeze(GameDesignPublicationOperation operation) {
    requireWrite();
    Objects.requireNonNull(operation, "operation");
    requireGenesis(operation.account().input().selection().target());
    requireReviewed(operation.account().input().selection().selectedCommit());
    RealmPolicySnapshot.Capture policy = policies.freeze(operation);
    CommandSnapshot.Capture command = commands.freeze(operation);
    Capture result = new Capture(command, policy);
    Capture retained =
        readCapture(operation)
            .orElseThrow(
                () -> new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_READBACK_UNAVAILABLE"));
    if (!Arrays.equals(result.command().canonicalBytes(), retained.command().canonicalBytes())
        || !Arrays.equals(result.policy().canonicalBytes(), retained.policy().canonicalBytes())) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_READBACK_CONFLICT");
    }
    return retained;
  }

  /** Exact stored captures for a bundle producer; partial capture cannot authorize publication. */
  public Optional<Capture> readCapture(GameDesignPublicationOperation operation) {
    Objects.requireNonNull(operation, "operation");
    requireReviewed(operation.account().input().selection().selectedCommit());
    var command = commands.readCapture(operation);
    var policy = policies.readCapture(operation);
    if (command.isEmpty() && policy.isEmpty()) return Optional.empty();
    if (command.isEmpty() || policy.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_INCOMPLETE");
    }
    return Optional.of(new Capture(command.orElseThrow(), policy.orElseThrow()));
  }

  public Optional<SynchronizedSources> readSynchronized(TargetProof target, UUID commitId) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(commitId, "commitId");
    var command = commands.readSnapshot(target, commitId);
    var policy = policies.readSnapshot(target, commitId);
    if (command.isEmpty() && policy.isEmpty()) return Optional.empty();
    if (command.isEmpty() || policy.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SYNCHRONIZED_SOURCE_INCOMPLETE");
    }
    return Optional.of(new SynchronizedSources(command.orElseThrow(), policy.orElseThrow()));
  }

  private void requireGenesis(TargetProof target) {
    if (readGenesis(target).isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_UNAVAILABLE");
    }
  }

  private void requireReviewed(DraftCommitBinding binding) {
    new GameDesignReviewedBaseRepository(dsl).requireRetained(binding);
  }

  private static OwnerOutcome commandOnlyOutcome(
      DraftCommitBinding binding, CommandApplication command) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-control-plane-command-application/v1");
    DraftAuthorizationFenceBinding.frame(out, binding.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, command.canonicalBytes());
    return new OwnerOutcome(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        command.resultIdentity(),
        out.toByteArray(),
        List.of(command.commandAppliedEpoch()));
  }

  private static void requireCompleteScopes(
      DraftCommitBinding binding, boolean hasCommand, boolean hasPolicy) {
    if (!hasCommand && !hasPolicy
        || !binding.requiredOwners().contains(Owner.GAME_DESIGN_CONTROL_PLANE)) {
      throw new IllegalArgumentException("Game Design owner requires typed source revisions");
    }
    List<AffectedUnit> units = binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE);
    List<AffectedUnit> commands =
        units.stream()
            .filter(
                unit ->
                    CommandSource.SCOPE.equals(unit.aggregateType())
                        && CommandSource.SCOPE.equals(unit.scopeType())
                        && CommandSource.SCOPE_ID.equals(unit.scopeId()))
            .toList();
    List<AffectedUnit> policies =
        units.stream()
            .filter(
                unit ->
                    RealmPolicySource.SCOPE.equals(unit.aggregateType())
                        && RealmPolicySource.SCOPE.equals(unit.scopeType())
                        && "effective".equals(unit.scopeId()))
            .toList();
    if (commands.size() != (hasCommand ? 1 : 0)
        || policies.size() != (hasPolicy ? 1 : 0)
        || commands.size() + policies.size() != units.size()
        || units.stream()
            .anyMatch(
                unit ->
                    !binding.target().canonicalVersionId().toString().equals(unit.aggregateId()))) {
      throw new IllegalArgumentException("Complete supported Game Design source scopes required");
    }
    if (hasPolicy) {
      binding.revisions().stream()
          .filter(revision -> revision.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
          .filter(RealmPolicySource::isPolicyRevision)
          .forEach(revision -> RealmPolicySource.revision(binding, revision));
    }
  }

  private static void requireWrite() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Game Design source writes require caller-owned writable READ_COMMITTED transaction");
    }
  }

  public record Genesis(RealmPolicyGenesis policy, CommandSource.NewDraftGenesisReceipt command) {
    public Genesis {
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(command, "command");
      if (!policy.target().equals(command.target())
          || !policy.receiptId().equals(command.receiptId())
          || !policy.creationTransactionId().equals(command.creationTransactionId())) {
        throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_IDENTITY_CONFLICT");
      }
    }
  }

  public record Application(
      DraftCommitBinding binding,
      Optional<CommandApplication> command,
      Optional<RealmPolicyApplication> policy,
      OwnerOutcome ownerOutcome) {
    public Application {
      Objects.requireNonNull(binding, "binding");
      command = Objects.requireNonNull(command, "command");
      policy = Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(ownerOutcome, "ownerOutcome");
      if (command.isEmpty() && policy.isEmpty()
          || command.isPresent() && !binding.equals(command.orElseThrow().binding())
          || policy.isPresent() && !binding.equals(policy.orElseThrow().snapshot().binding())
          || ownerOutcome.owner() != Owner.GAME_DESIGN_CONTROL_PLANE
          || ownerOutcome.status() != OwnerStatus.APPLIED
          || !binding.commitId().equals(ownerOutcome.commitId())
          || !binding.digest().equals(ownerOutcome.bindingDigest())) {
        throw new IllegalArgumentException("Owner application differs from exact Draft binding");
      }
    }
  }

  public record SynchronizedSources(CommandSnapshot command, RealmPolicySnapshot policy) {
    public SynchronizedSources {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(policy, "policy");
      if (!command.binding().equals(policy.binding())) {
        throw new IllegalStateException("GAME_DESIGN_SOURCE_SNAPSHOT_BINDING_CONFLICT");
      }
    }
  }

  public record Capture(CommandSnapshot.Capture command, RealmPolicySnapshot.Capture policy) {
    public Capture {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(policy, "policy");
      if (!Arrays.equals(command.operation().canonicalBytes(), policy.operation().canonicalBytes())
          || !command.snapshot().binding().equals(policy.snapshot().binding())) {
        throw new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_BINDING_CONFLICT");
      }
    }
  }
}
