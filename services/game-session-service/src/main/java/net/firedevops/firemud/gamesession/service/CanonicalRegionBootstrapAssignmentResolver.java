package net.firedevops.firemud.gamesession.service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;

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
    Objects.requireNonNull(request, "request");
    requireNonNil(canonicalRegionId, "canonicalRegionId");

    WorldCanonicalInstanceLifecycleEvidence evidence = lifecycleClient.read(request);
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

  /** Immutable owner-read result. Construction is restricted to this authenticated resolver. */
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
