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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Closed source evidence for one Account-owned, non-password security-state mutation. */
public final class AccountSecurityStateAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-account-security-state-event/v1";
  public static final String EVENT_TYPE = "ACCOUNT_SECURITY_STATE_CHANGED";
  public static final String EVENT_ID_PREFIX = "account-security-state-event-v1:";
  public static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:";

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
          "accountSecurityCutoff",
          "mutationKinds",
          "accountState");
  private static final Set<String> WIRE_FIELDS;
  private static final Set<String> CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> STATE_FIELDS =
      Set.of("emailVerified", "loginAuthModes", "globalRoles", "lifecycleState");
  private static final Set<String> MUTATION_KINDS =
      Set.of(
          "EMAIL_LOGIN_ELIGIBILITY_CHANGED",
          "LOGIN_AUTH_MODES_CHANGED",
          "GLOBAL_ROLE_CHANGED",
          "LIFECYCLE_STATE_CHANGED");
  private static final Set<String> LOGIN_MODES = Set.of("EMAIL_OTP", "PASSWORD");
  private static final Set<String> GLOBAL_ROLES =
      Set.of("platformAdmin", "support", "billingAdmin");
  private static final Set<String> LIFECYCLE_STATES =
      Set.of("ACTIVE", "SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED");
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern DIGEST_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static {
    var fields = new java.util.HashSet<>(PREIMAGE_FIELDS);
    fields.add("eventDigest");
    WIRE_FIELDS = Set.copyOf(fields);
  }

  private AccountSecurityStateAuthorityEventV1Codec() {}

  /** Seals the complete declared preimage; caller authorization and receipt proof are separate. */
  public static AccountSecurityStateAuthorityEvent seal(Map<String, ?> preimage) {
    Objects.requireNonNull(preimage, "event preimage is required");
    JsonNode tree = JSON.valueToTree(preimage);
    if (!(tree instanceof ObjectNode object)) {
      throw invalid("event preimage must be an object");
    }
    validatePreimage(object);
    String digest = digest(object);
    ObjectNode wire = object.deepCopy();
    wire.put("eventDigest", digest);
    return evidence(wire, digest);
  }

  /** Verifies the closed payload and digest, returning complete immutable canonical evidence. */
  public static AccountSecurityStateAuthorityEvent verify(String wireJson) {
    Objects.requireNonNull(wireJson, "event JSON is required");
    final JsonNode tree;
    try {
      Rfc8785CanonicalJson.canonicalizeUtf8(wireJson);
      tree = JSON.readTree(wireJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event JSON is malformed", exception);
    }
    if (!(tree instanceof ObjectNode wire)) {
      throw invalid("event must be an object");
    }
    exactFields(wire, WIRE_FIELDS);
    String supplied = text(wire, "eventDigest");
    if (!DIGEST_PATTERN.matcher(supplied).matches()) {
      throw invalid("eventDigest must be canonical sha256 hexadecimal");
    }
    ObjectNode preimage = wire.deepCopy();
    preimage.remove("eventDigest");
    validatePreimage(preimage);
    if (!MessageDigest.isEqual(
        supplied.getBytes(StandardCharsets.US_ASCII),
        digest(preimage).getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("eventDigest does not match the complete canonical preimage");
    }
    return evidence(wire, supplied);
  }

  private static void validatePreimage(ObjectNode event) {
    exactFields(event, PREIMAGE_FIELDS);
    equalText(event, "schemaVersion", SCHEMA_VERSION);
    equalText(event, "eventType", EVENT_TYPE);
    String requestId = uuid(event, "requestId");
    equalText(event, "eventId", EVENT_ID_PREFIX + requestId);
    String scope = "account/" + uuid(event, "accountId");
    equalText(event, "sourceScope", scope);
    equalText(event, "outboxStreamKey", EVENT_STREAM_PREFIX + scope);
    positive(event, "outboxSequence");
    positive(event, "accountAuthorityGeneration");
    positive(event, "sourceVersion");
    ObjectNode cutoff = object(event, "accountSecurityCutoff");
    exactFields(cutoff, CUTOFF_FIELDS);
    positive(cutoff, "accountAuthorityGeneration");
    positive(cutoff, "outboxSequence");
    for (String field : CUTOFF_FIELDS) {
      equalText(cutoff, field, text(event, field));
    }
    orderedValues(event.get("mutationKinds"), MUTATION_KINDS, "mutationKinds");
    ObjectNode state = object(event, "accountState");
    exactFields(state, STATE_FIELDS);
    if (!state.get("emailVerified").isBoolean()) {
      throw invalid("accountState.emailVerified must be a boolean");
    }
    orderedValues(state.get("loginAuthModes"), LOGIN_MODES, "accountState.loginAuthModes");
    orderedValues(state.get("globalRoles"), GLOBAL_ROLES, "accountState.globalRoles", true);
    if (!LIFECYCLE_STATES.contains(text(state, "lifecycleState"))) {
      throw invalid("accountState.lifecycleState is unsupported");
    }
  }

  private static List<String> orderedValues(JsonNode array, Set<String> allowed, String field) {
    return orderedValues(array, allowed, field, false);
  }

  private static List<String> orderedValues(
      JsonNode array, Set<String> allowed, String field, boolean allowEmpty) {
    if (array == null || !array.isArray() || (!allowEmpty && array.isEmpty())) {
      throw invalid(field + " must be a declared array with supported cardinality");
    }
    List<String> values = new ArrayList<>();
    String previous = null;
    for (JsonNode item : array) {
      if (!item.isTextual() || !allowed.contains(item.textValue())) {
        throw invalid(field + " contains an unsupported value");
      }
      String value = item.textValue();
      if (previous != null && previous.compareTo(value) >= 0) {
        throw invalid(field + " must be sorted and unique");
      }
      values.add(value);
      previous = value;
    }
    return List.copyOf(values);
  }

  private static ObjectNode object(ObjectNode parent, String field) {
    if (!(parent.get(field) instanceof ObjectNode value)) {
      throw invalid(field + " must be a required object");
    }
    return value;
  }

  private static void exactFields(ObjectNode object, Set<String> expected) {
    Set<String> actual = new java.util.HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw invalid("object must contain exactly the declared fields");
    }
  }

  private static String text(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(field + " must be a required string");
    }
    return value.textValue();
  }

  private static void equalText(ObjectNode object, String field, String expected) {
    if (!expected.equals(text(object, field))) {
      throw invalid(field + " does not match its exact binding");
    }
  }

  private static String uuid(ObjectNode object, String field) {
    String value = text(object, field);
    if (!UUID_PATTERN.matcher(value).matches() || NIL_UUID.equals(value)) {
      throw invalid(field + " must be a canonical lowercase non-nil UUID");
    }
    return value;
  }

  private static void positive(ObjectNode object, String field) {
    if (!POSITIVE_DECIMAL.matcher(text(object, field)).matches()) {
      throw invalid(field + " must be a positive canonical decimal string");
    }
  }

  private static String canonicalJson(ObjectNode object) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(object.toString()), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event cannot be canonicalized", exception);
    }
  }

  private static String digest(ObjectNode object) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonicalJson(object).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static AccountSecurityStateAuthorityEvent evidence(ObjectNode wire, String digest) {
    ObjectNode state = object(wire, "accountState");
    ObjectNode cutoff = object(wire, "accountSecurityCutoff");
    return new AccountSecurityStateAuthorityEvent(
        text(wire, "eventId"),
        text(wire, "requestId"),
        text(wire, "accountId"),
        text(wire, "outboxStreamKey"),
        text(wire, "outboxSequence"),
        text(wire, "accountAuthorityGeneration"),
        text(wire, "sourceVersion"),
        new AccountSecurityCutoff(
            text(cutoff, "accountAuthorityGeneration"),
            text(cutoff, "outboxStreamKey"),
            text(cutoff, "outboxSequence")),
        orderedValues(wire.get("mutationKinds"), MUTATION_KINDS, "mutationKinds"),
        new AccountState(
            state.get("emailVerified").booleanValue(),
            orderedValues(state.get("loginAuthModes"), LOGIN_MODES, "loginAuthModes"),
            orderedValues(state.get("globalRoles"), GLOBAL_ROLES, "globalRoles", true),
            text(state, "lifecycleState")),
        digest,
        canonicalJson(wire));
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  public record AccountSecurityCutoff(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {}

  public record AccountState(
      boolean emailVerified,
      List<String> loginAuthModes,
      List<String> globalRoles,
      String lifecycleState) {
    public AccountState {
      loginAuthModes = List.copyOf(loginAuthModes);
      globalRoles = List.copyOf(globalRoles);
    }
  }

  /** Immutable evidence constructible only by sealing or verifying this exact schema. */
  public static final class AccountSecurityStateAuthorityEvent {
    private final String eventId;
    private final String requestId;
    private final String accountId;
    private final String outboxStreamKey;
    private final String outboxSequence;
    private final String accountAuthorityGeneration;
    private final String sourceVersion;
    private final AccountSecurityCutoff accountSecurityCutoff;
    private final List<String> mutationKinds;
    private final AccountState accountState;
    private final String eventDigest;
    private final String canonicalJson;

    private AccountSecurityStateAuthorityEvent(
        String eventId,
        String requestId,
        String accountId,
        String outboxStreamKey,
        String outboxSequence,
        String accountAuthorityGeneration,
        String sourceVersion,
        AccountSecurityCutoff accountSecurityCutoff,
        List<String> mutationKinds,
        AccountState accountState,
        String eventDigest,
        String canonicalJson) {
      this.eventId = eventId;
      this.requestId = requestId;
      this.accountId = accountId;
      this.outboxStreamKey = outboxStreamKey;
      this.outboxSequence = outboxSequence;
      this.accountAuthorityGeneration = accountAuthorityGeneration;
      this.sourceVersion = sourceVersion;
      this.accountSecurityCutoff = accountSecurityCutoff;
      this.mutationKinds = List.copyOf(mutationKinds);
      this.accountState = accountState;
      this.eventDigest = eventDigest;
      this.canonicalJson = canonicalJson;
    }

    public String schemaVersion() {
      return SCHEMA_VERSION;
    }

    public String eventType() {
      return EVENT_TYPE;
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
      return "account/" + accountId;
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

    public List<String> mutationKinds() {
      return mutationKinds;
    }

    public AccountState accountState() {
      return accountState;
    }

    public String eventDigest() {
      return eventDigest;
    }

    public String canonicalJson() {
      return canonicalJson;
    }

    public byte[] canonicalJsonUtf8() {
      return canonicalJson.getBytes(StandardCharsets.UTF_8);
    }
  }
}
