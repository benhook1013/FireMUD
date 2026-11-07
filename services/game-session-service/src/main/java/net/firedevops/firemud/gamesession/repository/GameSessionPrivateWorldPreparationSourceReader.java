package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Preparation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.RuntimeAllocation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Selection;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Source;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Target;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;

/** Shared owner-local source assembly. Caller supplies a writable transaction for shared locks. */
public final class GameSessionPrivateWorldPreparationSourceReader {
  private final GameSessionCurrentPrivatePlaytestRepository current;
  private final GameSessionPrivateFreshTargetRepository targets;
  private final GameSessionPrivateFreshRuntimeAllocationRepository runtimes;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The owner repositories are injected service dependencies, not owned mutable value state")
  public GameSessionPrivateWorldPreparationSourceReader(
      GameSessionCurrentPrivatePlaytestRepository current,
      GameSessionPrivateFreshTargetRepository targets,
      GameSessionPrivateFreshRuntimeAllocationRepository runtimes) {
    this.current = Objects.requireNonNull(current);
    this.targets = Objects.requireNonNull(targets);
    this.runtimes = Objects.requireNonNull(runtimes);
  }

  public Snapshot read(PrivateWorldPreparationReadGrpcCodec.Request request) {
    Scope scope = request.scope();
    var selected =
        current
            .readCurrent(
                scope.targetNamespace(), scope.tenantId(), scope.worldSlug(), scope.realmSlug())
            .orElseThrow(() -> new IllegalStateException("Current private preparation is absent"));
    var original = selected.selectedPrepSnapshot();
    var p = original.preparation();
    if (!request.expectedPreparationRequestId().equals(p.request().requestId())
        || original.state() != GameSessionInitialPlaytestLifecycleRepository.ReadState.INCOMPLETE)
      throw new IllegalStateException("Expected current private preparation is unavailable");
    var allocated =
        targets
            .readForWorldPreparation(p.request().requestId(), p.playtestLifecycleId())
            .orElseThrow(() -> new IllegalStateException("Allocated private target is absent"));
    var runtime =
        runtimes
            .readForWorldPreparation(p.request().requestId(), p.playtestLifecycleId())
            .orElseThrow(
                () -> new IllegalStateException("Allocated private runtime identity is absent"));
    if (!runtime.matches(allocated))
      throw new IllegalStateException("Private runtime allocation differs from exact target");
    var selection = selected.selection();
    var source = p.source();
    var s =
        new Selection(
            selection.request().requestId(),
            selection.request().expectedHeadRevision(),
            selection.request().expectedCurrentLifecycleId(),
            selection.headRevision(),
            selection.requestDigest(),
            selection.selectedAt(),
            ByteString.copyFrom(selection.canonicalPreimage()));
    var prepared =
        new Preparation(
            p.request().requestId(),
            p.playtestLifecycleId(),
            p.playableStateNamespaceId(),
            p.playtestStateGeneration(),
            p.preparationState(),
            p.request().initializationMode().name(),
            p.requestDigest(),
            p.createdAt(),
            p.request().forkExpiresAt(),
            ByteString.copyFrom(p.request().canonicalPreimage()));
    var retainedSource =
        new Source(
            source.catalogRealmId(),
            source.catalogRequestId(),
            source.catalogRevision(),
            source.catalogReceiptDigest(),
            source.intakeOperationId(),
            source.intakeReceiptDigest(),
            source.sourceOperationId(),
            source.sourceEvidenceDigest(),
            source.canonicalVersionId(),
            source.publishedReleaseBundleRef(),
            source.publishedReleaseBundleDigest(),
            source.policySetDigest(),
            source.policyCarrierDigest(),
            source.targetPolicy());
    var target =
        new Target(
            new Scope(
                allocated.targetNamespace(),
                allocated.tenantId(),
                allocated.worldSlug(),
                allocated.realmSlug()),
            allocated.realmId(),
            allocated.realmDisplayName(),
            allocated.visible(),
            allocated.publicProduction(),
            allocated.stateScope(),
            allocated.playableStateNamespaceId(),
            allocated.characterCreationPolicy(),
            allocated.catalogRevision(),
            allocated.creationRequestId(),
            allocated.requestDigest(),
            allocated.receiptDigest(),
            allocated.preparationRequestId(),
            allocated.playtestLifecycleId(),
            allocated.playtestStateGeneration(),
            allocated.preparationState(),
            allocated.sourceCatalogRealmId(),
            allocated.targetPolicyId(),
            allocated.targetPolicyDigest(),
            allocated.canonicalVersionId(),
            allocated.publishedReleaseBundleRef(),
            allocated.publishedReleaseBundleDigest());
    // Observe the database clock after every shared-locked source read, keeping the original head
    // and complete preparation stable within this transaction rather than dating a later target
    // observation with the earlier preparation-read time.
    var confirmed =
        current
            .readCurrent(
                scope.targetNamespace(), scope.tenantId(), scope.worldSlug(), scope.realmSlug())
            .orElseThrow(
                () -> new IllegalStateException("Current private preparation disappeared"));
    if (!selected.selection().equals(confirmed.selection())
        || !p.equals(confirmed.selectedPrepSnapshot().preparation())
        || confirmed.selectedPrepSnapshot().state()
            != GameSessionInitialPlaytestLifecycleRepository.ReadState.INCOMPLETE)
      throw new IllegalStateException("Complete private source changed during owner read");
    return new Snapshot(
        scope,
        s,
        prepared,
        retainedSource,
        target,
        new RuntimeAllocation(
            runtime.allocationRequestId(),
            runtime.gameInstanceId(),
            runtime.requestDigest(),
            runtime.receiptDigest()),
        confirmed.selectedPrepSnapshot().observedAt());
  }
}
