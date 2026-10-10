package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable integrity carrier for one durable World StartSession execution outcome.
 *
 * <p>This value binds the original post-authorization tuple to the exact World execution scope,
 * independent source fences, retained preparation input, and one durable terminal outcome. It does
 * not authenticate a producer, admit execution, or settle or release Account participation.
 */
public final class WorldStartSessionExecutionTerminal {
  public static final String SCHEMA = "world-start-session-execution-terminal/v1";
  public static final int MAX_CANONICAL_BYTES = 24 * 1_024 * 1_024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern CANONICAL_POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final int MAX_TUPLE_BASE64_CHARS =
      ((StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES + 2) / 3) * 4;
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "originalPostAuthorizationTupleBase64",
          "accountWorldParticipationId",
          "accountWorldParticipationFence",
          "gameSessionOwnerAttemptId",
          "gameSessionOwnerFence",
          "targetNamespace",
          "canonicalTenantId",
          "controlPlaneRequestId",
          "canonicalGameInstanceId",
          "preparationInputDigest",
          "preparationInputJson",
          "worldExecutionFence",
          "outcome");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final byte[] originalPostAuthorizationTuple;
  private final String originalPostAuthorizationTupleBase64;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
  private final String targetNamespace;
  private final UUID canonicalTenantId;
  private final String controlPlaneRequestId;
  private final UUID canonicalGameInstanceId;
  private final String preparationInputDigest;
  private final String preparationInputJson;
  private final long worldExecutionFence;
  private final Outcome outcome;
  private final byte[] canonicalBytes;
  private final String digest;

  public WorldStartSessionExecutionTerminal(
      byte[] originalPostAuthorizationTuple,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      String targetNamespace,
      UUID canonicalTenantId,
      String controlPlaneRequestId,
      UUID canonicalGameInstanceId,
      String preparationInputDigest,
      String preparationInputJson,
      long worldExecutionFence,
      Outcome outcome) {
    this.preparationInputJson = requirePreparationInput(preparationInputJson);
    jsonStringValueUtf8Length(this.preparationInputJson);
    Objects.requireNonNull(originalPostAuthorizationTuple, "originalPostAuthorizationTuple");
    if (originalPostAuthorizationTuple.length == 0
        || originalPostAuthorizationTuple.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw invalid("Original post-authorization tuple exceeds its byte limit");
    }
    this.originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
    StartSessionPostAuthorizationExecutionTuple originalTuple =
        StartSessionPostAuthorizationExecutionTuple.decode(this.originalPostAuthorizationTuple);
    requireStartSessionGameSessionTarget(originalTuple);

    this.accountWorldParticipationId =
        requireNonNil(accountWorldParticipationId, "accountWorldParticipationId");
    this.accountWorldParticipationFence =
        requirePositive(accountWorldParticipationFence, "accountWorldParticipationFence");
    this.gameSessionOwnerAttemptId =
        requireNonNil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    this.gameSessionOwnerFence = requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
    this.worldExecutionFence = requirePositive(worldExecutionFence, "worldExecutionFence");

    this.targetNamespace = requireNamespace(targetNamespace);
    this.canonicalTenantId = requireNonNil(canonicalTenantId, "canonicalTenantId");
    this.controlPlaneRequestId =
        Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId");
    this.canonicalGameInstanceId =
        requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    this.preparationInputDigest = requireDigest(preparationInputDigest);
    this.outcome = Objects.requireNonNull(outcome, "outcome");
    this.originalPostAuthorizationTupleBase64 =
        Base64.getEncoder().encodeToString(this.originalPostAuthorizationTuple);

    var original = originalTuple.preAuthorizationTuple();
    if (!this.targetNamespace.equals(original.action().scope().targetNamespace())
        || !this.canonicalTenantId.equals(original.action().scope().tenantId())
        || !this.controlPlaneRequestId.equals(originalTuple.controlPlaneRequestId())) {
      throw invalid("World terminal scope differs from the original StartSession tuple");
    }

    requireCanonicalSize();
    byte[] preparationBytes = strictUtf8Bytes(this.preparationInputJson);
    if (!sha256Prefixed(preparationBytes).equals(this.preparationInputDigest)) {
      throw invalid("preparationInputDigest does not match the exact retained preparation input");
    }
    this.canonicalBytes = encodeCanonicalBytes();
    this.digest = sha256Prefixed(this.canonicalBytes);
  }

  /** Returns a defensive copy of the exact original post-authorization tuple bytes. */
  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
  }

  public String originalPostAuthorizationTupleBase64() {
    return originalPostAuthorizationTupleBase64;
  }

  public UUID accountWorldParticipationId() {
    return accountWorldParticipationId;
  }

  public long accountWorldParticipationFence() {
    return accountWorldParticipationFence;
  }

  public UUID gameSessionOwnerAttemptId() {
    return gameSessionOwnerAttemptId;
  }

  public long gameSessionOwnerFence() {
    return gameSessionOwnerFence;
  }

  public String targetNamespace() {
    return targetNamespace;
  }

  public UUID canonicalTenantId() {
    return canonicalTenantId;
  }

  public String controlPlaneRequestId() {
    return controlPlaneRequestId;
  }

  public UUID canonicalGameInstanceId() {
    return canonicalGameInstanceId;
  }

  public String preparationInputDigest() {
    return preparationInputDigest;
  }

  /** Returns the original preparation-input string without parsing or reformatting it. */
  public String preparationInputJson() {
    return preparationInputJson;
  }

  public long worldExecutionFence() {
    return worldExecutionFence;
  }

  public Outcome outcome() {
    return outcome;
  }

  /** Returns a defensive copy of the closed RFC 8785 UTF-8 representation. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** SHA-256 digest of the complete canonical terminal, in the shared prefixed form. */
  public String digest() {
    return digest;
  }

  /** Strictly decodes one complete, closed, canonical World terminal value. */
  public static WorldStartSessionExecutionTerminal fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0 || stored.length > MAX_CANONICAL_BYTES) {
      throw invalid("Canonical World StartSession terminal exceeds its byte limit");
    }
    try {
      String json = strictUtf8(stored);
      byte[] canonicalInput = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      if (!Arrays.equals(stored, canonicalInput)) {
        throw invalid("Canonical World StartSession terminal is not RFC 8785 JSON");
      }

      JsonNode root = JSON.readTree(json);
      requireExactFields(root);
      if (!SCHEMA.equals(text(root, "schema"))) {
        throw invalid("Unsupported World StartSession terminal schema");
      }

      WorldStartSessionExecutionTerminal terminal =
          new WorldStartSessionExecutionTerminal(
              decodeTuple(root),
              canonicalUuid(root, "accountWorldParticipationId"),
              positiveLong(root, "accountWorldParticipationFence"),
              canonicalUuid(root, "gameSessionOwnerAttemptId"),
              positiveLong(root, "gameSessionOwnerFence"),
              text(root, "targetNamespace"),
              canonicalUuid(root, "canonicalTenantId"),
              text(root, "controlPlaneRequestId"),
              canonicalUuid(root, "canonicalGameInstanceId"),
              text(root, "preparationInputDigest"),
              text(root, "preparationInputJson"),
              positiveLong(root, "worldExecutionFence"),
              parseOutcome(text(root, "outcome")));
      if (!Arrays.equals(stored, terminal.canonicalBytes)) {
        throw invalid("Canonical World StartSession terminal has a noncanonical re-encoding");
      }
      return terminal;
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Canonical World StartSession terminal is invalid", malformed);
    }
  }

  private byte[] encodeCanonicalBytes() {
    byte[] encoded = encodeCanonical(canonicalValue(preparationInputJson));
    if (encoded.length > MAX_CANONICAL_BYTES) {
      throw invalid("Canonical World StartSession terminal exceeds its byte limit");
    }
    return encoded;
  }

  private void requireCanonicalSize() {
    byte[] emptyStringPreimage = encodeCanonical(canonicalValue(""));
    long expectedLength =
        emptyStringPreimage.length + jsonStringValueUtf8Length(preparationInputJson);
    if (expectedLength > MAX_CANONICAL_BYTES) {
      throw invalid("Canonical World StartSession terminal exceeds its byte limit");
    }
  }

  private Map<String, Object> canonicalValue(String exactPreparationInputJson) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("originalPostAuthorizationTupleBase64", originalPostAuthorizationTupleBase64);
    value.put("accountWorldParticipationId", accountWorldParticipationId.toString());
    value.put("accountWorldParticipationFence", Long.toString(accountWorldParticipationFence));
    value.put("gameSessionOwnerAttemptId", gameSessionOwnerAttemptId.toString());
    value.put("gameSessionOwnerFence", Long.toString(gameSessionOwnerFence));
    value.put("targetNamespace", targetNamespace);
    value.put("canonicalTenantId", canonicalTenantId.toString());
    value.put("controlPlaneRequestId", controlPlaneRequestId);
    value.put("canonicalGameInstanceId", canonicalGameInstanceId.toString());
    value.put("preparationInputDigest", preparationInputDigest);
    value.put("preparationInputJson", exactPreparationInputJson);
    value.put("worldExecutionFence", Long.toString(worldExecutionFence));
    value.put("outcome", outcome.name());
    return value;
  }

  private static byte[] encodeCanonical(Map<String, Object> value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException("World StartSession terminal cannot be encoded", impossible);
    }
  }

  private static long jsonStringValueUtf8Length(String value) {
    long length = 0L;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '\b'
          || current == '\t'
          || current == '\n'
          || current == '\f'
          || current == '\r'
          || current == '"'
          || current == '\\') {
        length += 2L;
      } else if (current <= 0x1f) {
        length += 6L;
      } else if (current <= 0x7f) {
        length++;
      } else if (current <= 0x7ff) {
        length += 2L;
      } else if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw invalid("preparationInputJson is not valid Unicode");
        }
        length += 4L;
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw invalid("preparationInputJson is not valid Unicode");
      } else {
        length += 3L;
      }
      if (length > MAX_CANONICAL_BYTES) {
        throw invalid("Canonical World StartSession terminal exceeds its byte limit");
      }
    }
    return length;
  }

  private static byte[] strictUtf8Bytes(String value) {
    jsonStringValueUtf8Length(value);
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw invalid("preparationInputJson is not valid UTF-8", malformed);
    }
  }

  private static byte[] decodeTuple(JsonNode root) {
    String encoded = text(root, "originalPostAuthorizationTupleBase64");
    if (encoded.isEmpty() || encoded.length() > MAX_TUPLE_BASE64_CHARS) {
      throw invalid("Original post-authorization tuple exceeds its byte limit");
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length == 0
          || decoded.length > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
          || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
        throw invalid("Original post-authorization tuple must use canonical base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid("Original post-authorization tuple must use canonical base64", malformed);
    }
  }

  private static void requireStartSessionGameSessionTarget(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    var original = tuple.preAuthorizationTuple();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(original.actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(original.targetOwner())) {
      throw invalid("World terminal requires the original Game Session StartSession tuple");
    }
  }

  private static String requireNamespace(String value) {
    Objects.requireNonNull(value, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(value)) {
      throw invalid("targetNamespace must be one canonical DNS label");
    }
    return value;
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw invalid(field + " must not be nil");
    }
    return value;
  }

  private static long requirePositive(long value, String field) {
    if (value <= 0L) {
      throw invalid(field + " must be a positive signed 64-bit integer");
    }
    return value;
  }

  private static String requireDigest(String value) {
    Objects.requireNonNull(value, "preparationInputDigest");
    if (!SHA256.matcher(value).matches()) {
      throw invalid("preparationInputDigest must be sha256: followed by lowercase hexadecimal");
    }
    return value;
  }

  private static String requirePreparationInput(String value) {
    Objects.requireNonNull(value, "preparationInputJson");
    if (value.isBlank()) {
      throw invalid("preparationInputJson must retain a nonblank exact input");
    }
    if (value.length() > MAX_CANONICAL_BYTES) {
      throw invalid("preparationInputJson exceeds the terminal byte limit");
    }
    return value;
  }

  private static String strictUtf8(byte[] value) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(value))
          .toString();
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw invalid("Canonical World StartSession terminal is not strict UTF-8", malformed);
    }
  }

  private static void requireExactFields(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw invalid("Canonical World StartSession terminal must be a JSON object");
    }
    Set<String> actual = new HashSet<>();
    root.propertyNames().forEach(actual::add);
    if (!FIELDS.equals(actual)) {
      throw invalid("Canonical World StartSession terminal has missing or unsupported fields");
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root == null ? null : root.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(field + " must be a string");
    }
    return value.textValue();
  }

  private static UUID canonicalUuid(JsonNode root, String field) {
    String value = text(root, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(NIL_UUID)) {
        throw invalid(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static long positiveLong(JsonNode root, String field) {
    String value = text(root, field);
    if (!CANONICAL_POSITIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(field + " must be a canonical positive ASCII decimal string");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw invalid(field + " exceeds the positive signed 64-bit range", overflow);
    }
  }

  private static Outcome parseOutcome(String value) {
    try {
      return Outcome.valueOf(value);
    } catch (IllegalArgumentException unsupported) {
      throw invalid(
          "World StartSession terminal outcome must be COMMITTED or ABORTED", unsupported);
    }
  }

  private static String sha256Prefixed(byte[] bytes) {
    return "sha256:" + sha256(bytes);
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }

  public enum Outcome {
    COMMITTED,
    ABORTED
  }
}
