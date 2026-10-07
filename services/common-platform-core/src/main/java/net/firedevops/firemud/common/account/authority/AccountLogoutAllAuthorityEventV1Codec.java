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

/** Strict codec for committed Account logout-all authority events. */
public final class AccountLogoutAllAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-logout-all-event/v1";
  public static final String EVENT_TYPE = "LOGOUT_ALL_COMMITTED";
  public static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:";
  public static final String EVENT_ID_PREFIX = "account-logout-all-event-v1:";

  private static final Set<String> PREIMAGE_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "accountId",
          "sourceScope",
          "outboxStreamKey",
          "outboxSequence",
          "accountAuthorityGeneration",
          "sourceVersion",
          "accountSecurityCutoff");
  private static final Set<String> WIRE_FIELDS;
  private static final Set<String> ACCOUNT_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
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

  private AccountLogoutAllAuthorityEventV1Codec() {}

  /** Validates a preimage and returns immutable evidence sealed with its canonical digest. */
  public static AccountLogoutAllAuthorityEvent seal(Map<String, ?> preimage) {
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
  public static AccountLogoutAllAuthorityEvent verify(String wireJson) {
    Objects.requireNonNull(wireJson, "wireJson must not be null");
    final JsonNode tree;
    try {
      // Canonicalization rejects duplicate JSON object properties before Jackson builds a tree.
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

  private static AccountLogoutAllAuthorityEvent verifyWireNode(ObjectNode wire) {
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

    String requestId = requireUuid(event, "requestId", "event");
    requireExactText(event, "eventId", EVENT_ID_PREFIX + requestId, "event");
    String accountId = requireUuid(event, "accountId", "event");
    String sourceScope = "account/" + accountId;
    String streamKey = EVENT_STREAM_PREFIX + sourceScope;
    requireExactText(event, "sourceScope", sourceScope, "event");
    requireExactText(event, "outboxStreamKey", streamKey, "event");

    String outboxSequence = requirePositiveDecimal(event, "outboxSequence", "event");
    String accountAuthorityGeneration =
        requirePositiveDecimal(event, "accountAuthorityGeneration", "event");
    requirePositiveDecimal(event, "sourceVersion", "event");

    JsonNode cutoffNode = event.get("accountSecurityCutoff");
    if (!(cutoffNode instanceof ObjectNode cutoff)) {
      throw invalid("accountSecurityCutoff", "must be a required JSON object");
    }
    requireExactFields(cutoff, ACCOUNT_CUTOFF_FIELDS, "accountSecurityCutoff");
    String cutoffGeneration =
        requirePositiveDecimal(cutoff, "accountAuthorityGeneration", "accountSecurityCutoff");
    String cutoffStream = requireText(cutoff, "outboxStreamKey", "accountSecurityCutoff");
    String cutoffSequence =
        requirePositiveDecimal(cutoff, "outboxSequence", "accountSecurityCutoff");
    if (!accountAuthorityGeneration.equals(cutoffGeneration)) {
      throw invalid(
          "accountSecurityCutoff.accountAuthorityGeneration",
          "must equal event.accountAuthorityGeneration");
    }
    if (!streamKey.equals(cutoffStream)) {
      throw invalid("accountSecurityCutoff.outboxStreamKey", "must equal event.outboxStreamKey");
    }
    if (!outboxSequence.equals(cutoffSequence)) {
      throw invalid("accountSecurityCutoff.outboxSequence", "must equal event.outboxSequence");
    }
  }

  private static AccountLogoutAllAuthorityEvent toEvidence(
      ObjectNode wire, String digest, String canonicalJson) {
    ObjectNode cutoff = (ObjectNode) wire.get("accountSecurityCutoff");
    return new AccountLogoutAllAuthorityEvent(
        wire.path("schemaVersion").textValue(),
        wire.path("eventType").textValue(),
        wire.path("eventId").textValue(),
        wire.path("requestId").textValue(),
        wire.path("accountId").textValue(),
        wire.path("sourceScope").textValue(),
        wire.path("outboxStreamKey").textValue(),
        wire.path("outboxSequence").textValue(),
        wire.path("accountAuthorityGeneration").textValue(),
        wire.path("sourceVersion").textValue(),
        new AccountSecurityCutoff(
            cutoff.path("accountAuthorityGeneration").textValue(),
            cutoff.path("outboxStreamKey").textValue(),
            cutoff.path("outboxSequence").textValue()),
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

  private static String requireText(ObjectNode object, String field, String path) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(path + "." + field, "must be a required string");
    }
    return value.textValue();
  }

  private static String requireUuid(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (!UUID_PATTERN.matcher(value).matches() || NIL_UUID.equals(value)) {
      throw invalid(path + "." + field, "must be a canonical lowercase non-nil UUID");
    }
    return value;
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

  /** Immutable verified or producer-sealed logout-all event evidence. */
  public static final class AccountLogoutAllAuthorityEvent {
    private final String schemaVersion;
    private final String eventType;
    private final String eventId;
    private final String requestId;
    private final String accountId;
    private final String sourceScope;
    private final String outboxStreamKey;
    private final String outboxSequence;
    private final String accountAuthorityGeneration;
    private final String sourceVersion;
    private final AccountSecurityCutoff accountSecurityCutoff;
    private final String eventDigest;
    private final String canonicalJson;

    private AccountLogoutAllAuthorityEvent(
        String schemaVersion,
        String eventType,
        String eventId,
        String requestId,
        String accountId,
        String sourceScope,
        String outboxStreamKey,
        String outboxSequence,
        String accountAuthorityGeneration,
        String sourceVersion,
        AccountSecurityCutoff accountSecurityCutoff,
        String eventDigest,
        String canonicalJson) {
      this.schemaVersion = schemaVersion;
      this.eventType = eventType;
      this.eventId = eventId;
      this.requestId = requestId;
      this.accountId = accountId;
      this.sourceScope = sourceScope;
      this.outboxStreamKey = outboxStreamKey;
      this.outboxSequence = outboxSequence;
      this.accountAuthorityGeneration = accountAuthorityGeneration;
      this.sourceVersion = sourceVersion;
      this.accountSecurityCutoff = accountSecurityCutoff;
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

    public String accountId() {
      return accountId;
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

    public String accountAuthorityGeneration() {
      return accountAuthorityGeneration;
    }

    public String sourceVersion() {
      return sourceVersion;
    }

    public AccountSecurityCutoff accountSecurityCutoff() {
      return accountSecurityCutoff;
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

  /** Immutable Account security cutoff checkpoint. */
  public record AccountSecurityCutoff(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {}
}
