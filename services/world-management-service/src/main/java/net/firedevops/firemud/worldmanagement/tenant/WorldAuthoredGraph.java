package net.firedevops.firemud.worldmanagement.tenant;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import tools.jackson.databind.ObjectMapper;

/** Immutable World-owned value and codec for the six version-scoped authored content families. */
public final class WorldAuthoredGraph {
  private static final int SNAPSHOT_SCHEMA_VERSION = 1;

  private final List<Map<String, Object>> regions;
  private final List<Map<String, Object>> zones;
  private final List<Map<String, Object>> rooms;
  private final List<Map<String, Object>> roomExits;
  private final List<Map<String, Object>> generationRules;
  private final List<Map<String, Object>> spawnBindings;

  public WorldAuthoredGraph(
      List<? extends Map<String, ?>> regions,
      List<? extends Map<String, ?>> zones,
      List<? extends Map<String, ?>> rooms,
      List<? extends Map<String, ?>> roomExits,
      List<? extends Map<String, ?>> generationRules,
      List<? extends Map<String, ?>> spawnBindings) {
    this.regions = copyRows(regions, false);
    this.zones = copyRows(zones, false);
    this.rooms = copyRows(rooms, false);
    this.roomExits = copyRows(roomExits, false);
    this.generationRules = copyRows(generationRules, false);
    this.spawnBindings = copyRows(spawnBindings, false);
  }

  public List<Map<String, Object>> regions() {
    return copyRows(regions, true);
  }

  public List<Map<String, Object>> zones() {
    return copyRows(zones, true);
  }

  public List<Map<String, Object>> rooms() {
    return copyRows(rooms, true);
  }

  public List<Map<String, Object>> roomExits() {
    return copyRows(roomExits, true);
  }

  public List<Map<String, Object>> generationRules() {
    return copyRows(generationRules, true);
  }

  public List<Map<String, Object>> spawnBindings() {
    return copyRows(spawnBindings, true);
  }

  /**
   * Encodes the canonical snapshot representation using the capture component's configured mapper.
   */
  public byte[] encode(ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("snapshotSchemaVersion", SNAPSHOT_SCHEMA_VERSION);
    root.put("regions", regions);
    root.put("zones", zones);
    root.put("rooms", rooms);
    root.put("roomExits", roomExits);
    root.put("generationRules", generationRules);
    root.put("worldEntitySpawnBindings", spawnBindings);
    try {
      return objectMapper.writeValueAsBytes(root);
    } catch (Exception exception) {
      throw new SnapshotConflictException("World authored graph bytes could not be encoded");
    }
  }

  WorldAuthoredGraph withRegions(List<? extends Map<String, ?>> updatedRegions) {
    return new WorldAuthoredGraph(
        updatedRegions, zones, rooms, roomExits, generationRules, spawnBindings);
  }

  private static List<Map<String, Object>> copyRows(
      List<? extends Map<String, ?>> rows, boolean immutable) {
    Objects.requireNonNull(rows, "rows");
    List<Map<String, Object>> copiedRows = new ArrayList<>(rows.size());
    for (Map<String, ?> row : rows) {
      Objects.requireNonNull(row, "row");
      LinkedHashMap<String, Object> copiedRow = new LinkedHashMap<>();
      for (Map.Entry<String, ?> entry : row.entrySet()) {
        copiedRow.put(
            Objects.requireNonNull(entry.getKey(), "row key"),
            copyValue(entry.getValue(), immutable));
      }
      copiedRows.add(immutable ? Collections.unmodifiableMap(copiedRow) : copiedRow);
    }
    return immutable ? Collections.unmodifiableList(copiedRows) : copiedRows;
  }

  private static Object copyValue(Object value, boolean immutable) {
    if (value == null
        || value instanceof String
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>) {
      return value;
    }
    if (value instanceof byte[] bytes) {
      return bytes.clone();
    }
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copiedMap = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("Authored graph object keys must be strings");
        }
        copiedMap.put(key, copyValue(entry.getValue(), immutable));
      }
      return immutable ? Collections.unmodifiableMap(copiedMap) : copiedMap;
    }
    if (value instanceof List<?> list) {
      List<Object> copiedList = new ArrayList<>(list.size());
      for (Object item : list) {
        copiedList.add(copyValue(item, immutable));
      }
      return immutable ? Collections.unmodifiableList(copiedList) : copiedList;
    }
    throw new IllegalArgumentException(
        "Unsupported mutable value in authored graph: " + value.getClass().getName());
  }
}
