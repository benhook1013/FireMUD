package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Producer codec for canonical Account lifecycle-bound realm-grant authority events.
 *
 * <p>This seals and verifies immutable event data only. It establishes no source authority, actor
 * qualification, event-consumer activation, or gameplay admission.
 */
public final class RealmGrantAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-authority-event/v1";
  public static final String EVENT_TYPE = "REALM_GRANT_CHANGED";
  public static final int MAX_EVENT_PAYLOAD_BYTES = 32 * 1024;

  private static final String EVENT_ID_PREFIX = "realm-grant-event:v1:";
  private static final String LONG_MAX = "9223372036854775807";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int MAX_AUDIT_REASON_UTF8_BYTES = 4096;
  private static final int MAX_INSTANT_TEXT_LENGTH = 40;
  private static final Pattern MUTATION_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> GRANT_STATES = Set.of("ACTIVE", "REVOKED");
  private static final Set<String> MUTATION_KINDS = Set.of("CREATE", "EXTEND", "REVOKE", "REGRANT");
  private static final String[] WIRE_FIELD_NAMES = {
    "accountId",
    "actorAccountId",
    "auditReason",
    "eventDigest",
    "eventId",
    "eventType",
    "grantAuthorityGeneration",
    "grantExpiresAt",
    "grantRevision",
    "grantState",
    "mutationDigest",
    "mutationKind",
    "outboxSequence",
    "outboxStreamKey",
    "playtestLifecycleId",
    "realmSlug",
    "requestId",
    "schemaVersion",
    "sourceScope",
    "tenantId",
    "worldSlug"
  };
  private static final String[] PREIMAGE_FIELD_NAMES = {
    "schemaVersion",
    "eventType",
    "eventId",
    "requestId",
    "outboxStreamKey",
    "outboxSequence",
    "sourceScope",
    "accountId",
    "tenantId",
    "worldSlug",
    "realmSlug",
    "playtestLifecycleId",
    "grantRevision",
    "grantAuthorityGeneration",
    "grantState",
    "grantExpiresAt",
    "actorAccountId",
    "mutationKind",
    "mutationDigest",
    "auditReason"
  };
  private static final Set<String> PREIMAGE_FIELDS = Set.of(PREIMAGE_FIELD_NAMES);
  private static final Set<String> WIRE_FIELDS;
  private static final int WIRE_FIELD_NAME_BYTES = fieldNameBytes();
  private static final ObjectMapper JSON = StrictAuthorityEventSupport.strictJsonMapper();

  static {
    var fields = new java.util.HashSet<>(PREIMAGE_FIELDS);
    fields.add("eventDigest");
    WIRE_FIELDS = Set.copyOf(fields);
  }

  private RealmGrantAuthorityEventV1Codec() {}

  /** Seals one already-authorized Account grant mutation as canonical immutable event data. */
  public static Event seal(GrantPreimage preimage) {
    if (preimage == null) {
      throw invalid("preimage is required");
    }
    String eventId = eventId(preimage.requestId());
    String sourceScope =
        GrantAuthorityScopeCodec.sourceScope(preimage.grant(), GrantAuthorityScopeCodec.V1_BOUNDS);
    String outboxStreamKey =
        GrantAuthorityScopeCodec.outboxStreamKey(
            preimage.grant(), GrantAuthorityScopeCodec.V1_BOUNDS);
    requireSealPayloadWithinBound(preimage, eventId, sourceScope, outboxStreamKey);

    ObjectNode canonicalPreimage = preimageNode(preimage, eventId, sourceScope, outboxStreamKey);
    String digest =
        StrictAuthorityEventSupport.digest(canonicalPreimage, "grant event digest failed");
    ObjectNode wire = canonicalPreimage.deepCopy();
    wire.put("eventDigest", digest);
    byte[] payload =
        utf8(StrictAuthorityEventSupport.canonicalJson(wire, "grant event encoding failed"));
    if (payload.length > MAX_EVENT_PAYLOAD_BYTES) {
      throw invalid("event payload exceeds 32 KiB");
    }
    byte[] preimageBytes =
        utf8(
            StrictAuthorityEventSupport.canonicalJson(
                canonicalPreimage, "grant preimage encoding failed"));
    return new Event(preimage, eventId, digest, preimageBytes, payload);
  }

  /** Verifies one bounded canonical wire event and returns immutable typed evidence. */
  public static Event verify(byte[] payload) {
    if (payload == null || payload.length == 0 || payload.length > MAX_EVENT_PAYLOAD_BYTES) {
      throw invalid("event payload must be nonempty and at most 32 KiB");
    }

    String wireJson = decodeUtf8(payload);
    final JsonNode tree;
    try {
      tree = JSON.readTree(wireJson);
    } catch (IOException malformed) {
      throw invalid("event JSON is malformed", malformed);
    }
    if (!(tree instanceof ObjectNode wire)) {
      throw invalid("event must be a JSON object");
    }
    StrictAuthorityEventSupport.requireExactFields(
        wire, WIRE_FIELDS, "event", RealmGrantAuthorityEventV1Codec::invalid);

    String suppliedDigest =
        StrictAuthorityEventSupport.requireText(
            wire, "eventDigest", "event", RealmGrantAuthorityEventV1Codec::invalid);
    if (!StrictAuthorityEventSupport.isCanonicalSha256Digest(suppliedDigest)) {
      throw invalid("eventDigest must be sha256: followed by 64 lowercase hex digits");
    }

    ObjectNode preimageNode = wire.deepCopy();
    preimageNode.remove("eventDigest");
    GrantPreimage preimage = parsePreimage(preimageNode);
    String expectedDigest =
        StrictAuthorityEventSupport.digest(preimageNode, "grant event digest verification failed");
    if (!MessageDigest.isEqual(
        suppliedDigest.getBytes(StandardCharsets.US_ASCII),
        expectedDigest.getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("eventDigest does not match the canonical grant event preimage");
    }

    byte[] canonicalWire =
        utf8(StrictAuthorityEventSupport.canonicalJson(wire, "grant event encoding failed"));
    if (!Arrays.equals(payload, canonicalWire)) {
      throw invalid("event payload is not the exact RFC 8785 canonical byte encoding");
    }
    byte[] canonicalPreimage =
        utf8(
            StrictAuthorityEventSupport.canonicalJson(
                preimageNode, "grant preimage encoding failed"));
    return new Event(
        preimage, eventId(preimage.requestId()), suppliedDigest, canonicalPreimage, canonicalWire);
  }

  private static ObjectNode preimageNode(
      GrantPreimage preimage, String eventId, String sourceScope, String outboxStreamKey) {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("eventType", EVENT_TYPE);
    object.put("eventId", eventId);
    object.put("requestId", preimage.requestId().toString());
    object.put("outboxStreamKey", outboxStreamKey);
    object.put("outboxSequence", Long.toString(preimage.outboxSequence()));
    object.put("sourceScope", sourceScope);
    object.put("accountId", preimage.grant().accountId().toString());
    object.put("tenantId", preimage.grant().tenantId().toString());
    object.put("worldSlug", preimage.grant().worldSlug());
    object.put("realmSlug", preimage.grant().realmSlug());
    object.put("playtestLifecycleId", preimage.grant().playtestLifecycleId().toString());
    object.put("grantRevision", Long.toString(preimage.grantRevision()));
    object.put("grantAuthorityGeneration", preimage.grantAuthorityGeneration().toString());
    object.put("grantState", preimage.grantState());
    object.put("grantExpiresAt", preimage.grantExpiresAt().toString());
    object.put("actorAccountId", preimage.actorAccountId().toString());
    object.put("mutationKind", preimage.mutationKind());
    object.put("mutationDigest", preimage.mutationDigest());
    object.put("auditReason", preimage.auditReason());
    return object;
  }

  private static GrantPreimage parsePreimage(ObjectNode object) {
    StrictAuthorityEventSupport.requireExactFields(
        object, PREIMAGE_FIELDS, "event preimage", RealmGrantAuthorityEventV1Codec::invalid);
    StrictAuthorityEventSupport.requireExactText(
        object, "schemaVersion", SCHEMA_VERSION, "event", RealmGrantAuthorityEventV1Codec::invalid);
    StrictAuthorityEventSupport.requireExactText(
        object, "eventType", EVENT_TYPE, "event", RealmGrantAuthorityEventV1Codec::invalid);

    UUID requestId = requireUuid(object, "requestId");
    StrictAuthorityEventSupport.requireExactText(
        object, "eventId", eventId(requestId), "event", RealmGrantAuthorityEventV1Codec::invalid);

    UUID accountId = requireUuid(object, "accountId");
    UUID tenantId = requireUuid(object, "tenantId");
    String worldSlug = text(object, "worldSlug");
    String realmSlug = text(object, "realmSlug");
    UUID playtestLifecycleId = requireUuid(object, "playtestLifecycleId");
    GrantAuthorityScopeCodec.Scope grant =
        new GrantAuthorityScopeCodec.Scope(
            accountId, tenantId, worldSlug, realmSlug, playtestLifecycleId);
    String expectedSourceScope =
        GrantAuthorityScopeCodec.sourceScope(grant, GrantAuthorityScopeCodec.V1_BOUNDS);
    String expectedStreamKey =
        GrantAuthorityScopeCodec.outboxStreamKey(grant, GrantAuthorityScopeCodec.V1_BOUNDS);
    StrictAuthorityEventSupport.requireExactText(
        object,
        "sourceScope",
        expectedSourceScope,
        "event",
        RealmGrantAuthorityEventV1Codec::invalid);
    StrictAuthorityEventSupport.requireExactText(
        object,
        "outboxStreamKey",
        expectedStreamKey,
        "event",
        RealmGrantAuthorityEventV1Codec::invalid);

    long outboxSequence = positiveLong(object, "outboxSequence");
    long grantRevision = positiveLong(object, "grantRevision");
    UUID grantAuthorityGeneration = requireUuid(object, "grantAuthorityGeneration");
    String grantState = text(object, "grantState");
    Instant grantExpiresAt = instant(object, "grantExpiresAt");
    UUID actorAccountId = requireUuid(object, "actorAccountId");
    String mutationKind = text(object, "mutationKind");
    String mutationDigest = text(object, "mutationDigest");
    String auditReason = text(object, "auditReason");

    return new GrantPreimage(
        requestId,
        grant,
        grantRevision,
        grantAuthorityGeneration,
        grantState,
        grantExpiresAt,
        actorAccountId,
        mutationKind,
        mutationDigest,
        auditReason,
        outboxSequence);
  }

  private static UUID requireUuid(ObjectNode object, String field) {
    String value =
        StrictAuthorityEventSupport.requireUuid(
            object, field, "event", RealmGrantAuthorityEventV1Codec::invalid);
    return UUID.fromString(value);
  }

  private static long positiveLong(ObjectNode object, String field) {
    String value =
        StrictAuthorityEventSupport.requirePositiveDecimal(
            object, field, "event", RealmGrantAuthorityEventV1Codec::invalid);
    if (value.length() > LONG_MAX.length()
        || (value.length() == LONG_MAX.length() && value.compareTo(LONG_MAX) > 0)) {
      throw invalid("event." + field + " exceeds the positive signed-long owner range");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw invalid("event." + field + " must be a positive canonical long");
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalid("event." + field + " exceeds the positive signed-long owner range", malformed);
    }
  }

  private static Instant instant(ObjectNode object, String field) {
    String value = text(object, field);
    if (value.length() > MAX_INSTANT_TEXT_LENGTH) {
      throw invalid("event." + field + " exceeds the canonical UTC text maximum");
    }
    try {
      Instant parsed = Instant.parse(value);
      if (!parsed.toString().equals(value)) {
        throw invalid("event." + field + " must be canonical UTC Instant text");
      }
      return parsed;
    } catch (DateTimeParseException malformed) {
      throw invalid("event." + field + " must be canonical UTC Instant text", malformed);
    }
  }

  private static String text(ObjectNode object, String field) {
    return StrictAuthorityEventSupport.requireText(
        object, field, "event", RealmGrantAuthorityEventV1Codec::invalid);
  }

  private static String eventId(UUID requestId) {
    return EVENT_ID_PREFIX + requestId;
  }

  private static void requireSealPayloadWithinBound(
      GrantPreimage preimage, String eventId, String sourceScope, String outboxStreamKey) {
    String[] fixedValues = {
      SCHEMA_VERSION,
      EVENT_TYPE,
      eventId,
      preimage.requestId().toString(),
      outboxStreamKey,
      Long.toString(preimage.outboxSequence()),
      sourceScope,
      preimage.grant().accountId().toString(),
      preimage.grant().tenantId().toString(),
      preimage.grant().worldSlug(),
      preimage.grant().realmSlug(),
      preimage.grant().playtestLifecycleId().toString(),
      Long.toString(preimage.grantRevision()),
      preimage.grantAuthorityGeneration().toString(),
      preimage.grantState(),
      preimage.grantExpiresAt().toString(),
      preimage.actorAccountId().toString(),
      preimage.mutationKind(),
      preimage.mutationDigest(),
      "sha256:" + "0".repeat(64)
    };
    long upperBound;
    try {
      upperBound = Math.addExact(6L * WIRE_FIELD_NAMES.length + 1L, WIRE_FIELD_NAME_BYTES);
      for (String value : fixedValues) {
        upperBound = Math.addExact(upperBound, value.length());
      }
      upperBound =
          Math.addExact(
              upperBound, Math.multiplyExact(6L, auditReasonUtf8Length(preimage.auditReason())));
    } catch (ArithmeticException overflow) {
      throw invalid("event payload bound calculation overflows", overflow);
    }
    if (upperBound > MAX_EVENT_PAYLOAD_BYTES) {
      throw invalid("event payload exceeds 32 KiB before serialization");
    }
  }

  private static int fieldNameBytes() {
    int total = 0;
    for (String name : WIRE_FIELD_NAMES) {
      total = Math.addExact(total, name.length());
    }
    return total;
  }

  private static int auditReasonUtf8Length(String reason) {
    int byteLength = 0;
    boolean blank = true;
    for (int index = 0; index < reason.length(); ) {
      char current = reason.charAt(index);
      int codePoint;
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= reason.length() || !Character.isLowSurrogate(reason.charAt(index + 1))) {
          throw invalid("auditReason contains malformed Unicode");
        }
        codePoint = Character.toCodePoint(current, reason.charAt(index + 1));
        index += 2;
      } else if (Character.isLowSurrogate(current)) {
        throw invalid("auditReason contains malformed Unicode");
      } else {
        codePoint = current;
        index++;
      }
      if (codePoint == 0) {
        throw invalid("auditReason must not contain NUL");
      }
      blank &= isMutationWhitespace(codePoint);
      try {
        byteLength = Math.addExact(byteLength, utf8CodePointLength(codePoint));
      } catch (ArithmeticException overflow) {
        throw invalid("auditReason UTF-8 length overflows", overflow);
      }
      if (byteLength > MAX_AUDIT_REASON_UTF8_BYTES) {
        throw invalid("auditReason exceeds 4096 UTF-8 bytes");
      }
    }
    if (blank) {
      throw invalid("auditReason must not be blank");
    }
    if (!Normalizer.isNormalized(reason, Normalizer.Form.NFC)) {
      throw invalid("auditReason must already be NFC-normalized");
    }
    return byteLength;
  }

  private static int utf8CodePointLength(int codePoint) {
    if (codePoint <= 0x7f) return 1;
    if (codePoint <= 0x7ff) return 2;
    if (codePoint <= 0xffff) return 3;
    return 4;
  }

  private static boolean isMutationWhitespace(int codePoint) {
    return (codePoint >= 0x0009 && codePoint <= 0x000D)
        || codePoint == 0x0020
        || codePoint == 0x0085
        || codePoint == 0x00A0
        || codePoint == 0x1680
        || (codePoint >= 0x2000 && codePoint <= 0x200A)
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0x202F
        || codePoint == 0x205F
        || codePoint == 0x3000;
  }

  private static String validateGrantState(String grantState) {
    if (grantState == null || !GRANT_STATES.contains(grantState)) {
      throw invalid("grantState must be ACTIVE or REVOKED");
    }
    return grantState;
  }

  private static String validateMutationKind(String mutationKind) {
    if (mutationKind == null || !MUTATION_KINDS.contains(mutationKind)) {
      throw invalid("mutationKind must be CREATE, EXTEND, REVOKE, or REGRANT");
    }
    return mutationKind;
  }

  private static void validateStateKind(String grantState, String mutationKind) {
    if ("REVOKED".equals(grantState) != "REVOKE".equals(mutationKind)) {
      throw invalid("grantState and mutationKind are contradictory");
    }
  }

  private static UUID requireNonNilUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw invalid(field + " must be a non-nil UUID");
    }
    return value;
  }

  private static String validateMutationDigest(String mutationDigest) {
    if (mutationDigest == null || !MUTATION_DIGEST.matcher(mutationDigest).matches()) {
      throw invalid("mutationDigest must be exactly 64 lowercase hexadecimal characters");
    }
    return mutationDigest;
  }

  private static String validateAuditReason(String auditReason) {
    if (auditReason == null) {
      throw invalid("auditReason is required");
    }
    auditReasonUtf8Length(auditReason);
    return auditReason;
  }

  private static String decodeUtf8(byte[] payload) {
    try {
      CharBuffer decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(payload));
      return decoded.toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("event payload is not valid UTF-8", malformed);
    }
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String path, String message) {
    return new IllegalArgumentException(path + " " + message);
  }

  private static IllegalArgumentException invalid(String message, Exception cause) {
    return new IllegalArgumentException(message, cause);
  }

  /** Typed immutable event fields; construction grants no source or actor authority. */
  public record GrantPreimage(
      UUID requestId,
      GrantAuthorityScopeCodec.Scope grant,
      long grantRevision,
      UUID grantAuthorityGeneration,
      String grantState,
      Instant grantExpiresAt,
      UUID actorAccountId,
      String mutationKind,
      String mutationDigest,
      String auditReason,
      long outboxSequence) {
    public GrantPreimage {
      requestId = requireNonNilUuid(requestId, "requestId");
      if (grant == null) {
        throw invalid("grant scope is required");
      }
      if (grantRevision <= 0) {
        throw invalid("grantRevision must be positive");
      }
      grantAuthorityGeneration =
          requireNonNilUuid(grantAuthorityGeneration, "grantAuthorityGeneration");
      grantState = validateGrantState(grantState);
      if (grantExpiresAt == null) {
        throw invalid("grantExpiresAt is required, including for revoked grants");
      }
      actorAccountId = requireNonNilUuid(actorAccountId, "actorAccountId");
      mutationKind = validateMutationKind(mutationKind);
      mutationDigest = validateMutationDigest(mutationDigest);
      auditReason = validateAuditReason(auditReason);
      if (outboxSequence <= 0) {
        throw invalid("outboxSequence must be positive");
      }
      validateStateKind(grantState, mutationKind);
    }
  }

  /** Immutable producer-sealed or verified event bytes with defensive array accessors. */
  public static final class Event {
    private final GrantPreimage preimage;
    private final String eventId;
    private final String eventDigest;
    private final byte[] canonicalPreimage;
    private final byte[] payload;

    private Event(
        GrantPreimage preimage,
        String eventId,
        String eventDigest,
        byte[] canonicalPreimage,
        byte[] payload) {
      this.preimage = preimage;
      this.eventId = eventId;
      this.eventDigest = eventDigest;
      this.canonicalPreimage = canonicalPreimage.clone();
      this.payload = payload.clone();
    }

    public GrantPreimage preimage() {
      return preimage;
    }

    public String eventId() {
      return eventId;
    }

    public String eventDigest() {
      return eventDigest;
    }

    /** Returns canonical RFC 8785 preimage bytes without the eventDigest property. */
    public byte[] canonicalPreimage() {
      return canonicalPreimage.clone();
    }

    /** Returns the complete canonical RFC 8785 wire event including eventDigest. */
    public byte[] payload() {
      return payload.clone();
    }
  }
}
