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
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CoordinatorProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered exact GD+World source composition. Unknown or denied authority/application retains
 * the reservation and application slot for exact recovery. This entry supplies no multi-owner
 * definitive-abort producer, public creator recovery, Account settlement or runtime activation.
 */
public final class GameDesignWorldSourceCommitService {
  private final AccountOriginalDraftOrderClient account;
  private final WorldOriginalDraftGraphApplyClient world;
  private final DraftCommitCoordinatorRepository coordinator;
  private final GameDesignDraftTerminalOutcomeRepository terminals;
  private final GameDesignSourceRepository sources;
  private final TransactionTemplate write;
  private final TransactionTemplate read;
  private final String namespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repositories are intentionally shared service collaborators.")
  public GameDesignWorldSourceCommitService(
      AccountOriginalDraftOrderClient account,
      WorldOriginalDraftGraphApplyClient world,
      DraftCommitCoordinatorRepository coordinator,
      GameDesignDraftTerminalOutcomeRepository terminals,
      GameDesignSourceRepository sources,
      PlatformTransactionManager transactions,
      String namespace) {
    this.account = Objects.requireNonNull(account);
    this.world = Objects.requireNonNull(world);
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

  /** Internal exact retry uses retained terminal evidence before mutable creator authority. */
  public GameDesignDraftTerminalOutcome commit(
      DraftAuthorizationFenceBinding original, String originalCreatorCredential) {
    outsideSql();
    var operation = new GameDesignDraftTerminalOperation(original);
    var binding = operation.gameDesignBinding();
    if (!binding
            .requiredOwners()
            .equals(List.of(Owner.WORLD_MANAGEMENT, Owner.GAME_DESIGN_CONTROL_PLANE))
        || !original
            .requiredOwners()
            .equals(
                List.of(
                    DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                    DraftAuthorizationFenceBinding.Owner.WORLD)))
      throw new IllegalArgumentException("Complete GD+World owner vector required");
    var request =
        WorldOriginalDraftGraphApplyEvidence.Request.create(
            namespace, operation.accountBindingBytes());
    var retained = Objects.requireNonNull(read.execute(ignored -> terminal(operation)));
    if (retained.isPresent()) return retained.orElseThrow();
    retained =
        Objects.requireNonNull(
            write.execute(
                ignored -> {
                  coordinator.lockVersionTarget(binding.target());
                  var existing = terminal(operation);
                  if (existing.isPresent()) return existing;
                  coordinator.claim(binding, original);
                  coordinator.claimApplicationSlot(binding);
                  return Optional.empty();
                }));
    if (retained.isPresent()) return retained.orElseThrow();
    outsideSql();
    var orderRequest =
        AccountOriginalDraftOrderGrpcCodec.Request.create(
            namespace, original, originalCreatorCredential);
    var ordered = account.claim(orderRequest);
    if (ordered == null || !ordered.matches(orderRequest))
      throw new IllegalStateException("Exact authenticated original Account order required");
    outsideSql();
    var staged =
        Objects.requireNonNull(
            write.execute(
                ignored -> {
                  coordinator.lockVersionTarget(binding.target());
                  var existing = terminal(operation);
                  if (existing.isPresent()) return new Staged(existing, Optional.empty());
                  var claimed = coordinator.claim(binding, original);
                  coordinator.claimApplicationSlot(binding);
                  var local = claimed.ownerStates().get(Owner.GAME_DESIGN_CONTROL_PLANE);
                  if (local == null)
                    throw new IllegalStateException("Complete GD owner state required");
                  if (local.status() == OwnerStatus.NOT_ATTEMPTED) {
                    coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
                    requireApplied(
                        binding,
                        sources.apply(binding).ownerOutcome(),
                        Owner.GAME_DESIGN_CONTROL_PLANE);
                  } else if (local.status() == OwnerStatus.APPLIED) {
                    requireApplied(
                        binding, local.outcome().orElseThrow(), Owner.GAME_DESIGN_CONTROL_PLANE);
                  } else {
                    throw new IllegalStateException("Unresolved GD source requires exact recovery");
                  }
                  var remote = claimed.ownerStates().get(Owner.WORLD_MANAGEMENT);
                  if (remote == null)
                    throw new IllegalStateException("Complete World owner state required");
                  if (remote.status() == OwnerStatus.APPLIED) {
                    var outcome = remote.outcome().orElseThrow();
                    requireApplied(binding, outcome, Owner.WORLD_MANAGEMENT);
                    return new Staged(Optional.empty(), Optional.of(outcome));
                  }
                  if (remote.status() == OwnerStatus.NOT_ATTEMPTED)
                    coordinator.markOwnerInProgress(binding, Owner.WORLD_MANAGEMENT);
                  else if (remote.status() != OwnerStatus.IN_PROGRESS
                      && remote.status() != OwnerStatus.UNKNOWN)
                    throw new IllegalStateException(
                        "World owner has no successful recoverable result");
                  return new Staged(Optional.empty(), Optional.empty());
                }));
    if (staged.terminal().isPresent()) return staged.terminal().orElseThrow();
    outsideSql();
    OwnerOutcome worldOutcome =
        staged
            .worldOutcome()
            .orElseGet(
                () -> {
                  var response =
                      Objects.requireNonNull(
                          world.apply(request), "Authenticated World result required");
                  if (!request.equals(response.request()))
                    throw new IllegalArgumentException(
                        "World result changed the complete original request");
                  // Revalidate even an injected client's returned value; the strict carrier checks
                  // the complete
                  // original binding, APPLIED bytes and every World expected/resulting epoch.
                  var verified =
                      new WorldOriginalDraftGraphApplyEvidence.Result(
                          request, response.ownerReadback());
                  return new OwnerOutcome(
                      Owner.WORLD_MANAGEMENT,
                      OwnerStatus.APPLIED,
                      binding.commitId(),
                      binding.digest(),
                      verified.resultIdentity(),
                      verified.ownerReadback().result(),
                      verified.appliedEpochs().stream()
                          .map(
                              epoch ->
                                  new AppliedEpoch(
                                      epoch.aggregateType(),
                                      epoch.aggregateId(),
                                      epoch.scopeType(),
                                      epoch.scopeId(),
                                      epoch.expectedEpoch(),
                                      epoch.resultingEpoch()))
                          .toList());
                });
    outsideSql();
    write.executeWithoutResult(
        ignored -> {
          coordinator.lockVersionTarget(binding.target());
          if (terminal(operation).isPresent()) return;
          requireApplied(binding, worldOutcome, Owner.WORLD_MANAGEMENT);
          coordinator.recordOwnerOutcome(binding, worldOutcome);
          var actual = coordinator.read(binding.target(), binding.requestId()).orElseThrow();
          if (!binding.equals(actual.binding()))
            throw new IllegalStateException("Complete retained coordinator binding required");
          var outcomes =
              binding.requiredOwners().stream()
                  .map(
                      owner -> {
                        var state = actual.ownerStates().get(owner);
                        if (state == null || state.status() != OwnerStatus.APPLIED)
                          throw new IllegalStateException(
                              "Every exact owner must be durably APPLIED");
                        var outcome = state.outcome().orElseThrow();
                        requireApplied(binding, outcome, owner);
                        return outcome;
                      })
                  .toList();
          coordinator.advanceSourceVisibilityFence(
              binding, new CoordinatorProof(binding, outcomes));
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
    if (retained.isEmpty()) return retained;
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

  private static void requireApplied(
      DraftCommitBinding binding, OwnerOutcome outcome, Owner owner) {
    if (outcome.owner() != owner
        || outcome.status() != OwnerStatus.APPLIED
        || !binding.commitId().equals(outcome.commitId())
        || !binding.digest().equals(outcome.bindingDigest()))
      throw new IllegalStateException("Exact APPLIED owner result required");
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Original source commit requires no ambient transaction");
  }

  private record Staged(
      Optional<GameDesignDraftTerminalOutcome> terminal, Optional<OwnerOutcome> worldOutcome) {}
}
