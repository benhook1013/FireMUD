package net.firedevops.firemud.common.automation;

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
 * Explicit empty-only authored Automation source inventory for the versioned Game Design config.
 *
 * <p>This value is authored content only. It is not owner readback, authorization, provenance, a
 * receipt, or proof that an Automation owner has no source rows. A separate authorized owner intake
 * and immutable readback must establish those facts before publication can use them.
 */
public final class AutomationAuthoredSourceInventoryDeclaration {
  public static final String SCHEMA = "automation-authored-source-inventory/v1";
  public static final int MAX_JSON_BYTES = 4 * 1024;

  private static final Set<String> ROOT_FIELDS = Set.of("schema", "families");
  private static final List<String> FAMILY_ORDER =
      List.of("SCRIPT_DEFINITIONS", "EVENT_BINDINGS", "SCRIPT_PATCH_SOURCES");
  private static final Set<String> FAMILY_FIELDS = Set.copyOf(FAMILY_ORDER);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final byte[] canonicalBytes;

  private AutomationAuthoredSourceInventoryDeclaration(byte[] canonicalBytes) {
    this.canonicalBytes = canonicalBytes.clone();
  }

  /**
   * Parses explicitly authored inventory content, accepting only its exact empty-only v1 form.
   * Nonempty families are rejected as unsupported rather than interpreted as empty.
   */
  public static AutomationAuthoredSourceInventoryDeclaration parse(String authoredJson) {
    if (authoredJson == null) {
      throw new IllegalArgumentException("Automation source inventory declaration is required");
    }
    validateBoundedUtf8(authoredJson);

    final JsonNode root;
    try {
      root = JSON.readTree(authoredJson);
    } catch (RuntimeException invalidJson) {
      throw new IllegalArgumentException(
          "Automation source inventory declaration is not valid closed JSON", invalidJson);
    }
    requireObjectFields(root, ROOT_FIELDS, "declaration");
    JsonNode schema = root.get("schema");
    if (schema == null || !schema.isTextual() || !SCHEMA.equals(schema.textValue())) {
      throw new IllegalArgumentException("Unsupported Automation source inventory schema");
    }

    JsonNode families = root.get("families");
    requireObjectFields(families, FAMILY_FIELDS, "families");
    for (String family : FAMILY_ORDER) {
      JsonNode entries = families.get(family);
      if (entries == null || !entries.isArray()) {
        throw new IllegalArgumentException("Automation source family must be an array: " + family);
      }
      if (entries.size() != 0) {
        throw new IllegalArgumentException(
            "Nonempty Automation source family is unsupported: " + family);
      }
    }

    try {
      return new AutomationAuthoredSourceInventoryDeclaration(
          Rfc8785CanonicalJson.canonicalizeUtf8(authoredJson));
    } catch (IOException invalidCanonicalJson) {
      throw new IllegalArgumentException(
          "Automation source inventory declaration could not be canonicalized",
          invalidCanonicalJson);
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
    return other instanceof AutomationAuthoredSourceInventoryDeclaration declaration
        && Arrays.equals(canonicalBytes, declaration.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private static void validateBoundedUtf8(String value) {
    if (value.length() > MAX_JSON_BYTES) {
      throw new IllegalArgumentException("Automation source inventory declaration is too large");
    }
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      if (encoded.remaining() > MAX_JSON_BYTES) {
        throw new IllegalArgumentException("Automation source inventory declaration is too large");
      }
    } catch (CharacterCodingException invalidUnicode) {
      throw new IllegalArgumentException(
          "Automation source inventory declaration contains malformed Unicode", invalidUnicode);
    }
  }

  private static void requireObjectFields(JsonNode value, Set<String> expected, String name) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(
          "Automation source inventory " + name + " must be an object");
    }
    Set<String> actual = new HashSet<>();
    value.properties().forEach(property -> actual.add(property.getKey()));
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          "Automation source inventory " + name + " has missing or unsupported fields");
    }
  }
}
