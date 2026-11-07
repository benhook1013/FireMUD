package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationBuildGrpcCodec.Build;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves the actual frozen World graph from exact retained build provenance; writes nothing. */
public final class WorldPrivatePreparationBuildAssembly {
  private final WorldPrivatePreparationSourceClient source;
  private final WorldCanonicalFrozenTopologyRepository topology;
  private final Clock clock;

  public WorldPrivatePreparationBuildAssembly(
      WorldPrivatePreparationSourceClient source,
      WorldCanonicalFrozenTopologyRepository topology,
      Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.topology = Objects.requireNonNull(topology);
    this.clock = Objects.requireNonNull(clock);
  }

  public Inputs assemble(Scope scope, UUID preparationRequestId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Private build assembly refuses ambient SQL");
    Deadline deadline =
        Deadline.after(
            PrivateWorldPreparationReadGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    if (Context.current().getDeadline() != null)
      deadline = deadline.minimum(Context.current().getDeadline());
    live(deadline);
    Build first = source.readBuild(scope, preparationRequestId, deadline).evidence();
    var frozen =
        topology
            .readCommitted(capture(first.selectedWorld().request()))
            .orElseThrow(
                () -> new IllegalStateException("Exact selected World frozen graph is absent"));
    if (!frozen.request().freeze().equals(capture(first.selectedWorld().request())))
      throw new IllegalStateException("World owner returned a different frozen selection");
    var identity = frozen.sourceIdentity();
    var ownerSource = identity.sourceIntakeReceipt();
    var expected = first.source().snapshot();
    if (!identity.targetNamespace().equals(expected.scope().targetNamespace())
        || !identity.canonicalTenantId().equals(expected.scope().tenantId())
        || !identity.worldSlug().equals(expected.scope().worldSlug())
        || !identity.canonicalVersionId().equals(expected.source().canonicalVersionId())
        || !ownerSource.sourceOperationId().equals(expected.source().sourceOperationId())
        || !ownerSource.sourceEvidenceDigest().equals(expected.source().sourceEvidenceDigest())
        || !ownerSource.intakeRequestId().equals(first.selectedWorld().request().intakeRequestId()))
      throw new IllegalStateException(
          "World graph source differs from private preparation provenance");
    var plan = WorldCanonicalInstanceTopologyPlan.create(frozen);
    Build second = source.readBuild(scope, preparationRequestId, deadline).evidence();
    if (first.source().request().readRequestId().equals(second.source().request().readRequestId())
        || second.source().snapshot().observedAt().isBefore(first.source().snapshot().observedAt())
        || !Arrays.equals(
            PrivateWorldPreparationReadGrpcCodec.stableBytes(first.source().snapshot()),
            PrivateWorldPreparationReadGrpcCodec.stableBytes(second.source().snapshot()))
        || !Arrays.equals(
            first.selectedWorld().canonicalBytes(), second.selectedWorld().canonicalBytes()))
      throw new IllegalStateException(
          "Selected private source changed around the World owner read");
    first.source().snapshot().requireCurrentAt(clock.instant());
    second.source().snapshot().requireCurrentAt(clock.instant());
    live(deadline);
    return new Inputs(first, second, plan);
  }

  private static void live(Deadline deadline) {
    if (Context.current().isCancelled()) throw Status.CANCELLED.asRuntimeException();
    if (deadline.isExpired()) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
  }

  static WorldAuthoredGraphSnapshot.CaptureRequest capture(
      WorldPublishedStartLocationEvidence.Request request) {
    return new WorldAuthoredGraphSnapshot.CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        request.worldAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList());
  }

  /**
   * Independent source observations and an actual local frozen graph, not a materialization permit.
   */
  public static final class Inputs {
    private final Build first;
    private final Build readback;
    private final WorldCanonicalInstanceTopologyPlan topology;

    private Inputs(Build first, Build readback, WorldCanonicalInstanceTopologyPlan topology) {
      this.first = first;
      this.readback = readback;
      this.topology = topology;
    }

    public Build firstRead() {
      return first;
    }

    public Build committedReadback() {
      return readback;
    }

    public WorldCanonicalInstanceTopologyPlan topology() {
      return topology;
    }

    public boolean admissionAllowed() {
      return false;
    }
  }
}
