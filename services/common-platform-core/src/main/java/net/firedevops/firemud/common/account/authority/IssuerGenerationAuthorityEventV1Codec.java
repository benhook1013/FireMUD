package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Strict codec for committed Account issuer-generation authority events. */
public final class IssuerGenerationAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-issuer-generation-event/v1";
  public static final String EVENT_TYPE = "ISSUER_GENERATION_ADVANCED";
  public static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:";

  private static final Set<String> PREIMAGE_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "issuerId",
          "sourceScope",
          "outboxStreamKey",
          "outboxSequence",
          "issuerAuthGeneration",
          "sourceVersion");
  private static final Set<String> WIRE_FIELDS;
  private static final Pattern POSITIVE_DECIMAL_PATTERN = Pattern.compile("[1-9][0-9]*");
  private static final Pattern DIGEST_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static {
    var wireFields = new java.util.HashSet<>(PREIMAGE_FIELDS);
    wireFields.add("eventDigest");
    WIRE_FIELDS = Set.copyOf(wireFields);
  }

  private IssuerGenerationAuthorityEventV1Codec() {}

  /** Validates a preimage and returns immutable evidence sealed with its canonical digest. */
  public static IssuerGenerationAuthorityEvent seal(Map<String, ?> preimage) {
    Objects.requireNonNull(preimage, "preimage must not be null");
    JsonNode tree = JSON.valueToTree(preimage);
    if (!(tree instanceof ObjectNode object)) {
      throw invalid("event", "must be a JSON object");
    }
    validatePreimage(object);
    String digest = digest(object);
    ObjectNode wire = object.deepCopy();
    wire.put("eventDigest", digest);
    return toEvidence(wire, digest, canonicalJson(wire));
  }

  /** Validates and verifies one wire event from JSON, rejecting duplicate properties. */
  public static IssuerGenerationAuthorityEvent verify(String wireJson) {
    Objects.requireNonNull(wireJson, "wireJson must not be null");
    final JsonNode tree;
    try {
      // JCS parsing rejects duplicate JSON object properties before Jackson builds a tree.
      Rfc8785CanonicalJson.canonicalizeUtf8(wireJson);
      tree = JSON.readTree(wireJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event JSON is malformed", exception);
    }
    if (!(tree instanceof ObjectNode wire)) {
      throw invalid("event", "must be a JSON object");
    }
    return verifyWireNode(wire);
  }

  /** Validates and verifies one wire event supplied as JSON-compatible map data. */
  public static IssuerGenerationAuthorityEvent verify(Map<String, ?> wireEvent) {
    Objects.requireNonNull(wireEvent, "wireEvent must not be null");
    JsonNode tree = JSON.valueToTree(wireEvent);
    if (!(tree instanceof ObjectNode wire)) {
      throw invalid("event", "must be a JSON object");
    }
    return verifyWireNode(wire);
  }

  private static IssuerGenerationAuthorityEvent verifyWireNode(ObjectNode wire) {
    requireExactFields(wire, WIRE_FIELDS, "event");
    String suppliedDigest = requireText(wire, "eventDigest", "event");
    if (!DIGEST_PATTERN.matcher(suppliedDigest).matches()) {
      throw invalid("eventDigest", "must be lowercase sha256: followed by 64 lowercase hex digits");
    }
    ObjectNode preimage = wire.deepCopy();
    preimage.remove("eventDigest");
    validatePreimage(preimage);
    String expectedDigest = digest(preimage);
    if (!MessageDigest.isEqual(
        suppliedDigest.getBytes(StandardCharsets.US_ASCII),
        expectedDigest.getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("eventDigest", "does not match the canonical event preimage");
    }
    return toEvidence(wire, suppliedDigest, canonicalJson(wire));
  }

  private static void validatePreimage(ObjectNode event) {
    requireExactFields(event, PREIMAGE_FIELDS, "event preimage");
    requireExactText(event, "schemaVersion", SCHEMA_VERSION, "event");
    requireExactText(event, "eventType", EVENT_TYPE, "event");
    requireNonEmptyText(event, "eventId", "event");
    requireNonEmptyText(event, "requestId", "event");

    String issuerId = requireNonBlankText(event, "issuerId", "event");
    String sourceScope = "issuer/" + issuerId;
    String streamKey = EVENT_STREAM_PREFIX + sourceScope;
    requireExactText(event, "sourceScope", sourceScope, "event");
    requireExactText(event, "outboxStreamKey", streamKey, "event");

    requirePositiveDecimal(event, "outboxSequence", "event");
    requirePositiveDecimal(event, "issuerAuthGeneration", "event");
    requirePositiveDecimal(event, "sourceVersion", "event");
  }

  private static IssuerGenerationAuthorityEvent toEvidence(
      ObjectNode wire, String digest, String canonicalJson) {
    return new IssuerGenerationAuthorityEvent(
        wire.path("schemaVersion").textValue(),
        wire.path("eventType").textValue(),
        wire.path("eventId").textValue(),
        wire.path("requestId").textValue(),
        wire.path("issuerId").textValue(),
        wire.path("sourceScope").textValue(),
        wire.path("outboxStreamKey").textValue(),
        wire.path("outboxSequence").textValue(),
        wire.path("issuerAuthGeneration").textValue(),
        wire.path("sourceVersion").textValue(),
        digest,
        canonicalJson);
  }

  private static void requireExactFields(ObjectNode object, Set<String> required, String path) {
    Set<String> actual = new java.util.HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(required)) {
      Set<String> missing = new java.util.HashSet<>(required);
      missing.removeAll(actual);
      Set<String> unexpected = new java.util.HashSet<>(actual);
      unexpected.removeAll(required);
      StringBuilder message = new StringBuilder("must contain exactly the declared fields");
      if (!missing.isEmpty()) {
        message.append("; missing ").append(missing);
      }
      if (!unexpected.isEmpty()) {
        message.append("; unexpected ").append(unexpected);
      }
      throw invalid(path, message.toString());
    }
  }

  private static String requireExactText(
      ObjectNode object, String field, String expected, String path) {
    String value = requireText(object, field, path);
    if (!expected.equals(value)) {
      throw invalid(path + "." + field, "must equal " + expected);
    }
    return value;
  }

  private static String requireNonEmptyText(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (value.isEmpty()) {
      throw invalid(path + "." + field, "must be nonempty");
    }
    return value;
  }

  private static String requireNonBlankText(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (value.isBlank()) {
      throw invalid(path + "." + field, "must be nonblank");
    }
    return value;
  }

  private static String requireText(ObjectNode object, String field, String path) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(path + "." + field, "must be a required string");
    }
    return value.textValue();
  }

  private static String requirePositiveDecimal(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (!POSITIVE_DECIMAL_PATTERN.matcher(value).matches()) {
      throw invalid(path + "." + field, "must be a positive canonical decimal string");
    }
    return value;
  }

  private static String digest(ObjectNode preimage) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(preimage.toString());
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical);
      return "sha256:" + java.util.HexFormat.of().formatHex(hash);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event preimage cannot be canonicalized", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String canonicalJson(ObjectNode wire) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(wire.toString()), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event cannot be canonicalized", exception);
    }
  }

  private static IllegalArgumentException invalid(String path, String message) {
    return new IllegalArgumentException(path + " " + message);
  }

  /** Immutable verified or producer-sealed issuer-generation event evidence. */
  public static final class IssuerGenerationAuthorityEvent {
    private final String schemaVersion;
    private final String eventType;
    private final String eventId;
    private final String requestId;
    private final String issuerId;
    private final String sourceScope;
    private final String outboxStreamKey;
    private final String outboxSequence;
    private final String issuerAuthGeneration;
    private final String sourceVersion;
    private final String eventDigest;
    private final String canonicalJson;

    private IssuerGenerationAuthorityEvent(
        String schemaVersion,
        String eventType,
        String eventId,
        String requestId,
        String issuerId,
        String sourceScope,
        String outboxStreamKey,
        String outboxSequence,
        String issuerAuthGeneration,
        String sourceVersion,
        String eventDigest,
        String canonicalJson) {
      this.schemaVersion = schemaVersion;
      this.eventType = eventType;
      this.eventId = eventId;
      this.requestId = requestId;
      this.issuerId = issuerId;
      this.sourceScope = sourceScope;
      this.outboxStreamKey = outboxStreamKey;
      this.outboxSequence = outboxSequence;
      this.issuerAuthGeneration = issuerAuthGeneration;
      this.sourceVersion = sourceVersion;
      this.eventDigest = eventDigest;
      this.canonicalJson = canonicalJson;
    }

    public String schemaVersion() {
      return schemaVersion;
    }

    public String eventType() {
      return eventType;
    }

    public String eventId() {
      return eventId;
    }

    public String requestId() {
      return requestId;
    }

    public String issuerId() {
      return issuerId;
    }

    public String sourceScope() {
      return sourceScope;
    }

    public String outboxStreamKey() {
      return outboxStreamKey;
    }

    public String outboxSequence() {
      return outboxSequence;
    }

    public String issuerAuthGeneration() {
      return issuerAuthGeneration;
    }

    public String sourceVersion() {
      return sourceVersion;
    }

    public String eventDigest() {
      return eventDigest;
    }

    public String canonicalJson() {
      return canonicalJson;
    }

    /** Returns a caller-owned copy of the canonical complete wire event bytes. */
    public byte[] canonicalJsonUtf8() {
      return canonicalJson.getBytes(StandardCharsets.UTF_8);
    }
  }
}
