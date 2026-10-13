package net.firedevops.firemud.worldmanagement.tenant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Shared internal six-family graph reader; callers must hold their exact owner transaction lock.
 */
final class WorldAuthoredGraphReader {
  private static final Table<?> REGION = table("region");
  private static final Table<?> ZONE = table("zone");
  private static final Table<?> ROOM = table("room");
  private static final Table<?> ROOM_EXIT = table("room_exit");
  private static final Table<?> GENERATION_RULE = table("generation_rule");
  private static final Table<?> SPAWN_BINDING = table("world_entity_spawn_binding");

  private static final Field<Long> ID = field("id", Long.class);
  private static final Field<Long> TENANT_ID = field("tenant_id", Long.class);
  private static final Field<Long> VERSION_ID = field("version_id", Long.class);
  private static final Field<Long> REGION_ID = field("region_id", Long.class);
  private static final Field<Long> ZONE_ID = field("zone_id", Long.class);
  private static final Field<Long> FROM_ROOM_ID = field("from_room_id", Long.class);
  private static final Field<Long> TO_ROOM_ID = field("to_room_id", Long.class);
  private static final Field<Long> ROOM_ID = field("room_id", Long.class);
  private static final Field<Long> GENERATION_SEED = field("generation_seed", Long.class);
  private static final Field<Integer> SHARD_ID = field("shard_id", Integer.class);
  private static final Field<String> NAME = field("name", String.class);
  private static final Field<String> WEATHER = field("weather", String.class);
  private static final Field<String> GENERATOR_TYPE = field("generator_type", String.class);
  private static final Field<String> GENERATOR_PARAMS = field("generator_params", String.class);
  private static final Field<Double> SPACING_MULTIPLIER = field("spacing_multiplier", Double.class);
  private static final Field<String> DESCRIPTION = field("description", String.class);
  private static final Field<String> NAME_LOCALIZED_VARIANTS_JSON =
      field("name_localized_variants_json", String.class);
  private static final Field<String> DESCRIPTION_LOCALIZED_VARIANTS_JSON =
      field("description_localized_variants_json", String.class);
  private static final Field<String> DIRECTION = field("direction", String.class);
  private static final Field<Integer> COST = field("cost", Integer.class);
  private static final Field<String> SCOPE_TYPE = field("scope_type", String.class);
  private static final Field<String> SCOPE_ID = field("scope_id", String.class);
  private static final Field<String> RULE_VALUE = field("value", String.class);
  private static final Field<String> ENTITY_TEMPLATE_TYPE =
      field("entity_template_type", String.class);
  private static final Field<Long> ENTITY_TEMPLATE_ID = field("entity_template_id", Long.class);
  private static final Field<Integer> SPAWN_COUNT = field("spawn_count", Integer.class);
  private static final Field<Integer> RESPAWN_DELAY_SECONDS =
      field("respawn_delay_seconds", Integer.class);

  private final DSLContext dsl;

  WorldAuthoredGraphReader(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  WorldAuthoredGraph readAndValidateGraph(long tenantKey, long versionKey) {
    List<Record> regionRows = fetch(REGION, tenantKey, versionKey);
    List<Record> zoneRows = fetch(ZONE, tenantKey, versionKey);
    List<Record> roomRows = fetch(ROOM, tenantKey, versionKey);
    List<Record> exitRows = fetch(ROOM_EXIT, tenantKey, versionKey);
    List<Record> ruleRows = fetch(GENERATION_RULE, tenantKey, versionKey);
    List<Record> bindingRows = fetch(SPAWN_BINDING, tenantKey, versionKey);

    Map<Long, Boolean> regionIds = new HashMap<>();
    Map<Long, Boolean> zoneIds = new HashMap<>();
    Map<Long, Boolean> roomIds = new HashMap<>();
    List<LinkedHashMap<String, Object>> regions = new ArrayList<>();
    List<LinkedHashMap<String, Object>> zones = new ArrayList<>();
    List<LinkedHashMap<String, Object>> rooms = new ArrayList<>();
    List<LinkedHashMap<String, Object>> exits = new ArrayList<>();
    List<LinkedHashMap<String, Object>> rules = new ArrayList<>();
    List<LinkedHashMap<String, Object>> bindings = new ArrayList<>();

    for (Record row : regionRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "region.id");
      unique(regionIds, id, "region");
      Integer shard = required(row, SHARD_ID);
      Long seed = required(row, GENERATION_SEED);
      Double spacing = required(row, SPACING_MULTIPLIER);
      String name = required(row, NAME);
      if (shard < 0 || !Double.isFinite(spacing) || spacing <= 0.0d || name.isBlank()) {
        throw incomplete("region", id, "unsupported required region data");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("shardId", shard);
      item.put("name", name);
      item.put("weather", row.get(WEATHER, String.class));
      item.put("generationSeed", seed);
      item.put("generatorType", row.get(GENERATOR_TYPE, String.class));
      item.put("generatorParams", row.get(GENERATOR_PARAMS, String.class));
      item.put("spacingMultiplier", spacing);
      regions.add(item);
    }

    for (Record row : zoneRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "zone.id");
      unique(zoneIds, id, "zone");
      long regionId = positive(row, REGION_ID, "zone.region_id");
      if (!regionIds.containsKey(regionId)) {
        throw incomplete(
            "zone", id, "region parent is missing or belongs to another tenant/Version");
      }
      String name = required(row, NAME);
      if (name.isBlank()) {
        throw incomplete("zone", id, "zone name is blank");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("regionId", regionId);
      item.put("name", name);
      zones.add(item);
    }

    for (Record row : roomRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "room.id");
      unique(roomIds, id, "room");
      long zoneId = positive(row, ZONE_ID, "room.zone_id");
      if (!zoneIds.containsKey(zoneId)) {
        throw incomplete("room", id, "zone parent is missing or belongs to another tenant/Version");
      }
      String name = required(row, NAME);
      if (name.isBlank()) {
        throw incomplete("room", id, "room name is blank");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("zoneId", zoneId);
      item.put("name", name);
      item.put("description", row.get(DESCRIPTION, String.class));
      item.put("nameLocalizedVariantsJson", row.get(NAME_LOCALIZED_VARIANTS_JSON, String.class));
      item.put(
          "descriptionLocalizedVariantsJson",
          row.get(DESCRIPTION_LOCALIZED_VARIANTS_JSON, String.class));
      rooms.add(item);
    }

    for (Record row : exitRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "room_exit.id");
      long from = positive(row, FROM_ROOM_ID, "room_exit.from_room_id");
      long to = positive(row, TO_ROOM_ID, "room_exit.to_room_id");
      if (!roomIds.containsKey(from) || !roomIds.containsKey(to)) {
        throw incomplete(
            "room_exit", id, "exit endpoint is missing or belongs to another tenant/Version");
      }
      String direction = required(row, DIRECTION);
      Integer cost = required(row, COST);
      if (direction.isBlank() || cost <= 0) {
        throw incomplete("room_exit", id, "unsupported required room-exit data");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("fromRoomId", from);
      item.put("toRoomId", to);
      item.put("direction", direction);
      item.put("cost", cost);
      exits.add(item);
    }

    Set<String> exactRuleKeys = new HashSet<>();
    Set<String> unscopedRuleNames = new HashSet<>();
    for (Record row : ruleRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "generation_rule.id");
      String name = required(row, NAME);
      if (name.isBlank()) {
        throw incomplete("generation_rule", id, "generation rule name is blank");
      }
      String scopeType = row.get(SCOPE_TYPE, String.class);
      String scopeId = row.get(SCOPE_ID, String.class);
      if ((scopeType == null) != (scopeId == null)) {
        throw incomplete(
            "generation_rule", id, "scope_type and scope_id are only partially present");
      }
      String stableScope;
      if (scopeType == null) {
        if (!unscopedRuleNames.add(name)) {
          throw incomplete(
              "generation_rule", id, "duplicate ambiguous unscoped generation rule name");
        }
        stableScope = "";
      } else {
        if (!"REGION_SUBTREE".equals(scopeType) && !"ZONE_SUBTREE".equals(scopeType)) {
          throw incomplete("generation_rule", id, "unsupported generation rule scope type");
        }
        long parsedScopeId = parseCanonicalPositiveId(scopeId, "generation_rule.scope_id", id);
        if (("REGION_SUBTREE".equals(scopeType) && !regionIds.containsKey(parsedScopeId))
            || ("ZONE_SUBTREE".equals(scopeType) && !zoneIds.containsKey(parsedScopeId))) {
          throw incomplete(
              "generation_rule", id, "generation rule scope parent is missing or mismatched");
        }
        stableScope = scopeType + ":" + scopeId;
      }
      if (!exactRuleKeys.add(stableScope + "\u0000" + name)) {
        throw incomplete("generation_rule", id, "duplicate generation rule identity");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("name", name);
      item.put("scopeType", scopeType);
      item.put("scopeId", scopeId);
      item.put("value", row.get(RULE_VALUE, String.class));
      rules.add(item);
    }

    Set<String> spawnKeys = new HashSet<>();
    for (Record row : bindingRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "world_entity_spawn_binding.id");
      long roomId = positive(row, ROOM_ID, "world_entity_spawn_binding.room_id");
      if (!roomIds.containsKey(roomId)) {
        throw incomplete(
            "world_entity_spawn_binding",
            id,
            "spawn room is missing or belongs to another tenant/Version");
      }
      String entityType = required(row, ENTITY_TEMPLATE_TYPE);
      Long entityId = required(row, ENTITY_TEMPLATE_ID);
      Integer spawnCount = required(row, SPAWN_COUNT);
      Integer delay = required(row, RESPAWN_DELAY_SECONDS);
      if ((!"ITEM".equals(entityType) && !"NPC".equals(entityType))
          || entityId <= 0L
          || spawnCount <= 0
          || delay < 0) {
        throw incomplete(
            "world_entity_spawn_binding", id, "unsupported required spawn binding data");
      }
      String spawnKey = roomId + "\u0000" + entityType + "\u0000" + entityId;
      if (!spawnKeys.add(spawnKey)) {
        throw incomplete("world_entity_spawn_binding", id, "duplicate spawn binding identity");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("roomId", roomId);
      item.put("entityTemplateType", entityType);
      item.put("entityTemplateId", entityId);
      item.put("spawnCount", spawnCount);
      item.put("respawnDelaySeconds", delay);
      bindings.add(item);
    }
    return new WorldAuthoredGraph(regions, zones, rooms, exits, rules, bindings);
  }

  private List<Record> fetch(Table<?> table, long tenantKey, long versionKey) {
    List<? extends Record> fetchedRows =
        dsl.selectFrom(table)
            .where(TENANT_ID.eq(tenantKey).and(VERSION_ID.eq(versionKey)))
            .orderBy(ID.asc())
            .fetch();
    List<Record> rows = new ArrayList<>(fetchedRows.size());
    rows.addAll(fetchedRows);
    return rows;
  }

  private void requireExactScope(Record row, long tenantKey, long versionKey) {
    if (!Objects.equals(row.get(TENANT_ID, Long.class), tenantKey)
        || !Objects.equals(row.get(VERSION_ID, Long.class), versionKey)) {
      throw new SnapshotConflictException(
          "World graph row returned outside the exact V27 tenant/Version scope");
    }
  }

  private long positive(Record row, Field<Long> field, String label) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw new SnapshotConflictException(label + " is missing or non-positive");
    }
    return value;
  }

  private long parseCanonicalPositiveId(String value, String label, long rowId) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new NumberFormatException("not canonical positive decimal");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw incomplete("generation_rule", rowId, label + " is unsupported");
    }
  }

  private <T> T required(Record row, Field<T> field) {
    T value = row.get(field, field.getType());
    if (value == null) {
      throw new SnapshotConflictException(
          "World graph contains null required field " + field.getName());
    }
    return value;
  }

  private SnapshotConflictException incomplete(String family, long id, String reason) {
    return new SnapshotConflictException(
        "World graph capture denied incomplete " + family + " row " + id + ": " + reason);
  }

  private void unique(Map<Long, Boolean> ids, long id, String family) {
    if (ids.putIfAbsent(id, Boolean.TRUE) != null) {
      throw incomplete(family, id, "duplicate row identity");
    }
  }

  private static Table<?> table(String name) {
    return DSL.table(DSL.name(name));
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }
}
