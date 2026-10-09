package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed v1 authored realm-entry policy shared by the publication owner and runtime receivers. */
public record RealmEntryPolicy(
    int schemaVersion,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProduction,
    StateScope stateScope,
    EntryPolicy entryPolicy,
    String canonicalJson) {
  public static final String REVISION_KIND = "REALM_ENTRY_POLICY";
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_JSON_BYTES = 4096;
  public static final int MAX_SLUG_LENGTH = 64;
  public static final int MAX_DISPLAY_NAME_CODE_POINTS = 128;

  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "worldSlug",
          "worldDisplayName",
          "realmSlug",
          "realmDisplayName",
          "visible",
          "publicProduction",
          "stateScope",
          "entryPolicy");
  private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  public enum StateScope {
    SHARED,
    ISOLATED
  }

  public enum EntryPolicy {
    PRESEEDED_ONLY
  }

  /** Parses valid v1 JSON and returns the RFC 8785 canonical policy representation. */
  public static RealmEntryPolicy parse(String json, ObjectMapper objectMapper) {
    if (json == null || json.isBlank()) {
      throw invalid("data is required");
    }
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(json)) {
      throw invalid("data must be valid UTF-8");
    }
    ByteBuffer inputBytes = StandardCharsets.UTF_8.encode(json);
    if (inputBytes.remaining() > MAX_JSON_BYTES) {
      throw invalid("data exceeds the v1 byte limit");
    }

    JsonNode root;
    String canonicalJson;
    try {
      canonicalJson =
          new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
      root = objectMapper.readTree(canonicalJson);
    } catch (IOException | RuntimeException exception) {
      throw invalid("data must be valid unambiguous JSON");
    }
    if (root == null || !root.isObject()) {
      throw invalid("must be a JSON object");
    }
    for (String field : FIELDS) {
      if (!root.has(field)) {
        throw invalid(field + " is required");
      }
    }
    if (root.size() != FIELDS.size()) {
      throw invalid("must contain exactly the v1 fields");
    }

    JsonNode schemaVersion = root.get("schemaVersion");
    if (schemaVersion == null
        || !schemaVersion.isIntegralNumber()
        || !schemaVersion.canConvertToInt()
        || schemaVersion.intValue() != SCHEMA_VERSION) {
      throw invalid("schemaVersion must be the integer 1");
    }
    String worldSlug = requireSlug(root, "worldSlug");
    String worldDisplayName = requireDisplayName(root, "worldDisplayName");
    String realmSlug = requireSlug(root, "realmSlug");
    String realmDisplayName = requireDisplayName(root, "realmDisplayName");
    boolean visible = requireBoolean(root, "visible");
    boolean publicProduction = requireBoolean(root, "publicProduction");
    StateScope stateScope = requireEnum(root, "stateScope", StateScope.class);
    EntryPolicy entryPolicy = requireEnum(root, "entryPolicy", EntryPolicy.class);

    return new RealmEntryPolicy(
        SCHEMA_VERSION,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        visible,
        publicProduction,
        stateScope,
        entryPolicy,
        canonicalJson);
  }

  /** Parses policy evidence that is required to already use its exact canonical JSON spelling. */
  public static RealmEntryPolicy parseCanonical(String json, ObjectMapper objectMapper) {
    RealmEntryPolicy policy = parse(json, objectMapper);
    if (!json.equals(policy.canonicalJson())) {
      throw invalid("published policy JSON is not canonical");
    }
    return policy;
  }

  public static boolean isCanonicalSlug(String value) {
    return value != null
        && value.length() <= MAX_SLUG_LENGTH
        && SLUG_PATTERN.matcher(value).matches();
  }

  private static String requireSlug(JsonNode root, String field) {
    String value = requireText(root, field);
    if (!isCanonicalSlug(value)) {
      throw invalid(field + " must be a canonical lower-case slug");
    }
    return value;
  }

  private static String requireDisplayName(JsonNode root, String field) {
    String value = requireText(root, field);
    if (!value.equals(value.strip())
        || value.codePointCount(0, value.length()) > MAX_DISPLAY_NAME_CODE_POINTS
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid(field + " is outside the v1 text bounds");
    }
    return value;
  }

  private static String requireText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw invalid(field + " must be a nonblank string");
    }
    return value.asText();
  }

  private static boolean requireBoolean(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalid(field + " must be an explicit boolean");
    }
    return value.booleanValue();
  }

  private static <T extends Enum<T>> T requireEnum(JsonNode root, String field, Class<T> type) {
    String value = requireText(root, field);
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException exception) {
      throw invalid("unsupported " + field);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("INVALID_ARGUMENT: realmEntryPolicy " + message);
  }
}
