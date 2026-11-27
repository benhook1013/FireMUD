package net.firedevops.firemud.gamesession.service;

import io.grpc.Deadline;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;

/** Resolves a canonical region through the authenticated World lifecycle owner read. */
public final class CanonicalRegionBootstrapAssignmentResolver {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final WorldCanonicalInstanceLifecycleClient lifecycleClient;

  public CanonicalRegionBootstrapAssignmentResolver(
      WorldCanonicalInstanceLifecycleClient lifecycleClient) {
    this.lifecycleClient = Objects.requireNonNull(lifecycleClient, "lifecycleClient");
  }

  /**
   * Reads World-owned lifecycle evidence and resolves one exact canonical region assignment.
   *
   * <p>The result is only a validated source assignment. It does not create runtime ownership,
   * initialize a tick timeline, or grant admission.
   */
  public Assignment resolve(
      WorldCanonicalInstanceLifecycleEvidence.Request request, UUID canonicalRegionId) {
    return resolve(request, canonicalRegionId, null);
  }

  public Assignment resolve(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      UUID canonicalRegionId,
      Deadline operationDeadline) {
    Objects.requireNonNull(request, "request");
    requireNonNil(canonicalRegionId, "canonicalRegionId");

    WorldCanonicalInstanceLifecycleEvidence evidence =
        operationDeadline == null
            ? lifecycleClient.read(request)
            : lifecycleClient.read(request, operationDeadline);
    if (evidence == null) {
      throw new IllegalStateException("World lifecycle client returned no owner evidence");
    }
    if (!request.equals(evidence.request())) {
      throw new IllegalStateException(
          "World lifecycle response is not bound to the exact requested target");
    }
    if (!"ACTIVE".equals(evidence.lifecycleStatus())) {
      throw new IllegalStateException("World lifecycle evidence is not ACTIVE");
    }

    Map<UUID, UUID> assignments = evidence.operationalRegionAssignments();
    UUID operationalRegionId = assignments.get(canonicalRegionId);
    if (operationalRegionId == null) {
      throw new IllegalStateException(
          "World lifecycle evidence has no operational assignment for the canonical region");
    }
    requireNonNil(operationalRegionId, "operationalRegionId");
    return new Assignment(evidence, canonicalRegionId, operationalRegionId);
  }

  private static void requireNonNil(UUID value, String fieldName) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be a non-nil UUID");
    }
  }

  /**
   * Immutable owner-read result. Construction is restricted to this authenticated resolver, or to
   * the pre-v1 run-owned fixture stand-in below. A fixture assignment has no capture or graph.
   */
  public static final class Assignment {
    private final WorldCanonicalInstanceLifecycleEvidence.Request request;
    private final UUID canonicalRegionId;
    private final UUID operationalRegionId;
    private final String lifecycleStatus;
    private final long lifecycleEpoch;
    private final long lifecycleRowVersion;
    private final UUID captureId;
    private final String graphSha256;
    private final String preparationInputDigest;

    private Assignment(
        WorldCanonicalInstanceLifecycleEvidence evidence,
        UUID canonicalRegionId,
        UUID operationalRegionId) {
      this.request = evidence.request();
      this.canonicalRegionId = canonicalRegionId;
      this.operationalRegionId = operationalRegionId;
      this.lifecycleStatus = evidence.lifecycleStatus();
      this.lifecycleEpoch = evidence.lifecycleEpoch();
      this.lifecycleRowVersion = evidence.rowVersion();
      this.captureId = evidence.captureId();
      this.graphSha256 = evidence.graphSha256();
      this.preparationInputDigest = evidence.preparationInputDigest();
    }

    private Assignment(
        WorldRunOwnedFixtureLifecycleEvidence evidence,
        UUID canonicalRegionId,
        UUID operationalRegionId) {
      this.request = evidence.request();
      this.canonicalRegionId = canonicalRegionId;
      this.operationalRegionId = operationalRegionId;
      this.lifecycleStatus = evidence.lifecycleStatus();
      this.lifecycleEpoch = evidence.lifecycleEpoch();
      this.lifecycleRowVersion = evidence.rowVersion();
      this.captureId = null;
      this.graphSha256 = null;
      this.preparationInputDigest = evidence.preparationInputDigest();
    }

    /**
     * Pre-v1 gate waiver (ADR 0185) stand-in: the run-owned fixture world has no region graph or
     * operational region assignment map, so the caller supplies its fixed region identities over
     * World's ACTIVE fixture lifecycle. Refuses without the waivers (and so under prod).
     */
    public static Assignment preV1RunOwnedFixture(
        PreV1GateWaivers waivers,
        WorldRunOwnedFixtureLifecycleEvidence evidence,
        UUID canonicalRegionId,
        UUID operationalRegionId) {
      if (waivers == null || !waivers.enabled()) {
        throw new IllegalStateException("Run-owned fixture region assignment requires waivers");
      }
      Objects.requireNonNull(evidence, "evidence");
      if (!"ACTIVE".equals(evidence.lifecycleStatus())) {
        throw new IllegalStateException("World fixture lifecycle evidence is not ACTIVE");
      }
      requireNonNil(canonicalRegionId, "canonicalRegionId");
      requireNonNil(operationalRegionId, "operationalRegionId");
      return new Assignment(evidence, canonicalRegionId, operationalRegionId);
    }

    /** True for the run-owned fixture stand-in, which has no capture or graph. */
    public boolean runOwnedFixture() {
      return captureId == null;
    }

    public WorldCanonicalInstanceLifecycleEvidence.Request request() {
      return request;
    }

    public UUID canonicalRegionId() {
      return canonicalRegionId;
    }

    public UUID operationalRegionId() {
      return operationalRegionId;
    }

    public String lifecycleStatus() {
      return lifecycleStatus;
    }

    public long lifecycleEpoch() {
      return lifecycleEpoch;
    }

    public long lifecycleRowVersion() {
      return lifecycleRowVersion;
    }

    public UUID captureId() {
      return captureId;
    }

    public String graphSha256() {
      return graphSha256;
    }

    public String preparationInputDigest() {
      return preparationInputDigest;
    }
  }
}
