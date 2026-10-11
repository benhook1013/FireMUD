package net.firedevops.firemud.common.gamedesign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Existing template source JSON grammar, shared by data decoding and GD adapters. */
final class TemplateConfigSourceJson {
  private static final ObjectMapper JSON = new ObjectMapper();

  private TemplateConfigSourceJson() {}

  static void requireStoredFields(JsonNode node, String... names) {
    if (!node.isObject() || node.size() != names.length)
      throw new IllegalArgumentException("Closed command source operation fields are required");
    for (String name : names)
      if (node.get(name) == null)
        throw new IllegalArgumentException("Missing command source field " + name);
  }

  static String requiredStoredText(JsonNode source, String field) {
    JsonNode value = source.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank())
      throw new IllegalArgumentException("Command definition text is required: " + field);
    return value.asText();
  }

  static String canonical(Object value) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Canonical command source encoding failed", failure);
    }
  }

  static JsonNode tree(String json) {
    try {
      return JSON.readTree(Rfc8785CanonicalJson.canonicalizeUtf8(json));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Unambiguous command source JSON required", failure);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }
}
