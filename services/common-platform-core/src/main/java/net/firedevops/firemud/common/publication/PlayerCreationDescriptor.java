package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed schema-1 descriptor for a player-created actor with explicitly empty input. */
public final class PlayerCreationDescriptor {
  public static final int SCHEMA_VERSION = 1;
  public static final String INPUT_KIND = "EMPTY_OBJECT";
  public static final int MAX_JSON_BYTES = 4096;

  private static final Set<String> FIELDS =
      Set.of("schemaVersion", "descriptorId", "descriptorRevisionId", "inputKind", "dependencies");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final String descriptorId;
  private final String descriptorRevisionId;
  private final byte[] canonicalBytes;
  private final String canonicalJson;
  private final String digest;

  private PlayerCreationDescriptor(
      String descriptorId, String descriptorRevisionId, byte[] canonicalBytes) {
    this.descriptorId = descriptorId;
    this.descriptorRevisionId = descriptorRevisionId;
    this.canonicalBytes = canonicalBytes.clone();
    this.canonicalJson = new String(this.canonicalBytes, StandardCharsets.UTF_8);
    this.digest = sha256(this.canonicalBytes);
  }

  /** Parses the exact closed descriptor grammar and retains its canonical bytes and digest. */
  public static PlayerCreationDescriptor parse(String json) {
    byte[] inputBytes = boundedUtf8(json);
    final byte[] canonicalBytes;
    final JsonNode root;
    try {
      canonicalBytes = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      root = JSON.readTree(new String(canonicalBytes, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException invalidJson) {
      throw invalid("must be valid unambiguous JSON", invalidJson);
    }
    if (inputBytes.length > MAX_JSON_BYTES || root == null || !root.isObject()) {
      throw invalid("must be a bounded JSON object");
    }
    requireObjectFields(root);

    JsonNode schemaVersion = root.get("schemaVersion");
    if (schemaVersion == null
        || !schemaVersion.isIntegralNumber()
        || !schemaVersion.canConvertToInt()
        || schemaVersion.intValue() != SCHEMA_VERSION) {
      throw invalid("schemaVersion must be the integer 1");
    }
    String descriptorId = requireCanonicalNonNilUuid(root, "descriptorId");
    String descriptorRevisionId = requireCanonicalNonNilUuid(root, "descriptorRevisionId");
    JsonNode inputKind = root.get("inputKind");
    if (inputKind == null || !inputKind.isTextual() || !INPUT_KIND.equals(inputKind.asText())) {
      throw invalid("inputKind must be EMPTY_OBJECT");
    }
    JsonNode dependencies = root.get("dependencies");
    if (dependencies == null || !dependencies.isArray() || dependencies.size() != 0) {
      throw invalid("dependencies must be an explicit empty array");
    }
    return new PlayerCreationDescriptor(descriptorId, descriptorRevisionId, canonicalBytes);
  }

  public int schemaVersion() {
    return SCHEMA_VERSION;
  }

  public String descriptorId() {
    return descriptorId;
  }

  public String descriptorRevisionId() {
    return descriptorRevisionId;
  }

  public String inputKind() {
    return INPUT_KIND;
  }

  public List<String> dependencies() {
    return List.of();
  }

  public String canonicalJson() {
    return canonicalJson;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** SHA-256 of the complete canonical descriptor UTF-8 bytes, without an authority frame. */
  public String digest() {
    return digest;
  }

  /** Accepts only the explicitly present canonical empty-object creation input. */
  public void validateCreationInput(String inputJson) {
    if (!"{}".equals(inputJson)) {
      throw invalid("creation input must be the canonical empty object");
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PlayerCreationDescriptor descriptor
        && Arrays.equals(canonicalBytes, descriptor.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private static byte[] boundedUtf8(String json) {
    if (json == null) {
      throw invalid("data is required");
    }
    try {
      ByteBuffer inputBytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(json));
      if (inputBytes.remaining() > MAX_JSON_BYTES) {
        throw invalid("data exceeds the byte limit");
      }
      byte[] bytes = new byte[inputBytes.remaining()];
      inputBytes.get(bytes);
      return bytes;
    } catch (CharacterCodingException malformedUnicode) {
      throw invalid("data must be valid UTF-8", malformedUnicode);
    }
  }

  private static void requireObjectFields(JsonNode root) {
    Set<String> actual = new HashSet<>();
    root.properties().forEach(property -> actual.add(property.getKey()));
    if (!actual.equals(FIELDS)) {
      throw invalid("must contain exactly the descriptor fields");
    }
  }

  private static String requireCanonicalNonNilUuid(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || !node.isTextual()) {
      throw invalid(field + " must be a canonical nonzero UUID");
    }
    String value = node.asText();
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)
          || (parsed.getMostSignificantBits() == 0 && parsed.getLeastSignificantBits() == 0)) {
        throw invalid(field + " must be a canonical nonzero UUID");
      }
    } catch (IllegalArgumentException invalidUuid) {
      throw invalid(field + " must be a canonical nonzero UUID", invalidUuid);
    }
    return value;
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("INVALID_ARGUMENT: playerCreationDescriptor " + message);
  }

  private static IllegalArgumentException invalid(String message, Exception cause) {
    return new IllegalArgumentException(
        "INVALID_ARGUMENT: playerCreationDescriptor " + message, cause);
  }
}
