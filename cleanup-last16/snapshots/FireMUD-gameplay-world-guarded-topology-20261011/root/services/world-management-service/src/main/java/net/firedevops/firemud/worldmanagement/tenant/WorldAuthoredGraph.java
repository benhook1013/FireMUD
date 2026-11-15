package net.firedevops.firemud.worldmanagement.tenant;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Immutable World-owned value and codec for the six version-scoped authored content families. */
public final class WorldAuthoredGraph {
  private static final int SNAPSHOT_SCHEMA_VERSION = 1;
  private static final ObjectMapper STRICT_JSON_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

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

  /**
   * Decodes only the exact canonical representation emitted by {@link #encode(ObjectMapper)}. This
   * validates stored representation, not graph relationships or publication eligibility.
   */
  static WorldAuthoredGraph decode(byte[] encoded, ObjectMapper objectMapper) {
    if (encoded == null || objectMapper == null) {
      throw malformed("World authored graph bytes or mapper are missing");
    }
    byte[] original = encoded.clone();
    String json;
    try {
      json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(original))
              .toString();
    } catch (CharacterCodingException exception) {
      throw malformed("World authored graph bytes are not valid UTF-8", exception);
    }

    JsonNode root;
    try {
      root = STRICT_JSON_MAPPER.readTree(json);
    } catch (JacksonException exception) {
      throw malformed("World authored graph bytes are not strict JSON", exception);
    }
    requireObject(root, "root");
    requireFields(
        root,
        "root",
        "snapshotSchemaVersion",
        "regions",
        "zones",
        "rooms",
        "roomExits",
        "generationRules",
        "worldEntitySpawnBindings");
    JsonNode schemaVersion = root.get("snapshotSchemaVersion");
    if (!schemaVersion.isInt() || schemaVersion.intValue() != SNAPSHOT_SCHEMA_VERSION) {
      throw malformed("World authored graph snapshot schema version is unsupported");
    }

    WorldAuthoredGraph graph =
        new WorldAuthoredGraph(
            decodeRows(root.get("regions"), "regions", WorldAuthoredGraph::decodeRegion),
            decodeRows(root.get("zones"), "zones", WorldAuthoredGraph::decodeZone),
            decodeRows(root.get("rooms"), "rooms", WorldAuthoredGraph::decodeRoom),
            decodeRows(root.get("roomExits"), "roomExits", WorldAuthoredGraph::decodeRoomExit),
            decodeRows(
                root.get("generationRules"),
                "generationRules",
                WorldAuthoredGraph::decodeGenerationRule),
            decodeRows(
                root.get("worldEntitySpawnBindings"),
                "worldEntitySpawnBindings",
                WorldAuthoredGraph::decodeSpawnBinding));
    if (!Arrays.equals(graph.encode(objectMapper), original)) {
      throw malformed("World authored graph bytes are not in canonical encoding");
    }
    return graph;
  }

  private static List<Map<String, ?>> decodeRows(
      JsonNode rows, String family, Function<JsonNode, LinkedHashMap<String, Object>> decoder) {
    if (rows == null || !rows.isArray()) {
      throw malformed("World authored graph family " + family + " is not an array");
    }
    List<Map<String, ?>> decodedRows = new ArrayList<>(rows.size());
    for (JsonNode row : rows) {
      decodedRows.add(decoder.apply(row));
    }
    return decodedRows;
  }

  private static LinkedHashMap<String, Object> decodeRegion(JsonNode row) {
    requireFields(
        row,
        "regions row",
        "id",
        "shardId",
        "name",
        "weather",
        "generationSeed",
        "generatorType",
        "generatorParams",
        "spacingMultiplier");
    LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
    decoded.put("id", positiveLong(row, "id", "regions row"));
    decoded.put("shardId", nonNegativeInt(row, "shardId", "regions row"));
    decoded.put("name", nonBlankString(row, "name", "regions row"));
    decoded.put("weather", nullableString(row, "weather", "regions row"));
    decoded.put("generationSeed", longValue(row, "generationSeed", "regions row"));
    decoded.put("generatorType", nullableString(row, "generatorType", "regions row"));
    decoded.put("generatorParams", nullableString(row, "generatorParams", "regions row"));
    JsonNode spacing = row.get("spacingMultiplier");
    if (spacing == null || !spacing.isFloatingPointNumber()) {
      throw malformed("World authored graph regions row spacingMultiplier is not a Double");
    }
    double spacingValue = spacing.doubleValue();
    if (!Double.isFinite(spacingValue) || spacingValue <= 0.0d) {
      throw malformed("World authored graph regions row spacingMultiplier is unsupported");
    }
    decoded.put("spacingMultiplier", spacingValue);
    return decoded;
  }

  private static LinkedHashMap<String, Object> decodeZone(JsonNode row) {
    requireFields(row, "zones row", "id", "regionId", "name");
    LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
    decoded.put("id", positiveLong(row, "id", "zones row"));
    decoded.put("regionId", positiveLong(row, "regionId", "zones row"));
    decoded.put("name", nonBlankString(row, "name", "zones row"));
    return decoded;
  }

  private static LinkedHashMap<String, Object> decodeRoom(JsonNode row) {
    requireFields(
        row,
        "rooms row",
        "id",
        "zoneId",
        "name",
        "description",
        "nameLocalizedVariantsJson",
        "descriptionLocalizedVariantsJson");
    LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
    decoded.put("id", positiveLong(row, "id", "rooms row"));
    decoded.put("zoneId", positiveLong(row, "zoneId", "rooms row"));
    decoded.put("name", nonBlankString(row, "name", "rooms row"));
    decoded.put("description", nullableString(row, "description", "rooms row"));
    decoded.put(
        "nameLocalizedVariantsJson", nullableString(row, "nameLocalizedVariantsJson", "rooms row"));
    decoded.put(
        "descriptionLocalizedVariantsJson",
        nullableString(row, "descriptionLocalizedVariantsJson", "rooms row"));
    return decoded;
  }

  private static LinkedHashMap<String, Object> decodeRoomExit(JsonNode row) {
    requireFields(row, "roomExits row", "id", "fromRoomId", "toRoomId", "direction", "cost");
    LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
    decoded.put("id", positiveLong(row, "id", "roomExits row"));
    decoded.put("fromRoomId", positiveLong(row, "fromRoomId", "roomExits row"));
    decoded.put("toRoomId", positiveLong(row, "toRoomId", "roomExits row"));
    decoded.put("direction", nonBlankString(row, "direction", "roomExits row"));
    decoded.put("cost", positiveInt(row, "cost", "roomExits row"));
    return decoded;
  }

  private static LinkedHashMap<String, Object> decodeGenerationRule(JsonNode row) {
    requireFields(row, "generationRules row", "id", "name", "scopeType", "scopeId", "value");
    LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
    decoded.put("id", positiveLong(row, "id", "generationRules row"));
    decoded.put("name", nonBlankString(row, "name", "generationRules row"));
    String scopeType = nullableString(row, "scopeType", "generationRules row");
    String scopeId = nullableString(row, "scopeId", "generationRules row");
    if ((scopeType == null) != (scopeId == null)) {
      throw malformed("World authored graph generationRules row has a partial scope");
    }
    if (scopeType != null
        && (!scopeType.equals("REGION_SUBTREE") && !scopeType.equals("ZONE_SUBTREE"))) {
      throw malformed("World authored graph generationRules row scopeType is unsupported");
    }
    if (scopeId != null) {
      try {
        long parsedScopeId = Long.parseLong(scopeId);
        if (parsedScopeId <= 0L || !Long.toString(parsedScopeId).equals(scopeId)) {
          throw malformed("World authored graph generationRules row scopeId is noncanonical");
        }
      } catch (NumberFormatException exception) {
        throw malformed(
            "World authored graph generationRules row scopeId is unsupported", exception);
      }
    }
    decoded.put("scopeType", scopeType);
    decoded.put("scopeId", scopeId);
    decoded.put("value", nullableString(row, "value", "generationRules row"));
    return decoded;
  }

  private static LinkedHashMap<String, Object> decodeSpawnBinding(JsonNode row) {
    requireFields(
        row,
        "worldEntitySpawnBindings row",
        "id",
        "roomId",
        "entityTemplateType",
        "entityTemplateId",
        "spawnCount",
        "respawnDelaySeconds");
    LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
    decoded.put("id", positiveLong(row, "id", "worldEntitySpawnBindings row"));
    decoded.put("roomId", positiveLong(row, "roomId", "worldEntitySpawnBindings row"));
    String entityType = nonBlankString(row, "entityTemplateType", "worldEntitySpawnBindings row");
    if (!entityType.equals("ITEM") && !entityType.equals("NPC")) {
      throw malformed("World authored graph worldEntitySpawnBindings row type is unsupported");
    }
    decoded.put("entityTemplateType", entityType);
    decoded.put(
        "entityTemplateId", positiveLong(row, "entityTemplateId", "worldEntitySpawnBindings row"));
    decoded.put("spawnCount", positiveInt(row, "spawnCount", "worldEntitySpawnBindings row"));
    decoded.put(
        "respawnDelaySeconds",
        nonNegativeInt(row, "respawnDelaySeconds", "worldEntitySpawnBindings row"));
    return decoded;
  }

  private static void requireFields(JsonNode object, String label, String... fields) {
    requireObject(object, label);
    Set<String> expected = Set.of(fields);
    if (object.size() != expected.size()) {
      throw malformed("World authored graph " + label + " has missing or extra fields");
    }
    for (Map.Entry<String, JsonNode> entry : object.properties()) {
      if (!expected.contains(entry.getKey())) {
        throw malformed("World authored graph " + label + " has an unknown field");
      }
    }
    for (String field : fields) {
      if (object.get(field) == null) {
        throw malformed("World authored graph " + label + " is missing field " + field);
      }
    }
  }

  private static void requireObject(JsonNode node, String label) {
    if (node == null || !node.isObject()) {
      throw malformed("World authored graph " + label + " is not an object");
    }
  }

  private static long positiveLong(JsonNode object, String field, String label) {
    long value = longValue(object, field, label);
    if (value <= 0L) {
      throw malformed("World authored graph " + label + " " + field + " is not positive");
    }
    return value;
  }

  private static long longValue(JsonNode object, String field, String label) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw malformed("World authored graph " + label + " " + field + " is not a Long");
    }
    return value.longValue();
  }

  private static int positiveInt(JsonNode object, String field, String label) {
    int value = intValue(object, field, label);
    if (value <= 0) {
      throw malformed("World authored graph " + label + " " + field + " is not positive");
    }
    return value;
  }

  private static int nonNegativeInt(JsonNode object, String field, String label) {
    int value = intValue(object, field, label);
    if (value < 0) {
      throw malformed("World authored graph " + label + " " + field + " is negative");
    }
    return value;
  }

  private static int intValue(JsonNode object, String field, String label) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw malformed("World authored graph " + label + " " + field + " is not an Integer");
    }
    return value.intValue();
  }

  private static String nonBlankString(JsonNode object, String field, String label) {
    String value = stringValue(object, field, label, false);
    if (value.isBlank()) {
      throw malformed("World authored graph " + label + " " + field + " is blank");
    }
    return value;
  }

  private static String nullableString(JsonNode object, String field, String label) {
    return stringValue(object, field, label, true);
  }

  private static String stringValue(JsonNode object, String field, String label, boolean nullable) {
    JsonNode value = object.get(field);
    if (value == null) {
      throw malformed("World authored graph " + label + " is missing field " + field);
    }
    if (value.isNull() && nullable) {
      return null;
    }
    if (!value.isString()) {
      throw malformed("World authored graph " + label + " " + field + " is not a string");
    }
    return value.stringValue();
  }

  private static SnapshotConflictException malformed(String message) {
    return new SnapshotConflictException(message);
  }

  private static SnapshotConflictException malformed(String message, Exception cause) {
    SnapshotConflictException failure = new SnapshotConflictException(message);
    failure.initCause(cause);
    return failure;
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
