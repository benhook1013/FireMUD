package net.firedevops.firemud.accountservice.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed, bounded reader for Account's canonical owner-local descriptor resources. */
public final class AccountRedisScriptDescriptorLoader {
  public static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private AccountRedisScriptDescriptorLoader() {}

  public static RedisScriptDescriptor load(Class<?> owner, String resource) {
    try (InputStream input = owner.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("Account Redis descriptor is unavailable");
      return parse(input.readNBytes(MAX_DESCRIPTOR_BYTES + 1));
    } catch (IOException exception) {
      throw new IllegalStateException("Account Redis descriptor is unavailable", exception);
    }
  }

  public static RedisScriptDescriptor parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_DESCRIPTOR_BYTES) {
      throw new IllegalArgumentException("Account Redis descriptor size is invalid");
    }
    JsonNode root = JSON.readTree(bytes);
    fields(
        root,
        "scriptId",
        "resourcePath",
        "sha256",
        "owner",
        "principal",
        "role",
        "keys",
        "arguments",
        "category",
        "outcomes",
        "resetSensitivity",
        "lossClass",
        "tailLossBehavior",
        "compatibilityLevel",
        "supportedCoexistence",
        "legacyPayloadShape");
    array(root, "keys", "name", "ownedPrefix", "hashTagDeclaration");
    array(root, "arguments", "name");
    array(root, "outcomes", "code", "category", "effect");
    array(root, "supportedCoexistence", "callerVersion", "payloadVersion");
    for (String field :
        Set.of(
            "scriptId",
            "resourcePath",
            "sha256",
            "owner",
            "principal",
            "role",
            "category",
            "resetSensitivity",
            "lossClass",
            "tailLossBehavior",
            "compatibilityLevel")) {
      if (!root.get(field).isTextual()) {
        throw new IllegalArgumentException("Descriptor text is required");
      }
    }
    if (!root.get("legacyPayloadShape").isNull() && !root.get("legacyPayloadShape").isTextual()) {
      throw new IllegalArgumentException("Descriptor legacy shape is invalid");
    }
    return JSON.treeToValue(root, RedisScriptDescriptor.class);
  }

  private static void array(JsonNode root, String name, String... itemFields) {
    JsonNode items = root.get(name);
    if (!items.isArray()) throw new IllegalArgumentException("Descriptor array is required");
    for (JsonNode item : items) {
      fields(item, itemFields);
      for (String field : itemFields) {
        if (!item.get(field).isTextual()) {
          throw new IllegalArgumentException("Descriptor declaration text is required");
        }
      }
    }
  }

  private static void fields(JsonNode node, String... names) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Descriptor object is required");
    }
    Set<String> actual = new HashSet<>();
    node.properties().forEach(entry -> actual.add(entry.getKey()));
    if (!actual.equals(Set.of(names))) {
      throw new IllegalArgumentException("Descriptor fields are not canonical");
    }
  }
}
