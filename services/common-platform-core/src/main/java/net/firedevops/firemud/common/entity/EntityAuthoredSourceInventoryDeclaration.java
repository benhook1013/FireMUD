package net.firedevops.firemud.common.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit empty-only authored Entity source inventory for the versioned Game Design config.
 *
 * <p>This value is authored content only. It is not owner readback, authorization, provenance, a
 * receipt, or proof that Entity has no source rows. A separate authorized owner intake and
 * immutable readback must establish those facts before publication can use them.
 */
public final class EntityAuthoredSourceInventoryDeclaration {
  public static final String SCHEMA = "entity-authored-source-inventory/v1";
  public static final int MAX_JSON_BYTES = 4 * 1024;

  private static final Set<String> ROOT_FIELDS =
      Set.of("schema", "equipmentApplicability", "families");
  private static final List<String> FAMILY_ORDER =
      List.of(
          "ACTOR_BODY_LAYOUT_ASSIGNMENTS",
          "ARCHETYPE_ASSIGNMENTS",
          "ARCHETYPE_CONSTRAINTS",
          "ARCHETYPE_ROOTS",
          "BALANCE_CURVE_ATTACHMENTS",
          "BALANCE_CURVE_ROOTS",
          "BODY_LAYOUT_MEMBERSHIPS",
          "BODY_LAYOUT_ROOTS",
          "CRAFTING_INGREDIENT_BINDINGS",
          "CRAFTING_RECIPE_RESULT_BINDINGS",
          "CRAFTING_RECIPE_ROOTS",
          "EQUIPMENT_ATTACHMENT_RULES",
          "EQUIPMENT_CAPABILITIES",
          "EQUIPMENT_COMPATIBILITY_RULES",
          "EQUIPMENT_OCCUPANCY_RULES",
          "EQUIPMENT_SLOT_GROUPS",
          "EQUIPMENT_SLOT_ROOTS",
          "INBOUND_LOOT_BINDINGS",
          "ITEM_TEMPLATE_ROOTS",
          "LOOT_ITEM_MAPPINGS",
          "LOOT_TABLE_ROOTS",
          "NPC_TEMPLATE_ROOTS",
          "OTHER_ACTOR_TEMPLATE_ROOTS");
  private static final Set<String> FAMILY_FIELDS = Set.copyOf(FAMILY_ORDER);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final byte[] canonicalBytes;

  private EntityAuthoredSourceInventoryDeclaration(byte[] canonicalBytes) {
    this.canonicalBytes = canonicalBytes.clone();
  }

  /**
   * Parses explicitly authored inventory content, accepting only its exact empty-only v1 form.
   * Nonempty families are rejected as unsupported rather than interpreted as empty.
   */
  public static EntityAuthoredSourceInventoryDeclaration parse(String authoredJson) {
    if (authoredJson == null) {
      throw new IllegalArgumentException("Entity source inventory declaration is required");
    }
    validateBoundedUtf8(authoredJson);

    final JsonNode root;
    try {
      root = JSON.readTree(authoredJson);
    } catch (RuntimeException invalidJson) {
      throw new IllegalArgumentException(
          "Entity source inventory declaration is not valid closed JSON", invalidJson);
    }
    requireObjectFields(root, ROOT_FIELDS, "declaration");
    JsonNode schema = root.get("schema");
    if (schema == null || !schema.isTextual() || !SCHEMA.equals(schema.textValue())) {
      throw new IllegalArgumentException("Unsupported Entity source inventory schema");
    }
    JsonNode equipmentApplicability = root.get("equipmentApplicability");
    if (equipmentApplicability == null
        || !equipmentApplicability.isTextual()
        || !"NOT_APPLICABLE".equals(equipmentApplicability.textValue())) {
      throw new IllegalArgumentException(
          "Empty Entity source inventory requires explicit equipment non-applicability");
    }

    JsonNode families = root.get("families");
    requireObjectFields(families, FAMILY_FIELDS, "families");
    for (String family : FAMILY_ORDER) {
      JsonNode entries = families.get(family);
      if (entries == null || !entries.isArray()) {
        throw new IllegalArgumentException("Entity source family must be an array: " + family);
      }
      if (entries.size() != 0) {
        throw new IllegalArgumentException(
            "Nonempty Entity source family is unsupported: " + family);
      }
    }

    try {
      return new EntityAuthoredSourceInventoryDeclaration(
          Rfc8785CanonicalJson.canonicalizeUtf8(authoredJson));
    } catch (IOException invalidCanonicalJson) {
      throw new IllegalArgumentException(
          "Entity source inventory declaration could not be canonicalized", invalidCanonicalJson);
    }
  }

  /** Returns the exact deterministic RFC 8785 JSON text retained by this value. */
  public String canonicalJson() {
    return new String(canonicalBytes, StandardCharsets.UTF_8);
  }

  /** Returns a defensive copy of the exact deterministic RFC 8785 bytes. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EntityAuthoredSourceInventoryDeclaration declaration
        && Arrays.equals(canonicalBytes, declaration.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private static void validateBoundedUtf8(String value) {
    if (value.length() > MAX_JSON_BYTES) {
      throw new IllegalArgumentException("Entity source inventory declaration is too large");
    }
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      if (encoded.remaining() > MAX_JSON_BYTES) {
        throw new IllegalArgumentException("Entity source inventory declaration is too large");
      }
    } catch (CharacterCodingException invalidUnicode) {
      throw new IllegalArgumentException(
          "Entity source inventory declaration contains malformed Unicode", invalidUnicode);
    }
  }

  private static void requireObjectFields(JsonNode value, Set<String> expected, String name) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Entity source inventory " + name + " must be an object");
    }
    Set<String> actual = new HashSet<>();
    value.properties().forEach(property -> actual.add(property.getKey()));
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          "Entity source inventory " + name + " has missing or unsupported fields");
    }
  }
}
