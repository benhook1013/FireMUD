package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed authored realm-entry policy shared by the publication owner and runtime receivers. */
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
    PlayerCreationDescriptor creationDescriptor,
    String canonicalJson) {
  public static final String REVISION_KIND = "REALM_ENTRY_POLICY";
  public static final int SCHEMA_VERSION = 1;
  public static final int PLAYER_CREATED_SCHEMA_VERSION = 2;
  public static final int MAX_JSON_BYTES = 4096;
  public static final int MAX_SLUG_LENGTH = 64;
  public static final int MAX_DISPLAY_NAME_CODE_POINTS = 128;

  private static final Set<String> V1_FIELDS =
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
  private static final Set<String> V2_FIELDS =
      Set.of(
          "schemaVersion",
          "worldSlug",
          "worldDisplayName",
          "realmSlug",
          "realmDisplayName",
          "visible",
          "publicProduction",
          "stateScope",
          "entryPolicy",
          "creationDescriptor");
  private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  public RealmEntryPolicy {
    Objects.requireNonNull(stateScope, "stateScope");
    Objects.requireNonNull(entryPolicy, "entryPolicy");
    Objects.requireNonNull(canonicalJson, "canonicalJson");
    if (schemaVersion == SCHEMA_VERSION) {
      if (entryPolicy != EntryPolicy.PRESEEDED_ONLY || creationDescriptor != null) {
        throw invalid("schemaVersion 1 requires PRESEEDED_ONLY without a descriptor");
      }
    } else if (schemaVersion == PLAYER_CREATED_SCHEMA_VERSION) {
      if (entryPolicy != EntryPolicy.PLAYER_CREATED || creationDescriptor == null) {
        throw invalid("schemaVersion 2 requires PLAYER_CREATED with a descriptor");
      }
    } else {
      throw invalid("schemaVersion is unsupported");
    }
  }

  public enum StateScope {
    SHARED,
    ISOLATED
  }

  public enum EntryPolicy {
    PRESEEDED_ONLY,
    PLAYER_CREATED
  }

  /** Parses supported policy JSON and returns its RFC 8785 canonical representation. */
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
    JsonNode schemaVersion = root.get("schemaVersion");
    if (schemaVersion == null
        || !schemaVersion.isIntegralNumber()
        || !schemaVersion.canConvertToInt()) {
      throw invalid("schemaVersion must be a supported integer");
    }
    int version = schemaVersion.intValue();
    Set<String> fields =
        switch (version) {
          case SCHEMA_VERSION -> V1_FIELDS;
          case PLAYER_CREATED_SCHEMA_VERSION -> V2_FIELDS;
          default -> throw invalid("schemaVersion is unsupported");
        };
    for (String field : fields) {
      if (!root.has(field)) {
        throw invalid(field + " is required");
      }
    }
    if (root.size() != fields.size()) {
      throw invalid("must contain exactly the schema fields");
    }
    String worldSlug = requireSlug(root, "worldSlug");
    String worldDisplayName = requireDisplayName(root, "worldDisplayName");
    String realmSlug = requireSlug(root, "realmSlug");
    String realmDisplayName = requireDisplayName(root, "realmDisplayName");
    boolean visible = requireBoolean(root, "visible");
    boolean publicProduction = requireBoolean(root, "publicProduction");
    StateScope stateScope = requireEnum(root, "stateScope", StateScope.class);
    EntryPolicy entryPolicy = requireEnum(root, "entryPolicy", EntryPolicy.class);
    if (version == SCHEMA_VERSION && entryPolicy != EntryPolicy.PRESEEDED_ONLY) {
      throw invalid("schemaVersion 1 supports only PRESEEDED_ONLY");
    }
    if (version == PLAYER_CREATED_SCHEMA_VERSION && entryPolicy != EntryPolicy.PLAYER_CREATED) {
      throw invalid("schemaVersion 2 supports only PLAYER_CREATED");
    }
    PlayerCreationDescriptor creationDescriptor =
        version == PLAYER_CREATED_SCHEMA_VERSION
            ? PlayerCreationDescriptor.parse(root.get("creationDescriptor").toString())
            : null;

    return new RealmEntryPolicy(
        version,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        visible,
        publicProduction,
        stateScope,
        entryPolicy,
        creationDescriptor,
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
