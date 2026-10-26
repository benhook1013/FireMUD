package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;

/**
 * Joins World’s fresh initial-room region observation to an independent authenticated lifecycle
 * owner read. The result is an initial-placement consequence only; it grants no admission or
 * region-runtime ownership.
 */
public final class CanonicalInitialPlayerLocationRegionResolver {
  private final CanonicalInitialPlayerLocationClient initialLocationClient;
  private final CanonicalRegionBootstrapAssignmentResolver lifecycleResolver;

  public CanonicalInitialPlayerLocationRegionResolver(
      CanonicalInitialPlayerLocationClient initialLocationClient,
      CanonicalRegionBootstrapAssignmentResolver lifecycleResolver) {
    this.initialLocationClient =
        Objects.requireNonNull(initialLocationClient, "initialLocationClient");
    this.lifecycleResolver = Objects.requireNonNull(lifecycleResolver, "lifecycleResolver");
  }

  /**
   * Reads the exact current initial-room consequence, then revalidates it through World lifecycle.
   */
  public ResolvedRegion resolve(CanonicalInitialPlayerLocationRequest request) {
    Objects.requireNonNull(request, "request");
    CanonicalInitialPlayerLocationRegionResponse observed =
        initialLocationClient.readInitialPlayerLocationRegion(request);
    var lifecycle = observed.getLifecycle();
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
            lifecycle.getCanonicalRequestBytes().toByteArray());
    UUID canonicalRegionId = UUID.fromString(observed.getCanonicalRegionInstanceUuid());
    CanonicalRegionBootstrapAssignmentResolver.Assignment current =
        lifecycleResolver.resolve(lifecycleRequest, canonicalRegionId);
    if (!request.getExpectedTarget().equals(observed.getExpectedTarget())
        || !lifecycleRequest.equals(current.request())
        || !lifecycle.getLifecycleStatus().equals(current.lifecycleStatus())
        || lifecycle.getLifecycleEpoch() != current.lifecycleEpoch()
        || lifecycle.getRowVersion() != current.lifecycleRowVersion()
        || !lifecycle.getCaptureUuid().equals(current.captureId().toString())
        || !lifecycle.getGraphSha256().equals(current.graphSha256())
        || !lifecycle.getPreparationInputDigest().equals(current.preparationInputDigest())
        || !observed.getOperationalRegionUuid().equals(current.operationalRegionId().toString())) {
      throw new IllegalStateException(
          "World initial-location region consequence changed during lifecycle revalidation");
    }
    return new ResolvedRegion(observed, current);
  }

  /** Immutable resolved observation; consumers must still revalidate at their binding gate. */
  public static final class ResolvedRegion {
    private final CanonicalInitialPlayerLocationRegionResponse observation;
    private final CanonicalRegionBootstrapAssignmentResolver.Assignment lifecycleAssignment;

    private ResolvedRegion(
        CanonicalInitialPlayerLocationRegionResponse observation,
        CanonicalRegionBootstrapAssignmentResolver.Assignment lifecycleAssignment) {
      this.observation = Objects.requireNonNull(observation, "observation");
      this.lifecycleAssignment = Objects.requireNonNull(lifecycleAssignment, "lifecycleAssignment");
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Generated protobuf messages are immutable; the validated observation cannot be changed by its consumer.")
    public CanonicalInitialPlayerLocationRegionResponse observation() {
      return observation;
    }

    public CanonicalRegionBootstrapAssignmentResolver.Assignment lifecycleAssignment() {
      return lifecycleAssignment;
    }

    public UUID canonicalRegionInstanceId() {
      return lifecycleAssignment.canonicalRegionId();
    }

    public UUID operationalRegionId() {
      return lifecycleAssignment.operationalRegionId();
    }
  }
}
