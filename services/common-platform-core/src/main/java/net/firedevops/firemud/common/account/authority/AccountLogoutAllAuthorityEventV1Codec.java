package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private static final ObjectMapper JSON = StrictAuthorityEventSupport.strictJsonMapper();

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
    if (!StrictAuthorityEventSupport.isCanonicalSha256Digest(suppliedDigest)) {
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

    StrictAuthorityEventSupport.validateAccountSecurityCutoff(
        event.get("accountSecurityCutoff"),
        accountAuthorityGeneration,
        streamKey,
        outboxSequence,
        ACCOUNT_CUTOFF_FIELDS,
        AccountLogoutAllAuthorityEventV1Codec::invalid);
  }

  private static AccountLogoutAllAuthorityEvent toEvidence(
      ObjectNode wire, String digest, String canonicalJson) {
    var cutoff =
        StrictAuthorityEventSupport.accountSecurityCutoffFields(
            (ObjectNode) wire.get("accountSecurityCutoff"));
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
            cutoff.accountAuthorityGeneration(), cutoff.outboxStreamKey(), cutoff.outboxSequence()),
        digest,
        canonicalJson);
  }

  private static void requireExactFields(ObjectNode object, Set<String> required, String path) {
    StrictAuthorityEventSupport.requireExactFields(
        object, required, path, AccountLogoutAllAuthorityEventV1Codec::invalid);
  }

  private static String requireExactText(
      ObjectNode object, String field, String expected, String path) {
    return StrictAuthorityEventSupport.requireExactText(
        object, field, expected, path, AccountLogoutAllAuthorityEventV1Codec::invalid);
  }

  private static String requireText(ObjectNode object, String field, String path) {
    return StrictAuthorityEventSupport.requireText(
        object, field, path, AccountLogoutAllAuthorityEventV1Codec::invalid);
  }

  private static String requireUuid(ObjectNode object, String field, String path) {
    return StrictAuthorityEventSupport.requireUuid(
        object, field, path, AccountLogoutAllAuthorityEventV1Codec::invalid);
  }

  private static String requirePositiveDecimal(ObjectNode object, String field, String path) {
    return StrictAuthorityEventSupport.requirePositiveDecimal(
        object, field, path, AccountLogoutAllAuthorityEventV1Codec::invalid);
  }

  private static String digest(ObjectNode preimage) {
    return StrictAuthorityEventSupport.digest(preimage, "event preimage cannot be canonicalized");
  }

  private static String canonicalJson(ObjectNode wire) {
    return StrictAuthorityEventSupport.canonicalJson(wire, "event cannot be canonicalized");
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
