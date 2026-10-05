package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.EntityTemplateReference;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.Node;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure UUID-qualified validation of complete fresh six-family World input: PURE_UNVERIFIED only.
 *
 * <p>This does not authenticate source or Entity evidence, authorize Account commits, allocate or
 * read storage, report APPLIED, expose a public handler, capture released content, or establish
 * lifecycle/runtime readiness. Existing numeric REGION plans and historical graph bytes are
 * separate. Free-text and localized JSON fields are preserved, not semantically validated. Zero
 * spacing, exit cost and spawn count preserve the existing owner-default input convention; a future
 * writer must apply those defaults without changing the original binding.
 */
public final class WorldDraftTopologyCommitPlan {
  private static final ObjectMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final String TYPE_PREFIX = "WORLD_DESIGN_AGGREGATE_TYPE_";
  private static final String SCOPE_PREFIX = "WORLD_DESIGN_SCOPE_TYPE_";

  private record ObjectReference(WorldDesignAggregateType kind, UUID templateId) {}

  private final DraftCommitBinding binding;
  private final OwnerBinding ownerBinding;
  private final WorldDraftTopologyInputGraph graph;

  private WorldDraftTopologyCommitPlan(
      DraftCommitBinding binding, OwnerBinding ownerBinding, WorldDraftTopologyInputGraph graph) {
    this.binding = binding;
    this.ownerBinding = ownerBinding;
    this.graph = graph;
  }

  /**
   * Validates the exact supplied target and complete fresh input, without consulting any owner. All
   * World references must close inside this graph; existing mappings are never guessed.
   */
  public static WorldDraftTopologyCommitPlan create(
      DraftCommitBinding binding, OwnerBinding ownerBinding) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(ownerBinding, "ownerBinding");
    var target = binding.target();
    if (!target.canonicalTenantId().equals(ownerBinding.canonicalTenantId())
        || !target.canonicalVersionId().equals(ownerBinding.canonicalVersionId())
        || target.gameDesignVersionRowId() != ownerBinding.gameDesignVersionId()
        || !"NEW_GAME_ROW".equals(target.sourceProvenanceKind())) {
      throw invalid("Fresh World input differs from its supplied canonical target/source kind");
    }

    Map<ObjectReference, Node> nodes = new LinkedHashMap<>();
    Set<UUID> revisionIds = new HashSet<>();
    List<AffectedUnit> expected = new ArrayList<>();
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        continue;
      }
      WorldDesignMutationRevision mutation = parse(revision.payload());
      if (!binding.commitId().toString().equals(mutation.getCommitId())
          || !revision.revisionId().toString().equals(mutation.getLogicalRevisionId())
          || !revisionIds.add(revision.revisionId())) {
        throw invalid("World payload commit/revision identity differs from its complete input");
      }
      validateShape(mutation);
      UUID id = uuid(mutation.getAggregateId());
      UUID scope = uuid(mutation.getScopeId());
      EntityTemplateReference entity = null;
      if (mutation.hasWorldEntitySpawnBinding()) {
        var spawn = mutation.getWorldEntitySpawnBinding();
        entity =
            new EntityTemplateReference(
                spawn.getEntityTemplateType(), target.canonicalTenantId(),
                target.canonicalVersionId(), uuid(spawn.getEntityTemplateId()));
      }
      Node node =
          new Node(revision.revisionOrder(), revision.revisionId(), id, scope, mutation, entity);
      if (nodes.putIfAbsent(new ObjectReference(mutation.getAggregateType(), id), node) != null) {
        throw invalid("Fresh graph repeats an authored identity within the same World family");
      }
      String type = mutation.getAggregateType().name().substring(TYPE_PREFIX.length());
      expected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT, type, id.toString(), "AGGREGATE", id.toString(), "0"));
      expected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              type,
              id.toString(),
              mutation.getScopeType().name().substring(SCOPE_PREFIX.length()),
              scope.toString(),
              "0"));
    }
    if (nodes.isEmpty()) {
      throw invalid("Fresh graph requires World input; an empty binding is not topology");
    }
    validateClosure(nodes);
    List<AffectedUnit> actual = binding.affectedUnits(Owner.WORLD_MANAGEMENT);
    if (actual.size() != expected.size()
        || !new HashSet<>(actual).equals(new HashSet<>(expected))) {
      throw invalid("World affected units omit or change a derived aggregate/containing scope");
    }
    return new WorldDraftTopologyCommitPlan(
        binding,
        ownerBinding,
        new WorldDraftTopologyInputGraph(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            new ArrayList<>(nodes.values())));
  }

  /** Exact immutable enclosing binding, including other owners and original payload bytes. */
  public DraftCommitBinding binding() {
    return binding;
  }

  /** Supplied source-qualified binding, retained unchanged and still unauthenticated. */
  public OwnerBinding ownerBinding() {
    return ownerBinding;
  }

  /** PURE_UNVERIFIED logical input only; no private row IDs or released snapshot. */
  public WorldDraftTopologyInputGraph graph() {
    return graph;
  }

  private static WorldDesignMutationRevision parse(String payload) {
    try {
      var parsed = STRICT_JSON.readTree(payload);
      if (parsed == null || !parsed.isObject()) {
        throw invalid("World typed payload must be an object");
      }
      var builder = WorldDesignMutationRevision.newBuilder();
      JsonFormat.parser().merge(payload, builder);
      return builder.build();
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalArgumentException("Unsupported World typed JSON", exception);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException(
          "World typed JSON is not strict complete input", exception);
    }
  }

  private static void validateShape(WorldDesignMutationRevision m) {
    if (m.getOperation() != WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT
        || m.getScopeMutationPolicy()
            != WorldDesignScopeMutationPolicy.WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED
        || (m.getScopeType() != WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE
            && m.getScopeType() != WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE)
        || m.getExpectedDraftRevisionEpoch() != 0
        || m.getExpectedDraftScopeRevisionEpoch() != 0) {
      throw invalid(
          "Fresh input supports scoped UPSERT with zero epochs and no replacement policy");
    }
    switch (m.getAggregateType()) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> {
        require(m.hasRegion(), "REGION payload required");
        var p = m.getRegion();
        text(p.getName(), 100);
        require(
            p.getWeather().codePointCount(0, p.getWeather().length()) <= 50
                && p.getGeneratorType().codePointCount(0, p.getGeneratorType().length()) <= 50,
            "Region weather/generator exceeds storage range");
        require(
            p.getShardId() >= 0
                && Double.isFinite(p.getSpacingMultiplier())
                && p.getSpacingMultiplier() >= 0,
            "Invalid region shard/spacing");
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ZONE -> {
        require(m.hasZone(), "ZONE payload required");
        text(m.getZone().getName(), 100);
        uuid(m.getZone().getRegionId());
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM -> {
        require(m.hasRoom(), "ROOM payload required");
        text(m.getRoom().getName(), 100);
        uuid(m.getRoom().getZoneId());
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> {
        require(m.hasRoomExit(), "ROOM_EXIT payload required");
        var p = m.getRoomExit();
        uuid(p.getFromRoomId());
        uuid(p.getToRoomId());
        text(p.getDirection(), 32);
        require(p.getCost() >= 0, "Exit cost must be nonnegative; zero retains the owner default");
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> {
        require(m.hasGenerationRule(), "GENERATION_RULE payload required");
        text(m.getGenerationRule().getName(), 100);
        require(
            m.getGenerationRule()
                    .getValue()
                    .codePointCount(0, m.getGenerationRule().getValue().length())
                <= 255,
            "Rule value exceeds storage range");
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING -> {
        require(m.hasWorldEntitySpawnBinding(), "SPAWN payload required");
        var p = m.getWorldEntitySpawnBinding();
        uuid(p.getRoomId());
        uuid(p.getEntityTemplateId());
        require(
            (p.getEntityTemplateType()
                        == EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM
                    || p.getEntityTemplateType()
                        == EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                && p.getSpawnCount() >= 0
                && p.getRespawnDelaySeconds() >= 0,
            "Invalid spawn type/count/delay");
      }
      default ->
          throw invalid("Unsupported fresh World family; generated subtrees are not full input");
    }
  }

  private static void validateClosure(Map<ObjectReference, Node> nodes) {
    Map<UUID, UUID> zoneRegions = new HashMap<>();
    Map<UUID, UUID> roomZones = new HashMap<>();
    for (Node node : nodes.values()) {
      var m = node.mutation();
      if (m.hasZone()) {
        UUID region = uuid(m.getZone().getRegionId());
        parent(nodes, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION);
        zoneRegions.put(node.templateId(), region);
      } else if (m.hasRoom()) {
        UUID zone = uuid(m.getRoom().getZoneId());
        parent(nodes, zone, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE);
        roomZones.put(node.templateId(), zone);
      }
    }
    Set<List<String>> rules = new HashSet<>();
    Set<List<String>> spawns = new HashSet<>();
    Set<List<String>> exits = new HashSet<>();
    for (Node node : nodes.values()) {
      var m = node.mutation();
      boolean regionScope =
          m.getScopeType() == WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE;
      parent(
          nodes,
          node.scopeId(),
          regionScope
              ? WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
              : WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE);
      switch (m.getAggregateType()) {
        case WORLD_DESIGN_AGGREGATE_TYPE_REGION ->
            require(
                regionScope && node.templateId().equals(node.scopeId()),
                "Region scope must be itself");
        case WORLD_DESIGN_AGGREGATE_TYPE_ZONE ->
            require(
                regionScope
                    ? node.scopeId().equals(zoneRegions.get(node.templateId()))
                    : node.scopeId().equals(node.templateId()),
                "Zone is outside declared scope");
        case WORLD_DESIGN_AGGREGATE_TYPE_ROOM ->
            roomInScope(node.templateId(), node, roomZones, zoneRegions);
        case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> {
          var p = m.getRoomExit();
          UUID from = uuid(p.getFromRoomId());
          UUID to = uuid(p.getToRoomId());
          roomInScope(from, node, roomZones, zoneRegions);
          roomInScope(to, node, roomZones, zoneRegions);
          require(
              exits.add(List.of(from.toString(), p.getDirection())),
              "Duplicate room/direction exit");
        }
        case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE ->
            require(
                rules.add(
                    List.of(
                        m.getScopeType().name(),
                        node.scopeId().toString(),
                        m.getGenerationRule().getName())),
                "Duplicate scoped generation rule");
        case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING -> {
          var p = m.getWorldEntitySpawnBinding();
          roomInScope(uuid(p.getRoomId()), node, roomZones, zoneRegions);
          require(
              spawns.add(
                  List.of(
                      p.getRoomId(), p.getEntityTemplateType().name(), p.getEntityTemplateId())),
              "Duplicate typed room/entity spawn binding");
        }
        default -> throw invalid("Unsupported World family");
      }
    }
  }

  private static void roomInScope(
      UUID room, Node node, Map<UUID, UUID> rooms, Map<UUID, UUID> zones) {
    UUID zone = rooms.get(room);
    require(zone != null, "Room reference is not in the complete fresh graph");
    UUID containing =
        node.mutation().getScopeType() == WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE
            ? zone
            : zones.get(zone);
    require(node.scopeId().equals(containing), "Room or exit endpoint is outside declared scope");
  }

  private static void parent(
      Map<ObjectReference, Node> nodes, UUID id, WorldDesignAggregateType type) {
    Node node = nodes.get(new ObjectReference(type, id));
    require(node != null, "Missing or wrong-family reference in complete fresh graph");
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      require(
          id.toString().equals(value) && !id.equals(new UUID(0, 0)),
          "World logical IDs require canonical non-nil UUIDs");
      return id;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "World logical IDs require canonical non-nil UUIDs", exception);
    }
  }

  private static void text(String value, int maximum) {
    require(
        !value.isBlank() && value.codePointCount(0, value.length()) <= maximum,
        "Required World text is blank or exceeds storage range");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw invalid(message);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
