package net.firedevops.firemud.common.redis.contracts;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded owner-declared metadata for one Redis script; it contains no executable behavior. */
public record RedisScriptDescriptor(
    String id,
    String owner,
    String scriptResource,
    String sourceSha256,
    RedisRole redisRole,
    String category,
    List<KeyDeclaration> keys,
    List<String> arguments,
    String resetSensitivity,
    String lossOutcomeClass,
    String tailLossBehavior,
    Map<Integer, String> outcomes) {
  public static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
  public static final int MAX_KEYS = 16;
  public static final int MAX_ARGUMENTS = 32;
  public static final int MAX_OUTCOMES = 16;

  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._/-]{0,95}");
  private static final Pattern OWNER = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern KEY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
  private static final Pattern METADATA = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final Pattern HASH_TAG = Pattern.compile("\\{[A-Za-z0-9._-]{1,64}\\}");
  private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9._/-]{1,160}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public RedisScriptDescriptor {
    requireMatch(ID, id, "script id");
    if (id.contains("..")) throw invalid("Script id is malformed");
    requireMatch(OWNER, owner, "owner");
    requireMatch(RESOURCE, scriptResource, "script resource");
    if (scriptResource.startsWith("/")
        || scriptResource.contains("\\")
        || List.of(scriptResource.split("/", -1)).contains("..")) {
      throw invalid("Script resource must be a bounded classpath-relative path");
    }
    requireMatch(SHA256, sourceSha256, "script source SHA-256");
    Objects.requireNonNull(redisRole, "Redis role is required");
    requireMatch(METADATA, category, "script category");
    keys = List.copyOf(Objects.requireNonNull(keys, "KEYS declaration is required"));
    if (keys.isEmpty() || keys.size() > MAX_KEYS) throw invalid("KEYS count is outside its bound");
    if (keys.stream().map(KeyDeclaration::name).distinct().count() != keys.size()) {
      throw invalid("KEYS names must be unique");
    }
    arguments = List.copyOf(Objects.requireNonNull(arguments, "ARGV declaration is required"));
    if (arguments.size() > MAX_ARGUMENTS) throw invalid("ARGV count is outside its bound");
    for (String argument : arguments) requireMatch(KEY_NAME, argument, "ARGV name");
    if (new HashSet<>(arguments).size() != arguments.size()) {
      throw invalid("ARGV names must be unique");
    }
    requireMatch(METADATA, resetSensitivity, "reset sensitivity");
    requireMatch(METADATA, lossOutcomeClass, "loss outcome class");
    requireBoundedText(tailLossBehavior, "tail-loss behavior", 256);
    outcomes = Map.copyOf(Objects.requireNonNull(outcomes, "Outcomes are required"));
    if (outcomes.isEmpty() || outcomes.size() > MAX_OUTCOMES) {
      throw invalid("Outcome count is outside its bound");
    }
    for (Map.Entry<Integer, String> outcome : outcomes.entrySet()) {
      if (outcome.getKey() == null || outcome.getKey() < -1000 || outcome.getKey() > 1000) {
        throw invalid("Outcome code is outside its bound");
      }
      requireMatch(METADATA, outcome.getValue(), "outcome name");
    }
  }

  /** Parses a strict, bounded UTF-8 JSON owner descriptor with no unknown fields. */
  public static RedisScriptDescriptor parse(byte[] descriptorBytes) {
    Objects.requireNonNull(descriptorBytes, "Descriptor bytes are required");
    if (descriptorBytes.length == 0 || descriptorBytes.length > MAX_DESCRIPTOR_BYTES) {
      throw invalid("Script descriptor exceeds its byte bound");
    }
    final JsonNode root;
    try {
      String text = strictUtf8(descriptorBytes);
      root = JSON.readTree(text);
    } catch (RuntimeException ex) {
      throw invalid("Script descriptor is malformed");
    }
    if (root == null || !root.isObject()) throw invalid("Script descriptor must be an object");
    requireExactFields(
        root,
        Set.of(
            "schemaVersion",
            "id",
            "owner",
            "scriptResource",
            "sourceSha256",
            "redisRole",
            "category",
            "keys",
            "arguments",
            "resetSensitivity",
            "lossOutcomeClass",
            "tailLossBehavior",
            "outcomes"));
    if (!root.get("schemaVersion").isIntegralNumber()
        || !root.get("schemaVersion").canConvertToInt()
        || root.get("schemaVersion").intValue() != 1) {
      throw invalid("Unsupported script descriptor schema version");
    }

    List<KeyDeclaration> keys = new ArrayList<>();
    JsonNode keysNode = root.get("keys");
    if (!keysNode.isArray() || keysNode.size() == 0 || keysNode.size() > MAX_KEYS) {
      throw invalid("KEYS declaration is outside its bound");
    }
    for (JsonNode key : keysNode) {
      if (!key.isObject()) throw invalid("KEYS declarations must be objects");
      requireExactFields(key, Set.of("name", "allowedPrefix", "requiredHashTag"));
      keys.add(
          new KeyDeclaration(
              text(key.get("name"), "KEYS name", 64),
              text(key.get("allowedPrefix"), "KEYS prefix", 128),
              optionalEmptyText(key.get("requiredHashTag"), "KEYS hash tag", 66)));
    }

    List<String> arguments = new ArrayList<>();
    JsonNode argumentsNode = root.get("arguments");
    if (!argumentsNode.isArray() || argumentsNode.size() > MAX_ARGUMENTS) {
      throw invalid("ARGV declaration is outside its bound");
    }
    for (JsonNode argument : argumentsNode) {
      arguments.add(text(argument, "ARGV name", 64));
    }

    JsonNode outcomesNode = root.get("outcomes");
    if (!outcomesNode.isObject()
        || outcomesNode.size() == 0
        || outcomesNode.size() > MAX_OUTCOMES) {
      throw invalid("Outcomes declaration is outside its bound");
    }
    Map<Integer, String> outcomes = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> outcome : outcomesNode.properties()) {
      final int code;
      try {
        if (!outcome.getKey().matches("-?(0|[1-9][0-9]{0,3})")) {
          throw new NumberFormatException();
        }
        code = Integer.parseInt(outcome.getKey());
        if (!Integer.toString(code).equals(outcome.getKey())) {
          throw new NumberFormatException();
        }
      } catch (NumberFormatException ex) {
        throw invalid("Outcome code is malformed");
      }
      outcomes.put(code, text(outcome.getValue(), "Outcome name", 64));
    }

    return new RedisScriptDescriptor(
        text(root.get("id"), "Script id", 96),
        text(root.get("owner"), "Owner", 64),
        text(root.get("scriptResource"), "Script resource", 160),
        text(root.get("sourceSha256"), "Script source SHA-256", 64),
        RedisRole.parse(text(root.get("redisRole"), "Redis role", 32)),
        text(root.get("category"), "Script category", 64),
        keys,
        arguments,
        text(root.get("resetSensitivity"), "Reset sensitivity", 64),
        text(root.get("lossOutcomeClass"), "Loss outcome class", 64),
        text(root.get("tailLossBehavior"), "Tail-loss behavior", 256),
        outcomes);
  }

  private static void requireExactFields(JsonNode object, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    for (Map.Entry<String, JsonNode> property : object.properties()) actual.add(property.getKey());
    if (!actual.equals(expected)) throw invalid("Script descriptor fields are not exact");
  }

  private static String text(JsonNode node, String field, int maximum) {
    if (node == null || !node.isTextual()) throw invalid(field + " must be text");
    String value = node.stringValue();
    requireBoundedText(value, field, maximum);
    return value;
  }

  private static String optionalEmptyText(JsonNode node, String field, int maximum) {
    if (node == null || !node.isTextual()) throw invalid(field + " must be text");
    String value = node.stringValue();
    if (value.length() > maximum) throw invalid(field + " is outside its bound");
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) throw invalid(field + " is malformed");
    }
    return value;
  }

  private static void requireBoundedText(String value, String field, int maximum) {
    if (value == null || value.isEmpty() || value.length() > maximum) {
      throw invalid(field + " is outside its bound");
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) throw invalid(field + " is malformed");
    }
  }

  private static void requireMatch(Pattern pattern, String value, String field) {
    if (value == null || !pattern.matcher(value).matches()) throw invalid(field + " is malformed");
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw invalid("Script descriptor is not valid UTF-8");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  public enum RedisRole {
    COORDINATION,
    CACHE;

    static RedisRole parse(String value) {
      return switch (value) {
        case "coordination" -> COORDINATION;
        case "cache" -> CACHE;
        default -> throw invalid("Redis role is unsupported");
      };
    }
  }

  public record KeyDeclaration(String name, String allowedPrefix, String requiredHashTag) {
    public KeyDeclaration {
      requireMatch(KEY_NAME, name, "KEYS name");
      requireBoundedText(allowedPrefix, "KEYS prefix", 128);
      if (!allowedPrefix.matches("[A-Za-z0-9:_{}.-]+")) throw invalid("KEYS prefix is malformed");
      Objects.requireNonNull(requiredHashTag, "Required hash tag declaration is required");
      if (!requiredHashTag.isEmpty() && !HASH_TAG.matcher(requiredHashTag).matches()) {
        throw invalid("Required hash tag is malformed");
      }
      if (!requiredHashTag.isEmpty() && !allowedPrefix.contains(requiredHashTag)) {
        throw invalid("Required hash tag is not present in the declared key prefix");
      }
    }
  }
}
