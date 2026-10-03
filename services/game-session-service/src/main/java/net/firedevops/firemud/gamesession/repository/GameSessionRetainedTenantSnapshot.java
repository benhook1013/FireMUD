package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Closed, immutable evidence for Game Session's retained-tenant identity projection. */
public record GameSessionRetainedTenantSnapshot(
    String targetNamespace,
    String legacyGameSessionTenantId,
    String canonicalJson,
    String evidenceDigest) {
  private static final String DIGEST_DOMAIN = "game-session/retained-tenant-identity-snapshot/v1";
  private static final Pattern CANONICAL_DECIMAL =
      Pattern.compile("(?:0|[1-9][0-9]*|-[1-9][0-9]*)");
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "schemaVersion",
          "targetNamespace",
          "legacyGameSessionTenantId",
          "instances",
          "pointers",
          "sharedNamespaces",
          "backfillIssues",
          "pointerEvents",
          "preparedUpgrades");
  private static final Set<String> INSTANCE_FIELDS =
      Set.of(
          "id",
          "tenant_id",
          "runtime_version",
          "script_patch_version",
          "owner_account_id",
          "status",
          "row_version",
          "game_template_id",
          "launch_descriptor_id",
          "version_id",
          "release_bundle_id",
          "version_state_epoch",
          "generation_config_revision",
          "remap_set_id",
          "script_patch_pinned_control_plane_request_id",
          "script_pin_epoch");
  private static final Set<String> POINTER_FIELDS =
      Set.of(
          "id",
          "tenant_id",
          "game_instance_id",
          "world_slug",
          "realm_slug",
          "pointer_version",
          "catalog_revision",
          "visible",
          "requires_character_selection",
          "state_scope",
          "character_creation_policy",
          "public_production_realm",
          "realm_id",
          "playable_state_namespace_id");
  private static final Set<String> SHARED_NAMESPACE_FIELDS =
      Set.of("tenant_id", "playable_state_namespace_id");
  private static final Set<String> POINTER_EVENT_FIELDS =
      Set.of(
          "id",
          "tenant_id",
          "game_instance_id",
          "world_slug",
          "realm_slug",
          "pointer_version",
          "visible",
          "requires_character_selection",
          "state_scope",
          "character_creation_policy",
          "control_plane_request_id",
          "prepared_version_upgrade_id",
          "public_production_realm");
  private static final Set<String> PREPARED_UPGRADE_FIELDS =
      Set.of(
          "id",
          "tenant_id",
          "preparation_id",
          "control_plane_request_id",
          "source_game_instance_id",
          "source_version_id",
          "target_version_id",
          "target_launch_descriptor_id",
          "remap_set_id",
          "result",
          "executed_target_game_instance_id",
          "executed_pointer_version",
          "execution_control_plane_request_id");
  private static final Set<String> NULLABLE_INSTANCE_FIELDS =
      Set.of(
          "script_patch_version",
          "game_template_id",
          "launch_descriptor_id",
          "version_id",
          "release_bundle_id",
          "version_state_epoch",
          "generation_config_revision",
          "remap_set_id",
          "script_patch_pinned_control_plane_request_id",
          "script_pin_epoch");
  private static final Set<String> NULLABLE_POINTER_EVENT_FIELDS =
      Set.of("prepared_version_upgrade_id");
  private static final Set<String> NULLABLE_PREPARED_UPGRADE_FIELDS =
      Set.of(
          "remap_set_id",
          "executed_target_game_instance_id",
          "executed_pointer_version",
          "execution_control_plane_request_id");

  public GameSessionRetainedTenantSnapshot {
    validateIdentity(targetNamespace, legacyGameSessionTenantId);
    if (canonicalJson == null) {
      throw new IllegalArgumentException("canonicalJson must not be null");
    }
    if (evidenceDigest == null || !SHA256.matcher(evidenceDigest).matches()) {
      throw new IllegalArgumentException("A canonical SHA-256 digest is required");
    }
    validateCanonicalSnapshot(targetNamespace, legacyGameSessionTenantId, canonicalJson);
    String expectedDigest = digest(canonicalJson);
    if (!expectedDigest.equals(evidenceDigest)) {
      throw new IllegalArgumentException("Retained-tenant snapshot digest does not match its JSON");
    }
  }

  /** Revalidates stored canonical JSON and its digest without accepting caller-built maps. */
  public static GameSessionRetainedTenantSnapshot fromCanonicalJson(
      String targetNamespace,
      String legacyGameSessionTenantId,
      String canonicalJson,
      String evidenceDigest) {
    return new GameSessionRetainedTenantSnapshot(
        targetNamespace, legacyGameSessionTenantId, canonicalJson, evidenceDigest);
  }

  static GameSessionRetainedTenantSnapshot fromProjection(
      String targetNamespace, String legacyGameSessionTenantId, ObjectNode projection) {
    validateIdentity(targetNamespace, legacyGameSessionTenantId);
    if (projection == null) {
      throw new IllegalArgumentException("projection must not be null");
    }
    String canonicalJson = canonicalize(projection);
    return new GameSessionRetainedTenantSnapshot(
        targetNamespace, legacyGameSessionTenantId, canonicalJson, digest(canonicalJson));
  }

  static void validateIdentity(String targetNamespace, String legacyGameSessionTenantId) {
    if (targetNamespace == null || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    BigInteger tenantId = parseDecimal(legacyGameSessionTenantId, "legacyGameSessionTenantId");
    if (tenantId.signum() <= 0 || tenantId.compareTo(LONG_MAX) > 0) {
      throw new IllegalArgumentException("legacyGameSessionTenantId must be a positive BIGINT");
    }
  }

  private static String canonicalize(ObjectNode projection) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(projection)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Retained-tenant snapshot is not canonical JSON", exception);
    }
  }

  private static void validateCanonicalSnapshot(
      String targetNamespace, String legacyGameSessionTenantId, String canonicalJson) {
    final JsonNode envelope;
    final byte[] canonicalBytes;
    try {
      envelope = JSON.readTree(canonicalJson);
      canonicalBytes = Rfc8785CanonicalJson.canonicalizeUtf8(canonicalJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Retained-tenant snapshot JSON is malformed", exception);
    }
    if (envelope == null || !envelope.isObject()) {
      throw new IllegalArgumentException("Retained-tenant snapshot must be a JSON object");
    }
    if (!Arrays.equals(canonicalBytes, canonicalJson.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Retained-tenant snapshot JSON is not RFC 8785 canonical");
    }
    ObjectNode object = (ObjectNode) envelope;
    requireExactFields(object, ENVELOPE_FIELDS, "snapshot");
    JsonNode schemaVersion = object.get("schemaVersion");
    if (schemaVersion == null
        || !schemaVersion.isIntegralNumber()
        || !schemaVersion.canConvertToInt()
        || schemaVersion.intValue() != 1) {
      throw new IllegalArgumentException("snapshot.schemaVersion must be integer 1");
    }
    requireExactText(object, "targetNamespace", targetNamespace, "snapshot");
    requireExactText(object, "legacyGameSessionTenantId", legacyGameSessionTenantId, "snapshot");

    JsonNode instances = requireArray(object, "instances", "snapshot");
    JsonNode pointers = requireArray(object, "pointers", "snapshot");
    JsonNode sharedNamespaces = requireArray(object, "sharedNamespaces", "snapshot");
    JsonNode backfillIssues = requireArray(object, "backfillIssues", "snapshot");
    JsonNode pointerEvents = requireArray(object, "pointerEvents", "snapshot");
    JsonNode preparedUpgrades = requireArray(object, "preparedUpgrades", "snapshot");

    if (!backfillIssues.isEmpty()) {
      throw new IllegalArgumentException(
          "snapshot.backfillIssues must be empty for valid evidence");
    }

    String tenantId = legacyGameSessionTenantId;
    validateRows(
        instances,
        INSTANCE_FIELDS,
        Set.of(
            "id",
            "tenant_id",
            "owner_account_id",
            "row_version",
            "game_template_id",
            "version_id",
            "release_bundle_id",
            "version_state_epoch",
            "script_pin_epoch"),
        Set.of("row_version"),
        Set.of(),
        Set.of(),
        NULLABLE_INSTANCE_FIELDS,
        "id",
        tenantId,
        "instances");
    validateRows(
        pointers,
        POINTER_FIELDS,
        Set.of("id", "tenant_id", "game_instance_id", "pointer_version", "catalog_revision"),
        Set.of(),
        Set.of("realm_id", "playable_state_namespace_id"),
        Set.of("visible", "requires_character_selection", "public_production_realm"),
        Set.of(),
        "id",
        tenantId,
        "pointers");
    validateRows(
        sharedNamespaces,
        SHARED_NAMESPACE_FIELDS,
        Set.of("tenant_id"),
        Set.of(),
        Set.of("playable_state_namespace_id"),
        Set.of(),
        Set.of(),
        "tenant_id",
        tenantId,
        "sharedNamespaces");
    validateRows(
        pointerEvents,
        POINTER_EVENT_FIELDS,
        Set.of("id", "tenant_id", "game_instance_id", "pointer_version"),
        Set.of(),
        Set.of(),
        Set.of("visible", "requires_character_selection", "public_production_realm"),
        NULLABLE_POINTER_EVENT_FIELDS,
        "id",
        tenantId,
        "pointerEvents");
    validateRows(
        preparedUpgrades,
        PREPARED_UPGRADE_FIELDS,
        Set.of(
            "id",
            "tenant_id",
            "source_game_instance_id",
            "source_version_id",
            "target_version_id",
            "executed_target_game_instance_id",
            "executed_pointer_version"),
        Set.of(),
        Set.of(),
        Set.of(),
        NULLABLE_PREPARED_UPGRADE_FIELDS,
        "id",
        tenantId,
        "preparedUpgrades");

    validatePointerRelationships(instances, pointers, sharedNamespaces, tenantId);
    if (instances.isEmpty()
        && pointers.isEmpty()
        && sharedNamespaces.isEmpty()
        && pointerEvents.isEmpty()
        && preparedUpgrades.isEmpty()) {
      throw new IllegalArgumentException("snapshot contains no retained tenant evidence");
    }
  }

  private static void validateRows(
      JsonNode rows,
      Set<String> fields,
      Set<String> numericFields,
      Set<String> nonnegativeNumericFields,
      Set<String> uuidFields,
      Set<String> booleanFields,
      Set<String> nullableFields,
      String sortField,
      String tenantId,
      String path) {
    BigInteger previousSortValue = null;
    for (int index = 0; index < rows.size(); index++) {
      JsonNode row = rows.get(index);
      String rowPath = path + "[" + index + "]";
      if (row == null || !row.isObject()) {
        throw new IllegalArgumentException(rowPath + " must be an object");
      }
      ObjectNode object = (ObjectNode) row;
      requireExactFields(object, fields, rowPath);
      Iterator<String> fieldNames = fields.iterator();
      while (fieldNames.hasNext()) {
        String field = fieldNames.next();
        JsonNode value = object.get(field);
        if (value == null) {
          throw new IllegalArgumentException(rowPath + "." + field + " is required");
        }
        if (value.isNull()) {
          if (!nullableFields.contains(field)) {
            throw new IllegalArgumentException(rowPath + "." + field + " may not be null");
          }
          continue;
        }
        if (numericFields.contains(field)) {
          BigInteger parsed = parseDecimalNode(value, rowPath + "." + field);
          if (parsed.compareTo(LONG_MIN) < 0 || parsed.compareTo(LONG_MAX) > 0) {
            throw new IllegalArgumentException(
                rowPath + "." + field + " is outside SQL BIGINT bounds");
          }
          if (parsed.signum() <= 0 && !nonnegativeNumericFields.contains(field)) {
            throw new IllegalArgumentException(rowPath + "." + field + " must be positive");
          }
          if (parsed.signum() < 0 && nonnegativeNumericFields.contains(field)) {
            throw new IllegalArgumentException(rowPath + "." + field + " must be nonnegative");
          }
        } else if (uuidFields.contains(field)) {
          requireUuid(value, rowPath + "." + field);
        } else if (booleanFields.contains(field)) {
          if (!value.isBoolean()) {
            throw new IllegalArgumentException(rowPath + "." + field + " must be a JSON boolean");
          }
        } else if (!value.isTextual()) {
          throw new IllegalArgumentException(rowPath + "." + field + " must be a JSON string");
        }
      }

      JsonNode tenantNode = object.get("tenant_id");
      if (tenantNode != null && !tenantId.equals(tenantNode.textValue())) {
        throw new IllegalArgumentException(rowPath + ".tenant_id contradicts the snapshot key");
      }
      BigInteger sortValue = parseDecimalNode(object.get(sortField), rowPath + "." + sortField);
      if (previousSortValue != null && sortValue.compareTo(previousSortValue) <= 0) {
        throw new IllegalArgumentException(path + " must be strictly sorted by " + sortField);
      }
      previousSortValue = sortValue;
    }
  }

  private static void validatePointerRelationships(
      JsonNode instances, JsonNode pointers, JsonNode sharedNamespaces, String tenantId) {
    Set<String> instanceIds = new java.util.HashSet<>();
    for (JsonNode instance : instances) {
      instanceIds.add(instance.get("id").textValue());
    }
    String sharedNamespaceId =
        sharedNamespaces.isEmpty()
            ? null
            : sharedNamespaces.get(0).get("playable_state_namespace_id").textValue();
    if (sharedNamespaces.size() > 1) {
      throw new IllegalArgumentException(
          "sharedNamespaces may contain only the tenant's single allocation");
    }
    Set<String> realmIds = new java.util.HashSet<>();
    Set<String> pointerInstanceIds = new java.util.HashSet<>();
    Set<String> selectors = new java.util.HashSet<>();
    Set<String> isolatedNamespaceIds = new java.util.HashSet<>();
    for (int index = 0; index < pointers.size(); index++) {
      JsonNode pointer = pointers.get(index);
      String path = "pointers[" + index + "]";
      if (!tenantId.equals(pointer.get("tenant_id").textValue())) {
        throw new IllegalArgumentException(path + ".tenant_id contradicts the snapshot key");
      }
      if (!instanceIds.contains(pointer.get("game_instance_id").textValue())) {
        throw new IllegalArgumentException(
            path + ".game_instance_id has no retained tenant instance");
      }
      if (!realmIds.add(pointer.get("realm_id").textValue())) {
        throw new IllegalArgumentException(path + ".realm_id contradicts another current pointer");
      }
      if (!pointerInstanceIds.add(pointer.get("game_instance_id").textValue())) {
        throw new IllegalArgumentException(
            path + ".game_instance_id is bound by multiple current pointers");
      }
      String selector =
          pointer.get("world_slug").textValue() + "\u0000" + pointer.get("realm_slug").textValue();
      if (!selectors.add(selector)) {
        throw new IllegalArgumentException(path + " duplicates another current pointer selector");
      }
      String scope = pointer.get("state_scope").textValue();
      String pointerNamespace = pointer.get("playable_state_namespace_id").textValue();
      if ("SHARED".equals(scope)) {
        if (sharedNamespaceId == null || !sharedNamespaceId.equals(pointerNamespace)) {
          throw new IllegalArgumentException(
              path + " does not match the tenant SHARED namespace allocation");
        }
      } else if ("ISOLATED".equals(scope)) {
        if (sharedNamespaceId != null && sharedNamespaceId.equals(pointerNamespace)) {
          throw new IllegalArgumentException(
              path + " uses the tenant SHARED allocation as ISOLATED state");
        }
        if (!isolatedNamespaceIds.add(pointerNamespace)) {
          throw new IllegalArgumentException(
              path + " reuses another ISOLATED playable-state namespace");
        }
      } else {
        throw new IllegalArgumentException(
            path + ".state_scope must be exactly SHARED or ISOLATED");
      }
    }
  }

  private static JsonNode requireArray(ObjectNode object, String field, String path) {
    JsonNode value = object.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException(path + "." + field + " must be an array");
    }
    return value;
  }

  private static void requireExactFields(ObjectNode object, Set<String> expected, String path) {
    if (object.size() != expected.size()) {
      throw new IllegalArgumentException(path + " has missing or undeclared fields");
    }
    Iterator<String> fields = object.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!expected.contains(field)) {
        throw new IllegalArgumentException(
            path + "." + field + " is not declared by schema version 1");
      }
    }
    for (String field : expected) {
      if (!object.has(field)) {
        throw new IllegalArgumentException(path + "." + field + " is required");
      }
    }
  }

  private static void requireExactText(
      ObjectNode object, String field, String expected, String path) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
      throw new IllegalArgumentException(
          path + "." + field + " does not match its snapshot selector");
    }
  }

  private static BigInteger parseDecimalNode(JsonNode node, String path) {
    if (node == null || !node.isTextual()) {
      throw new IllegalArgumentException(path + " must be a canonical decimal string");
    }
    return parseDecimal(node.textValue(), path);
  }

  private static BigInteger parseDecimal(String value, String path) {
    if (value == null || !CANONICAL_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(path + " must be a canonical decimal string");
    }
    return new BigInteger(value);
  }

  private static void requireUuid(JsonNode value, String path) {
    if (!value.isTextual() || !CANONICAL_UUID.matcher(value.textValue()).matches()) {
      throw new IllegalArgumentException(path + " must be a lowercase canonical UUID string");
    }
    UUID parsed = UUID.fromString(value.textValue());
    if (parsed.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(path + " must not be a nil UUID");
    }
  }

  private static String digest(String canonicalJson) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      updateFrame(hash, DIGEST_DOMAIN);
      updateFrame(hash, canonicalJson);
      return "sha256:" + HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void updateFrame(MessageDigest hash, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    hash.update((byte) ':');
    hash.update(bytes);
  }
}
