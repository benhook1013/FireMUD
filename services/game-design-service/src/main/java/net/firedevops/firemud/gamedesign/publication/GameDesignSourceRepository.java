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
import org.jooq.DSLContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins actual Game Design sources under one caller-owned coordinator transaction. */
public final class GameDesignSourceRepository {
  private final RealmPolicySourceRepository policies;
  private final CommandSourceRepository commands;
  private final AssetSourceRepository assets;
  private final GameplayRuleSourceRepository gameplayRules;
  private final DraftCommitCoordinatorRepository coordinator;

  public GameDesignSourceRepository(DSLContext dsl) {
    Objects.requireNonNull(dsl, "dsl");
    policies = new RealmPolicySourceRepository(dsl);
    commands = new CommandSourceRepository(dsl);
    assets = new AssetSourceRepository(dsl);
    gameplayRules = new GameplayRuleSourceRepository(dsl);
    coordinator = new DraftCommitCoordinatorRepository(dsl);
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
    Genesis result =
        new Genesis(
            policy,
            command,
            assets.enrollFreshDraft(target),
            gameplayRules.enrollFreshDraft(target));
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
    var asset = assets.readGenesis(target);
    var gameplay = gameplayRules.readGenesis(target);
    if (policy.isEmpty() && command.isEmpty() && asset.isEmpty() && gameplay.isEmpty())
      return Optional.empty();
    if (policy.isEmpty() || command.isEmpty() || asset.isEmpty() || gameplay.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
    }
    return Optional.of(
        new Genesis(
            policy.orElseThrow(),
            command.orElseThrow(),
            asset.orElseThrow(),
            gameplay.orElseThrow()));
  }

  /** Stage actual source writes and record exactly one complete local owner outcome. */
  public Application apply(DraftCommitBinding binding) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    List<CommandSource.Mutation> commandMutations = CommandSource.mutations(binding);
    boolean policyMutation = CommandSource.hasRealmPolicyRevision(binding);
    boolean assetMutation = !AssetSource.mutations(binding).isEmpty();
    boolean gameplayMutation = !GameplayRuleSource.mutations(binding).isEmpty();
    requireCompleteScopes(
        binding, !commandMutations.isEmpty(), policyMutation, assetMutation, gameplayMutation);
    requireGenesis(binding.target());
    Optional<CommandApplication> command = commands.apply(binding);
    Optional<AssetApplication> asset = assets.apply(binding);
    Optional<GameplayRuleSnapshot.Application> gameplay = gameplayRules.apply(binding);
    if (gameplay.isPresent() != gameplayMutation)
      throw new IllegalStateException("GAME_DESIGN_GAMEPLAY_APPLICATION_INCOMPLETE");
    if (asset.isPresent() != assetMutation)
      throw new IllegalStateException("GAME_DESIGN_ASSET_APPLICATION_INCOMPLETE");
    if (command.isPresent() != !commandMutations.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_COMMAND_APPLICATION_INCOMPLETE");
    }
    Optional<RealmPolicyApplication> policy = Optional.empty();
    if (policyMutation) {
      // The policy-only path retains its existing exact owner result. Mixed input stages policy
      // first, then records the combined result using the command store's actual readback bytes.
      policy =
          Optional.of(
              command.isPresent() || asset.isPresent() || gameplay.isPresent()
                  ? policies.applyMutation(binding)
                  : policies.apply(binding));
    }
    OwnerOutcome expected;
    if (asset.isPresent() || gameplay.isPresent()) {
      var component = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(
          component, "game-design-control-plane-sibling-components/v1");
      var epochs = new java.util.ArrayList<AppliedEpoch>();
      if (command.isPresent()) {
        DraftAuthorizationFenceBinding.frame(component, "COMMAND");
        DraftAuthorizationFenceBinding.frame(component, command.orElseThrow().canonicalBytes());
        epochs.add(command.orElseThrow().commandAppliedEpoch());
      }
      if (asset.isPresent()) {
        DraftAuthorizationFenceBinding.frame(component, "ASSET");
        DraftAuthorizationFenceBinding.frame(component, asset.orElseThrow().canonicalBytes());
        epochs.add(asset.orElseThrow().appliedEpoch());
      }
      if (gameplay.isPresent()) {
        DraftAuthorizationFenceBinding.frame(component, "GAMEPLAY");
        DraftAuthorizationFenceBinding.frame(component, gameplay.orElseThrow().canonicalBytes());
        epochs.add(gameplay.orElseThrow().appliedEpoch());
      }
      if (policy.isPresent()) {
        var policyResult = policy.orElseThrow();
        epochs.add(policyResult.ownerOutcome().appliedEpochs().getFirst());
        epochs.sort(
            java.util.Comparator.comparing(AppliedEpoch::aggregateType)
                .thenComparing(AppliedEpoch::aggregateId)
                .thenComparing(AppliedEpoch::scopeType)
                .thenComparing(AppliedEpoch::scopeId));
        policies.recordCombinedOwnerOutcome(binding, component.toByteArray(), List.copyOf(epochs));
        expected = policyResult.combinedOwnerOutcome(component.toByteArray(), List.copyOf(epochs));
      } else {
        epochs.sort(
            java.util.Comparator.comparing(AppliedEpoch::aggregateType)
                .thenComparing(AppliedEpoch::aggregateId)
                .thenComparing(AppliedEpoch::scopeType)
                .thenComparing(AppliedEpoch::scopeId));
        var out = new ByteArrayOutputStream();
        DraftAuthorizationFenceBinding.frame(
            out, "game-design-control-plane-ordinary-source-application/v1");
        DraftAuthorizationFenceBinding.frame(out, binding.canonicalBytes());
        DraftAuthorizationFenceBinding.frame(out, component.toByteArray());
        expected =
            new OwnerOutcome(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                OwnerStatus.APPLIED,
                binding.commitId(),
                binding.digest(),
                "ordinary-source:" + binding.commitId(),
                out.toByteArray(),
                List.copyOf(epochs));
        coordinator.recordOwnerOutcome(binding, expected);
      }
    } else if (policy.isPresent() && command.isPresent()) {
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
    return new Application(binding, command, policy, asset, gameplay, retained);
  }

  /** Invoke after the actual coordinator fence advances, before releasing its application slot. */
  public SynchronizedSources captureSynchronized(DraftCommitBinding binding) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    requireGenesis(binding.target());
    CommandSnapshot command = commands.captureSynchronized(binding);
    RealmPolicySnapshot policy = policies.captureSynchronized(binding);
    AssetSnapshot asset = assets.captureSynchronized(binding);
    GameplayRuleSnapshot gameplay = gameplayRules.captureSynchronized(binding);
    if (!binding.equals(command.binding()) || !binding.equals(policy.binding())) {
      throw new IllegalStateException("GAME_DESIGN_SYNCHRONIZED_SOURCE_BINDING_CONFLICT");
    }
    return new SynchronizedSources(command, policy, asset, gameplay);
  }

  /** Freeze both complete, selected source snapshots against the same retained operation. */
  public Capture freeze(GameDesignPublicationOperation operation) {
    requireWrite();
    Objects.requireNonNull(operation, "operation");
    requireGenesis(operation.account().input().selection().target());
    RealmPolicySnapshot.Capture policy = policies.freeze(operation);
    CommandSnapshot.Capture command = commands.freeze(operation);
    Capture result = new Capture(command, policy, assets.freeze(operation));
    Capture retained =
        readCapture(operation)
            .orElseThrow(
                () -> new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_READBACK_UNAVAILABLE"));
    if (!Arrays.equals(result.command().canonicalBytes(), retained.command().canonicalBytes())
        || !Arrays.equals(result.policy().canonicalBytes(), retained.policy().canonicalBytes())
        || !Arrays.equals(result.asset().canonicalBytes(), retained.asset().canonicalBytes())) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_READBACK_CONFLICT");
    }
    return retained;
  }

  /** Exact stored captures for a bundle producer; partial capture cannot authorize publication. */
  public Optional<Capture> readCapture(GameDesignPublicationOperation operation) {
    Objects.requireNonNull(operation, "operation");
    var command = commands.readCapture(operation);
    var policy = policies.readCapture(operation);
    var asset = assets.readCapture(operation);
    if (command.isEmpty() && policy.isEmpty() && asset.isEmpty()) return Optional.empty();
    if (command.isEmpty() || policy.isEmpty() || asset.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_INCOMPLETE");
    }
    return Optional.of(
        new Capture(command.orElseThrow(), policy.orElseThrow(), asset.orElseThrow()));
  }

  public Optional<SynchronizedSources> readSynchronized(TargetProof target, UUID commitId) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(commitId, "commitId");
    var command = commands.readSnapshot(target, commitId);
    var policy = policies.readSnapshot(target, commitId);
    var asset = assets.readSnapshot(target, commitId);
    var gameplay = gameplayRules.readSnapshot(target, commitId);
    if (command.isEmpty() && policy.isEmpty() && asset.isEmpty() && gameplay.isEmpty())
      return Optional.empty();
    if (command.isEmpty() || policy.isEmpty() || asset.isEmpty() || gameplay.isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SYNCHRONIZED_SOURCE_INCOMPLETE");
    }
    return Optional.of(
        new SynchronizedSources(
            command.orElseThrow(),
            policy.orElseThrow(),
            asset.orElseThrow(),
            gameplay.orElseThrow()));
  }

  private void requireGenesis(TargetProof target) {
    if (readGenesis(target).isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_UNAVAILABLE");
    }
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
      DraftCommitBinding binding,
      boolean hasCommand,
      boolean hasPolicy,
      boolean hasAsset,
      boolean hasGameplay) {
    if (!hasCommand && !hasPolicy && !hasAsset && !hasGameplay
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
    var assets = units.stream().filter(AssetSourceRepository::isScope).toList();
    var gameplay = units.stream().filter(GameplayRuleSource::isScope).toList();
    if (commands.size() != (hasCommand ? 1 : 0)
        || policies.size() != (hasPolicy ? 1 : 0)
        || assets.size() != (hasAsset ? 1 : 0)
        || gameplay.size() != (hasGameplay ? 1 : 0)
        || commands.size() + policies.size() + assets.size() + gameplay.size() != units.size()
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

  public record Genesis(
      RealmPolicyGenesis policy,
      CommandSource.NewDraftGenesisReceipt command,
      AssetSnapshot.Genesis asset,
      GameplayRuleSnapshot.Genesis gameplay) {
    public Genesis {
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(asset, "asset");
      Objects.requireNonNull(gameplay, "gameplay");
      if (!policy.target().equals(command.target())
          || !policy.receiptId().equals(command.receiptId())
          || !policy.creationTransactionId().equals(command.creationTransactionId())
          || !policy.target().equals(asset.target())
          || !policy.creationTransactionId().equals(asset.creationTransactionId())
          || !policy.target().equals(gameplay.target())
          || !policy.creationTransactionId().equals(gameplay.creationTransactionId())) {
        throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_IDENTITY_CONFLICT");
      }
    }
  }

  public record Application(
      DraftCommitBinding binding,
      Optional<CommandApplication> command,
      Optional<RealmPolicyApplication> policy,
      Optional<AssetApplication> asset,
      Optional<GameplayRuleSnapshot.Application> gameplay,
      OwnerOutcome ownerOutcome) {
    public Application {
      Objects.requireNonNull(binding, "binding");
      command = Objects.requireNonNull(command, "command");
      policy = Objects.requireNonNull(policy, "policy");
      asset = Objects.requireNonNull(asset, "asset");
      gameplay = Objects.requireNonNull(gameplay, "gameplay");
      Objects.requireNonNull(ownerOutcome, "ownerOutcome");
      if (command.isEmpty() && policy.isEmpty() && asset.isEmpty() && gameplay.isEmpty()
          || command.isPresent() && !binding.equals(command.orElseThrow().binding())
          || policy.isPresent() && !binding.equals(policy.orElseThrow().snapshot().binding())
          || asset.isPresent() && !binding.equals(asset.orElseThrow().binding())
          || gameplay.isPresent() && !binding.equals(gameplay.orElseThrow().binding())
          || ownerOutcome.owner() != Owner.GAME_DESIGN_CONTROL_PLANE
          || ownerOutcome.status() != OwnerStatus.APPLIED
          || !binding.commitId().equals(ownerOutcome.commitId())
          || !binding.digest().equals(ownerOutcome.bindingDigest())) {
        throw new IllegalArgumentException("Owner application differs from exact Draft binding");
      }
    }
  }

  public record SynchronizedSources(
      CommandSnapshot command,
      RealmPolicySnapshot policy,
      AssetSnapshot asset,
      GameplayRuleSnapshot gameplay) {
    public SynchronizedSources {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(asset, "asset");
      Objects.requireNonNull(gameplay, "gameplay");
      if (!command.binding().equals(policy.binding())
          || !command.binding().equals(asset.binding())
          || !command.binding().equals(gameplay.binding())) {
        throw new IllegalStateException("GAME_DESIGN_SOURCE_SNAPSHOT_BINDING_CONFLICT");
      }
    }
  }

  public record Capture(
      CommandSnapshot.Capture command,
      RealmPolicySnapshot.Capture policy,
      AssetSnapshot.Capture asset) {
    public Capture {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(asset, "asset");
      if (!Arrays.equals(command.operation().canonicalBytes(), policy.operation().canonicalBytes())
          || !command.snapshot().binding().equals(policy.snapshot().binding())
          || !command.snapshot().binding().equals(asset.snapshot().binding())
          || !Arrays.equals(
              command.operation().canonicalBytes(), asset.operation().canonicalBytes())) {
        throw new IllegalStateException("GAME_DESIGN_SOURCE_CAPTURE_BINDING_CONFLICT");
      }
    }
  }
}
