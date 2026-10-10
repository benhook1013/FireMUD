package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Row;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Template;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.EntityTemplateReference;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;

/**
 * Pure typed projection of a frozen World topology for a possible instance materialization.
 *
 * <p>The source remains {@link WorldCanonicalFrozenTopology#STATUS}; this plan is not release,
 * Account authorization, execution, lifecycle, or admission proof. It allocates no runtime IDs,
 * persists nothing, and preserves the complete source binding and authored payloads.
 */
public final class WorldCanonicalInstanceTopologyPlan {
  /** One source-bound row with both exact input and the frozen storage projection. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable.")
  public record SourceRow(
      Template identity,
      WorldDesignMutationRevision authoredRevision,
      WorldDesignMutationRevision content,
      String inputPayload) {
    public SourceRow {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(authoredRevision, "authoredRevision");
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(inputPayload, "inputPayload");
    }

    /** Exact UTF-8 encoding of the immutable input payload retained by the source binding. */
    public byte[] inputPayloadBytes() {
      return inputPayload.getBytes(StandardCharsets.UTF_8);
    }
  }

  public sealed interface Entry
      permits Region, Zone, Room, RoomExit, GenerationRuleIntent, SpawnBindingIntent {
    SourceRow source();

    default Template identity() {
      return source().identity();
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable.")
  public record Region(SourceRow source, RegionDesignMutation content) implements Entry {
    public Region {
      requireFamily(source, Family.REGION);
      Objects.requireNonNull(content, "content");
      if (!source.content().hasRegion() || !source.content().getRegion().equals(content)) {
        throw invalid("REGION content differs from its frozen source row");
      }
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable.")
  public record Zone(SourceRow source, Template region, ZoneDesignMutation content)
      implements Entry {
    public Zone {
      requireFamily(source, Family.ZONE);
      requireFamily(region, Family.REGION);
      Objects.requireNonNull(content, "content");
      if (!source.content().hasZone() || !source.content().getZone().equals(content)) {
        throw invalid("ZONE content differs from its frozen source row");
      }
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable.")
  public record Room(SourceRow source, Template zone, RoomDesignMutation content) implements Entry {
    public Room {
      requireFamily(source, Family.ROOM);
      requireFamily(zone, Family.ZONE);
      Objects.requireNonNull(content, "content");
      if (!source.content().hasRoom() || !source.content().getRoom().equals(content)) {
        throw invalid("ROOM content differs from its frozen source row");
      }
    }
  }

  public record RoomExit(
      SourceRow source, Template fromRoom, Template toRoom, RoomExitDesignMutation content)
      implements Entry {
    public RoomExit {
      requireFamily(source, Family.ROOM_EXIT);
      requireFamily(fromRoom, Family.ROOM);
      requireFamily(toRoom, Family.ROOM);
      Objects.requireNonNull(content, "content");
      if (!source.content().hasRoomExit() || !source.content().getRoomExit().equals(content)) {
        throw invalid("ROOM_EXIT content differs from its frozen source row");
      }
    }
  }

  /** A configured rule is required intent; this entry carries no execution or skip outcome. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable.")
  public record GenerationRuleIntent(
      SourceRow source, Template scope, GenerationRuleDesignMutation requiredIntent)
      implements Entry {
    public GenerationRuleIntent {
      requireFamily(source, Family.GENERATION_RULE);
      requireScopeFamily(scope);
      Objects.requireNonNull(requiredIntent, "requiredIntent");
      if (!source.authoredRevision().hasGenerationRule()
          || !source.authoredRevision().getGenerationRule().equals(requiredIntent)) {
        throw invalid("GENERATION_RULE intent differs from its exact authored input");
      }
    }
  }

  /** A configured spawn is required intent and retains its canonical Entity UUID reference. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable.")
  public record SpawnBindingIntent(
      SourceRow source,
      Template room,
      EntityTemplateReference entityReference,
      WorldEntitySpawnBindingDesignMutation requiredIntent)
      implements Entry {
    public SpawnBindingIntent {
      requireFamily(source, Family.WORLD_ENTITY_SPAWN_BINDING);
      requireFamily(room, Family.ROOM);
      Objects.requireNonNull(entityReference, "entityReference");
      Objects.requireNonNull(requiredIntent, "requiredIntent");
      if (!source.authoredRevision().hasWorldEntitySpawnBinding()
          || !source.authoredRevision().getWorldEntitySpawnBinding().equals(requiredIntent)) {
        throw invalid("Spawn-binding intent differs from its exact authored input");
      }
    }
  }

  private final WorldCanonicalFrozenTopology source;
  private final List<Entry> entries;
  private final List<Region> regions;
  private final List<Zone> zones;
  private final List<Room> rooms;
  private final List<RoomExit> roomExits;
  private final List<GenerationRuleIntent> generationRules;
  private final List<SpawnBindingIntent> spawnBindings;

  private WorldCanonicalInstanceTopologyPlan(
      WorldCanonicalFrozenTopology source,
      List<Entry> entries,
      List<Region> regions,
      List<Zone> zones,
      List<Room> rooms,
      List<RoomExit> roomExits,
      List<GenerationRuleIntent> generationRules,
      List<SpawnBindingIntent> spawnBindings) {
    this.source = source;
    this.entries = List.copyOf(entries);
    this.regions = List.copyOf(regions);
    this.zones = List.copyOf(zones);
    this.rooms = List.copyOf(rooms);
    this.roomExits = List.copyOf(roomExits);
    this.generationRules = List.copyOf(generationRules);
    this.spawnBindings = List.copyOf(spawnBindings);
  }

  /**
   * Projects every row from the exact frozen graph, retaining its original binding and ordering.
   *
   * <p>Rows and parent references use family-qualified authored UUIDs. The source's private storage
   * keys are not used to derive instance identities or Entity references.
   */
  public static WorldCanonicalInstanceTopologyPlan create(WorldCanonicalFrozenTopology source) {
    Objects.requireNonNull(source, "source");
    if (!WorldCanonicalFrozenTopology.STATUS.equals(source.status())) {
      throw invalid("Source topology is not the unverified frozen component");
    }
    var request = source.request();
    var plan = request.plan();
    var graph = source.graph();
    var input = plan.graph();
    if (!graph.tenantId().equals(input.tenantId())
        || !graph.versionId().equals(input.versionId())
        || !graph.tenantId().equals(plan.binding().target().canonicalTenantId())
        || !graph.versionId().equals(plan.binding().target().canonicalVersionId())
        || !graph.tenantId().equals(request.freeze().canonicalTenantId())
        || !graph.versionId().equals(request.freeze().canonicalVersionId())) {
      throw invalid("Frozen graph differs from its complete canonical source binding");
    }

    Map<Template, WorldDraftTopologyInputGraph.Node> expected = new HashMap<>();
    for (var node : input.nodes()) {
      Template identity =
          new Template(family(node.mutation().getAggregateType()), node.templateId());
      if (expected.putIfAbsent(identity, node) != null) {
        throw invalid("Complete source repeats a family-qualified authored identity");
      }
    }
    if (expected.size() != graph.rows().size()) {
      throw invalid("Frozen topology omits or adds a complete source row");
    }

    Map<UUID, DraftCommitBinding.RevisionPayload> payloadByRevision = new HashMap<>();
    for (var revision : plan.binding().revisions()) {
      if (revision.owner() == Owner.WORLD_MANAGEMENT
          && payloadByRevision.putIfAbsent(revision.revisionId(), revision) != null) {
        throw invalid("Complete source repeats a World revision identity");
      }
    }

    List<Entry> entries = new ArrayList<>();
    List<Region> regions = new ArrayList<>();
    List<Zone> zones = new ArrayList<>();
    List<Room> rooms = new ArrayList<>();
    List<RoomExit> exits = new ArrayList<>();
    List<GenerationRuleIntent> generationRules = new ArrayList<>();
    List<SpawnBindingIntent> spawnBindings = new ArrayList<>();
    Map<Template, Entry> byIdentity = new HashMap<>();

    for (Row row : graph.rows()) {
      var authored = row.authored();
      Template identity =
          new Template(family(authored.mutation().getAggregateType()), row.template().templateId());
      if (!identity.equals(row.template())) {
        throw invalid("Frozen row identity confuses authored family or template ID");
      }
      var expectedNode = expected.remove(identity);
      if (expectedNode == null || !expectedNode.equals(authored)) {
        throw invalid("Frozen row is missing, duplicated, or differs from its exact input");
      }
      validateContentEnvelope(authored, row.content());
      var inputPayload = payloadByRevision.get(authored.revisionId());
      if (inputPayload == null) {
        throw invalid("Frozen row has no exact retained authored payload");
      }
      SourceRow sourceRow =
          new SourceRow(identity, authored.mutation(), row.content(), inputPayload.payload());
      Entry entry =
          project(sourceRow, authored.entityReference(), graph.tenantId(), graph.versionId());
      if (byIdentity.putIfAbsent(identity, entry) != null) {
        throw invalid("Frozen topology repeats a family-qualified row identity");
      }
      entries.add(entry);
      switch (entry) {
        case Region region -> regions.add(region);
        case Zone zone -> zones.add(zone);
        case Room room -> rooms.add(room);
        case RoomExit roomExit -> exits.add(roomExit);
        case GenerationRuleIntent generationRule -> generationRules.add(generationRule);
        case SpawnBindingIntent spawnBinding -> spawnBindings.add(spawnBinding);
      }
    }
    if (!expected.isEmpty()) {
      throw invalid("Frozen topology does not cover every typed source row");
    }

    validateClosure(entries, byIdentity);
    return new WorldCanonicalInstanceTopologyPlan(
        source, entries, regions, zones, rooms, exits, generationRules, spawnBindings);
  }

  /** The exact source request, full Draft binding, and freeze evidence retained by the capture. */
  public WorldCanonicalFrozenTopology.Request sourceBinding() {
    return source.request();
  }

  public UUID captureId() {
    return source.captureId();
  }

  public UUID tenantId() {
    return source.graph().tenantId();
  }

  public UUID versionId() {
    return source.graph().versionId();
  }

  public List<Entry> entries() {
    return entries;
  }

  public List<Region> regions() {
    return regions;
  }

  public List<Zone> zones() {
    return zones;
  }

  public List<Room> rooms() {
    return rooms;
  }

  public List<RoomExit> roomExits() {
    return roomExits;
  }

  public List<GenerationRuleIntent> generationRules() {
    return generationRules;
  }

  public List<SpawnBindingIntent> spawnBindings() {
    return spawnBindings;
  }

  private static Entry project(
      SourceRow source,
      EntityTemplateReference entityReference,
      UUID canonicalTenantId,
      UUID canonicalVersionId) {
    var authored = source.authoredRevision();
    var content = source.content();
    return switch (source.identity().family()) {
      case REGION -> new Region(source, content.getRegion());
      case ZONE ->
          new Zone(
              source,
              new Template(Family.REGION, uuid(authored.getZone().getRegionId())),
              content.getZone());
      case ROOM ->
          new Room(
              source,
              new Template(Family.ZONE, uuid(authored.getRoom().getZoneId())),
              content.getRoom());
      case ROOM_EXIT ->
          new RoomExit(
              source,
              new Template(Family.ROOM, uuid(authored.getRoomExit().getFromRoomId())),
              new Template(Family.ROOM, uuid(authored.getRoomExit().getToRoomId())),
              content.getRoomExit());
      case GENERATION_RULE ->
          new GenerationRuleIntent(source, declaredScope(authored), authored.getGenerationRule());
      case WORLD_ENTITY_SPAWN_BINDING -> {
        var spawn = authored.getWorldEntitySpawnBinding();
        if (entityReference == null
            || entityReference.kind() != spawn.getEntityTemplateType()
            || !entityReference.tenantId().equals(canonicalTenantId)
            || !entityReference.versionId().equals(canonicalVersionId)
            || !entityReference.templateId().equals(uuid(spawn.getEntityTemplateId()))) {
          throw invalid("Spawn intent differs from its exact typed canonical Entity reference");
        }
        yield new SpawnBindingIntent(
            source, new Template(Family.ROOM, uuid(spawn.getRoomId())), entityReference, spawn);
      }
    };
  }

  private static void validateClosure(List<Entry> entries, Map<Template, Entry> byIdentity) {
    Map<Template, Template> regionsByZone = new HashMap<>();
    Map<Template, Template> zonesByRoom = new HashMap<>();
    Set<List<String>> exitIdentities = new HashSet<>();
    Set<List<String>> generationRuleIdentities = new HashSet<>();
    Set<List<String>> spawnBindingIdentities = new HashSet<>();
    for (Entry entry : entries) {
      if (entry instanceof Zone zone) {
        requireEntry(byIdentity, zone.region(), Region.class, "zone parent region");
        regionsByZone.put(zone.identity(), zone.region());
      } else if (entry instanceof Room room) {
        requireEntry(byIdentity, room.zone(), Zone.class, "room parent zone");
        zonesByRoom.put(room.identity(), room.zone());
      }
    }

    for (Entry entry : entries) {
      Template scope = declaredScope(entry.source().authoredRevision());
      if (entry instanceof Region || entry instanceof Zone || entry instanceof Room) {
        requireWithinScope(scope, entry.identity(), regionsByZone, zonesByRoom);
      }
      if (entry instanceof RoomExit roomExit) {
        requireEntry(byIdentity, roomExit.fromRoom(), Room.class, "exit source room");
        requireEntry(byIdentity, roomExit.toRoom(), Room.class, "exit destination room");
        if (!exitIdentities.add(
            List.of(
                roomExit.fromRoom().templateId().toString(), roomExit.content().getDirection()))) {
          throw invalid("Frozen topology repeats a room/direction exit");
        }
        requireWithinScope(scope, roomExit.fromRoom(), regionsByZone, zonesByRoom);
        requireWithinScope(scope, roomExit.toRoom(), regionsByZone, zonesByRoom);
      } else if (entry instanceof GenerationRuleIntent rule) {
        requireEntry(
            byIdentity,
            rule.scope(),
            scope.family() == Family.REGION ? Region.class : Zone.class,
            "generation-rule scope");
        if (!scope.equals(rule.scope())) {
          throw invalid("Generation-rule scope differs from its authored scope");
        }
        if (!generationRuleIdentities.add(
            List.of(
                rule.scope().family().name(),
                rule.scope().templateId().toString(),
                rule.requiredIntent().getName()))) {
          throw invalid("Frozen topology repeats a scoped generation rule");
        }
      } else if (entry instanceof SpawnBindingIntent spawn) {
        requireEntry(byIdentity, spawn.room(), Room.class, "spawn-binding room");
        if (!spawnBindingIdentities.add(
            List.of(
                spawn.room().templateId().toString(),
                spawn.entityReference().kind().name(),
                spawn.entityReference().templateId().toString()))) {
          throw invalid("Frozen topology repeats a typed room/entity spawn binding");
        }
        requireWithinScope(scope, spawn.room(), regionsByZone, zonesByRoom);
      }
    }
  }

  private static void requireWithinScope(
      Template scope,
      Template child,
      Map<Template, Template> regionsByZone,
      Map<Template, Template> zonesByRoom) {
    boolean contained;
    if (scope.family() == Family.REGION) {
      Template region = regionFor(child, regionsByZone, zonesByRoom);
      contained = scope.equals(region);
    } else if (scope.family() == Family.ZONE) {
      Template zone = zoneFor(child, zonesByRoom);
      contained = scope.equals(zone);
    } else {
      throw invalid("World subtree scope must be a REGION or ZONE");
    }
    if (!contained) {
      throw invalid("Authored row or exit endpoint falls outside its declared subtree scope");
    }
  }

  private static Template regionFor(
      Template child, Map<Template, Template> regionsByZone, Map<Template, Template> zonesByRoom) {
    if (child.family() == Family.REGION) {
      return child;
    }
    Template zone = child.family() == Family.ZONE ? child : zonesByRoom.get(child);
    Template region = zone == null ? null : regionsByZone.get(zone);
    if (region == null) {
      throw invalid("Authored parent chain is incomplete at its REGION");
    }
    return region;
  }

  private static Template zoneFor(Template child, Map<Template, Template> zonesByRoom) {
    if (child.family() == Family.ZONE) {
      return child;
    }
    Template zone = zonesByRoom.get(child);
    if (zone == null) {
      throw invalid("Authored parent chain is incomplete at its ZONE");
    }
    return zone;
  }

  private static void requireEntry(
      Map<Template, Entry> entries,
      Template identity,
      Class<? extends Entry> expectedType,
      String relationship) {
    Entry found = entries.get(identity);
    if (found == null || !expectedType.isInstance(found)) {
      throw invalid("Missing or type-confused " + relationship);
    }
  }

  private static void validateContentEnvelope(
      WorldDraftTopologyInputGraph.Node authored, WorldDesignMutationRevision content) {
    var mutation = authored.mutation();
    var expected = mutation.toBuilder();
    switch (mutation.getAggregateType()) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> {
        if (mutation.getRegion().getSpacingMultiplier() == 0) {
          expected.setRegion(mutation.getRegion().toBuilder().setSpacingMultiplier(1.0));
        }
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> {
        if (mutation.getRoomExit().getCost() == 0) {
          expected.setRoomExit(mutation.getRoomExit().toBuilder().setCost(1));
        }
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING -> {
        if (mutation.getWorldEntitySpawnBinding().getSpawnCount() == 0) {
          expected.setWorldEntitySpawnBinding(
              mutation.getWorldEntitySpawnBinding().toBuilder().setSpawnCount(1));
        }
      }
      default -> {}
    }
    if (!content.equals(expected.build())) {
      throw invalid("Frozen content differs from its exact authored/default projection");
    }
  }

  private static Template declaredScope(WorldDesignMutationRevision revision) {
    return switch (revision.getScopeType()) {
      case WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE ->
          new Template(Family.REGION, uuid(revision.getScopeId()));
      case WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE ->
          new Template(Family.ZONE, uuid(revision.getScopeId()));
      default -> throw invalid("Unsupported World topology scope");
    };
  }

  private static Family family(WorldDesignAggregateType aggregate) {
    return switch (aggregate) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> Family.REGION;
      case WORLD_DESIGN_AGGREGATE_TYPE_ZONE -> Family.ZONE;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM -> Family.ROOM;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> Family.ROOM_EXIT;
      case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> Family.GENERATION_RULE;
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING ->
          Family.WORLD_ENTITY_SPAWN_BINDING;
      default -> throw invalid("Unsupported topology family");
    };
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) {
        throw invalid("Canonical topology references require non-nil UUIDs");
      }
      return id;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Canonical topology references require non-nil UUIDs", exception);
    }
  }

  private static void requireScopeFamily(Template scope) {
    Objects.requireNonNull(scope, "scope");
    if (scope.family() != Family.REGION && scope.family() != Family.ZONE) {
      throw invalid("World subtree scope must be a REGION or ZONE");
    }
  }

  private static void requireFamily(SourceRow source, Family expected) {
    Objects.requireNonNull(source, "source");
    requireFamily(source.identity(), expected);
  }

  private static void requireFamily(Template identity, Family expected) {
    Objects.requireNonNull(identity, "identity");
    if (identity.family() != expected) {
      throw invalid("Expected " + expected + " family-qualified identity");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
