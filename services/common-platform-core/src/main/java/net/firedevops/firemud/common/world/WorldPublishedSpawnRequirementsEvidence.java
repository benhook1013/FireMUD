package net.firedevops.firemud.common.world;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;

/** Exact immutable published spawn and generation inputs retained from one World source read. */
public record WorldPublishedSpawnRequirementsEvidence(
    Request request,
    CompleteLaunchBindingEvidence launchBinding,
    UUID captureId,
    String graphDigest,
    List<FamilyCount> familyCounts,
    List<SpawnRequirement> spawnRequirements,
    List<GenerationRequirement> generationRequirements) {
  public static final int SCHEMA_VERSION = 1;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern CANONICAL_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
  private static final List<WorldDesignAggregateType> FAMILY_ORDER =
      List.of(
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING);

  /** Exact source-read selector. The nested Game Design request remains unchanged. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf request messages are immutable value messages.")
  public record Request(
      String targetNamespace,
      UUID readRequestId,
      GetLaunchDescriptorRequest launchBindingRequest,
      String expectedReleaseAttestationDigest) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Generated protobuf request messages are immutable value messages.")
    public Request {
      Objects.requireNonNull(targetNamespace, "targetNamespace");
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be a canonical DNS label");
      }
      requireNonNil(readRequestId, "readRequestId");
      Objects.requireNonNull(launchBindingRequest, "launchBindingRequest");
      requireNoUnknownFields(launchBindingRequest, "launch binding request");
      UUID nestedReadRequestId =
          parseCanonicalUuid(launchBindingRequest.getRequestId(), "launch binding request ID");
      if (!readRequestId.equals(nestedReadRequestId)) {
        throw new IllegalArgumentException(
            "Launch binding request ID must equal the published spawn read request ID");
      }
      if (readRequestId.toString().equals(launchBindingRequest.getControlPlaneRequestId())) {
        throw new IllegalArgumentException(
            "Published spawn read request ID must be distinct from the original control-plane request");
      }
      parseCanonicalUuid(launchBindingRequest.getCanonicalTenantId(), "canonical tenant ID");
      requireText(launchBindingRequest.getWorldSlug(), "worldSlug");
      requireText(launchBindingRequest.getControlPlaneRequestId(), "controlPlaneRequestId");
      requireSha256(launchBindingRequest.getExpectedRequestDigest(), "expectedRequestDigest");
      requireSha256(launchBindingRequest.getExpectedResultDigest(), "expectedResultDigest");
      requireSha256(expectedReleaseAttestationDigest, "expectedReleaseAttestationDigest");
    }
  }

  /** One explicit count from the original ordered six-family declaration. */
  public record FamilyCount(WorldDesignAggregateType family, int count) {
    public FamilyCount {
      Objects.requireNonNull(family, "family");
      if (count < 0) {
        throw new IllegalArgumentException("Published World family count must be nonnegative");
      }
    }
  }

  /** Exact authored Entity template reference; the owner payload is intentionally absent. */
  public record EntityTemplateReference(
      EntityTemplateReferenceType kind, UUID tenantId, UUID versionId, UUID templateId) {
    public EntityTemplateReference {
      Objects.requireNonNull(kind, "kind");
      if (kind != EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM
          && kind != EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC) {
        throw new IllegalArgumentException("Published spawn Entity kind must be ITEM or NPC");
      }
      requireNonNil(tenantId, "entity tenantId");
      requireNonNil(versionId, "entity versionId");
      requireNonNil(templateId, "entity templateId");
    }
  }

  /** One unchanged authored spawn binding at its original revision position. */
  public record SpawnRequirement(
      String revisionOrder,
      UUID revisionId,
      UUID spawnBindingId,
      RoomTemplateRef roomTemplate,
      EntityTemplateReference entityTemplate,
      int spawnCount,
      int respawnDelaySeconds) {
    public SpawnRequirement {
      requireCanonicalDecimal(revisionOrder, "revisionOrder");
      requireNonNil(revisionId, "revisionId");
      requireNonNil(spawnBindingId, "spawnBindingId");
      Objects.requireNonNull(roomTemplate, "roomTemplate");
      Objects.requireNonNull(entityTemplate, "entityTemplate");
      if (spawnCount < 0 || respawnDelaySeconds < 0) {
        throw new IllegalArgumentException(
            "Published spawn count and respawn delay must be nonnegative");
      }
    }
  }

  /** One unchanged authored generation-rule intent at its original revision position. */
  public record GenerationRequirement(
      String revisionOrder,
      UUID revisionId,
      UUID ruleTemplateId,
      WorldDesignAggregateType scopeFamily,
      UUID scopeId,
      String name,
      String value) {
    public GenerationRequirement {
      requireCanonicalDecimal(revisionOrder, "revisionOrder");
      requireNonNil(revisionId, "revisionId");
      requireNonNil(ruleTemplateId, "ruleTemplateId");
      Objects.requireNonNull(scopeFamily, "scopeFamily");
      if (scopeFamily != WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
          && scopeFamily != WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE) {
        throw new IllegalArgumentException("Generation requirement scope must be REGION or ZONE");
      }
      requireNonNil(scopeId, "scopeId");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }
  }

  public WorldPublishedSpawnRequirementsEvidence {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(launchBinding, "launchBinding");
    requireNonNil(captureId, "captureId");
    requireSha256(graphDigest, "graphDigest");
    familyCounts = List.copyOf(Objects.requireNonNull(familyCounts, "familyCounts"));
    spawnRequirements = List.copyOf(Objects.requireNonNull(spawnRequirements, "spawnRequirements"));
    generationRequirements =
        List.copyOf(Objects.requireNonNull(generationRequirements, "generationRequirements"));

    var descriptor = launchBinding.descriptor();
    var release = launchBinding.releaseAttestation();
    if (!request.targetNamespace().equals(descriptor.targetNamespace())
        || !request.targetNamespace().equals(release.targetNamespace())
        || !descriptor
            .canonicalTenantId()
            .toString()
            .equals(request.launchBindingRequest().getCanonicalTenantId())
        || !descriptor.worldSlug().equals(request.launchBindingRequest().getWorldSlug())
        || !descriptor
            .controlPlaneRequestId()
            .equals(request.launchBindingRequest().getControlPlaneRequestId())
        || !descriptor
            .requestDigest()
            .equals(request.launchBindingRequest().getExpectedRequestDigest())
        || !descriptor
            .resultDigest()
            .equals(request.launchBindingRequest().getExpectedResultDigest())) {
      throw new IllegalArgumentException(
          "Complete launch binding differs from the exact source-read selector and namespace");
    }
    if (request.readRequestId().equals(descriptor.authoredWorldSourceOperationId())) {
      throw new IllegalArgumentException(
          "Published spawn read request ID must be distinct from the original source operation");
    }
    if (!request.expectedReleaseAttestationDigest().equals(release.evidenceDigest())) {
      throw new IllegalArgumentException(
          "Release attestation digest differs from the exact source-read selector");
    }
    if (!AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(
            release.schemaVersion())
        || release.worldStartLocationEvidence() == null) {
      throw new IllegalArgumentException(
          "Published spawn requirements require selected release World selector evidence");
    }
    String boundGraphDigest =
        WorldDraftStartLocationEvidence.fromStored(
                release.worldStartLocationEvidence().selectorReceiptBytes())
            .graphDigest();
    if (!boundGraphDigest.equals(graphDigest)) {
      throw new IllegalArgumentException(
          "Published spawn graph digest differs from the exact release World selector receipt");
    }

    if (familyCounts.size() != FAMILY_ORDER.size()) {
      throw new IllegalArgumentException("Exactly six ordered World family counts are required");
    }
    for (int index = 0; index < FAMILY_ORDER.size(); index++) {
      if (familyCounts.get(index).family() != FAMILY_ORDER.get(index)) {
        throw new IllegalArgumentException(
            "World family counts must use the canonical ordered six-family declaration");
      }
    }
    requireCount(
        familyCounts,
        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
        generationRequirements.size());
    requireCount(
        familyCounts,
        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
        spawnRequirements.size());

    UUID tenantId = release.canonicalTenantId();
    UUID versionId = release.canonicalVersionId();
    Set<UUID> revisionIds = new HashSet<>();
    Set<UUID> spawnBindingIds = new HashSet<>();
    Set<List<Object>> spawnIdentities = new HashSet<>();
    Set<UUID> ruleTemplateIds = new HashSet<>();
    requireIncreasingOrders(
        spawnRequirements.stream().map(SpawnRequirement::revisionOrder).toList());
    for (SpawnRequirement spawn : spawnRequirements) {
      if (!revisionIds.add(spawn.revisionId())) {
        throw new IllegalArgumentException("Published requirements repeat a revision identity");
      }
      if (!spawnBindingIds.add(spawn.spawnBindingId())) {
        throw new IllegalArgumentException(
            "Published spawn requirements repeat a binding identity");
      }
      if (!tenantId.equals(spawn.roomTemplate().tenantId())
          || !versionId.equals(spawn.roomTemplate().versionId())
          || !tenantId.equals(spawn.entityTemplate().tenantId())
          || !versionId.equals(spawn.entityTemplate().versionId())) {
        throw new IllegalArgumentException(
            "Published spawn references differ from the exact canonical tenant/version context");
      }
      List<Object> identity =
          List.of(
              spawn.roomTemplate().roomTemplateId(),
              spawn.entityTemplate().kind(),
              spawn.entityTemplate().templateId());
      if (!spawnIdentities.add(identity)) {
        throw new IllegalArgumentException(
            "Published spawn requirements repeat an authored identity");
      }
    }

    Set<List<Object>> generationIdentities = new HashSet<>();
    requireIncreasingOrders(
        generationRequirements.stream().map(GenerationRequirement::revisionOrder).toList());
    for (GenerationRequirement generation : generationRequirements) {
      if (!revisionIds.add(generation.revisionId())) {
        throw new IllegalArgumentException("Published requirements repeat a revision identity");
      }
      if (!ruleTemplateIds.add(generation.ruleTemplateId())) {
        throw new IllegalArgumentException(
            "Published generation requirements repeat a rule template identity");
      }
      List<Object> identity =
          List.of(generation.scopeFamily(), generation.scopeId(), generation.name());
      if (!generationIdentities.add(identity)) {
        throw new IllegalArgumentException(
            "Published generation requirements repeat an authored identity");
      }
    }
  }

  private static void requireCount(
      List<FamilyCount> familyCounts, WorldDesignAggregateType family, int expectedCount) {
    FamilyCount count =
        familyCounts.stream().filter(entry -> entry.family() == family).findFirst().orElseThrow();
    if (count.count() != expectedCount) {
      throw new IllegalArgumentException(
          "Published World family count differs from its complete source list");
    }
  }

  private static void requireIncreasingOrders(List<String> orders) {
    BigInteger previous = null;
    for (String order : orders) {
      BigInteger current = new BigInteger(order);
      if (previous != null && current.compareTo(previous) <= 0) {
        throw new IllegalArgumentException(
            "Published requirements must preserve strictly increasing revision order");
      }
      previous = current;
    }
  }

  static UUID parseCanonicalUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
  }

  static void requireCanonicalDecimal(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!CANONICAL_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a canonical nonnegative decimal string");
    }
  }

  static void requireNoUnknownFields(com.google.protobuf.Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireSha256(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be canonical tagged SHA-256 text");
    }
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
  }
}
