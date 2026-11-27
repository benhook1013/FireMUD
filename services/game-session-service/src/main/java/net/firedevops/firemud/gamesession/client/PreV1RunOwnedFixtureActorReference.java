package net.firedevops.firemud.gamesession.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;

/**
 * Pre-v1 gate waiver (ADR 0185) stand-in for the run-owned fixture's actor. The fixture world has
 * no Entity preseeded assignment, so this value carries no Entity provenance: Game Session derives
 * the actor identity and runtime entity id. Its evidence bytes frame only those derived identities
 * and the exact expected target, so repeated reads of one request compare equal.
 */
public final class PreV1RunOwnedFixtureActorReference
    implements CanonicalGameplayRuntimeActorReference {
  static final String EVIDENCE_LABEL = "pre-v1-run-owned-fixture-actor/v1";

  private final String workloadNamespace;
  private final CanonicalRuntimeEntityId runtimeEntityId;
  private final UUID canonicalAccountUuid;
  private final UUID characterUuid;
  private final PreseededActorAssignmentExpectedTarget target;
  private final AssignmentOrigin origin;

  private PreV1RunOwnedFixtureActorReference(
      String workloadNamespace,
      CanonicalRuntimeEntityId runtimeEntityId,
      UUID canonicalAccountUuid,
      UUID characterUuid,
      PreseededActorAssignmentExpectedTarget target,
      AssignmentOrigin origin) {
    this.workloadNamespace = workloadNamespace;
    this.runtimeEntityId = runtimeEntityId;
    this.canonicalAccountUuid = canonicalAccountUuid;
    this.characterUuid = characterUuid;
    this.target = target;
    this.origin = origin;
  }

  /**
   * Builds the stand-in for one exact schema-1 World request. Refuses without the gate waivers (and
   * so under the prod profile). The caller owns the deterministic identity derivations.
   */
  public static PreV1RunOwnedFixtureActorReference standIn(
      PreV1GateWaivers waivers,
      String workloadNamespace,
      CanonicalInitialPlayerLocationRequest request,
      long runtimeEntityId) {
    if (waivers == null || !waivers.enabled()) {
      throw new IllegalStateException("Run-owned fixture actor stand-in requires the waivers");
    }
    Objects.requireNonNull(request, "request");
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalArgumentException("Exact Game Session workload namespace required");
    }
    if (request.getSchemaVersion() != 1
        || request.hasPlayerCreatedOrigin()
        || !request.hasExpectedTarget()
        || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Closed schema-1 preseeded World request required");
    }
    var target = request.getExpectedTarget();
    var origin =
        new AssignmentOrigin(
            canonical(request.getAssignmentUuid()), request.getAssignmentIntentDigest());
    return new PreV1RunOwnedFixtureActorReference(
        workloadNamespace,
        new CanonicalRuntimeEntityId(
            canonical(target.getCanonicalTenantUuid()),
            target.getGameInstanceId(),
            runtimeEntityId),
        canonical(request.getCanonicalAccountUuid()),
        canonical(request.getCharacterUuid()),
        target,
        origin);
  }

  @Override
  public String workloadNamespace() {
    return workloadNamespace;
  }

  @Override
  public CanonicalRuntimeEntityId runtimeEntityId() {
    return runtimeEntityId;
  }

  /** No Entity projection exists; the stand-in's first and only generation. */
  @Override
  public long projectionGeneration() {
    return 1L;
  }

  @Override
  public long projectionFence() {
    return 1L;
  }

  @Override
  public UUID canonicalAccountUuid() {
    return canonicalAccountUuid;
  }

  @Override
  public UUID characterUuid() {
    return characterUuid;
  }

  @Override
  public UUID playableStateNamespaceId() {
    return UUID.fromString(target.getPlayableStateNamespaceUuid());
  }

  @Override
  public ActorOrigin origin() {
    return origin;
  }

  @Override
  public boolean matchesWorldRequest(CanonicalInitialPlayerLocationRequest request) {
    return request != null
        && request.getSchemaVersion() == 1
        && !request.hasPlayerCreatedOrigin()
        && origin.assignmentId().toString().equals(request.getAssignmentUuid())
        && origin.intentDigest().equals(request.getAssignmentIntentDigest())
        && canonicalAccountUuid.toString().equals(request.getCanonicalAccountUuid())
        && characterUuid.toString().equals(request.getCharacterUuid())
        && target.equals(request.getExpectedTarget());
  }

  @Override
  public byte[] canonicalEvidenceBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part :
        new byte[][] {
          EVIDENCE_LABEL.getBytes(StandardCharsets.US_ASCII),
          workloadNamespace.getBytes(StandardCharsets.UTF_8),
          Long.toString(runtimeEntityId.value()).getBytes(StandardCharsets.US_ASCII),
          canonicalAccountUuid.toString().getBytes(StandardCharsets.US_ASCII),
          characterUuid.toString().getBytes(StandardCharsets.US_ASCII),
          origin.assignmentId().toString().getBytes(StandardCharsets.US_ASCII),
          origin.intentDigest().getBytes(StandardCharsets.US_ASCII),
          target.toByteArray()
        }) {
      out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static UUID canonical(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical non-nil UUID required");
    }
    return parsed;
  }

  @Override
  public String toString() {
    return "PreV1RunOwnedFixtureActorReference[redacted]";
  }
}
