package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Closed source evidence for one Account-owned, non-password security-state mutation. */
public final class AccountSecurityStateAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-account-security-state-event/v1";
  public static final String EVENT_TYPE = "ACCOUNT_SECURITY_STATE_CHANGED";
  public static final String EVENT_ID_PREFIX = "account-security-state-event-v1:";
  public static final String RESTRICTION_EVENT_TYPE = "ACCOUNT_RESTRICTION_CHANGED";
  public static final String RESTRICTION_EVENT_ID_PREFIX = "account-restriction-event-v1:";
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
  private static final Set<String> RESTRICTION_PREIMAGE_FIELDS =
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
          "restrictionCategory",
          "restrictionRevision",
          "restrictionEnforcementEpoch",
          "restrictionState",
          "restrictionResultId",
          "restrictionRequestDigest",
          "restrictionSourceKind",
          "restrictionSourceRequestId",
          "restrictionSourceDigest");
  private static final Set<String> RESTRICTION_CATEGORIES =
      Set.of("account_security_lock", "platform_access_ban");
  private static final Set<String> RESTRICTION_STATES = Set.of("NONRESTRICTED", "RESTRICTED");
  private static final Set<String> RESTRICTION_SOURCE_KINDS =
      Set.of("ACCOUNT_SECURITY_POLICY", "ACCOUNT_SECURITY_RECOVERY", "LOGGING_ADMIN_MODERATION");
  private static final Set<String> LOGIN_MODES = Set.of("EMAIL_OTP", "PASSWORD");
  private static final Set<String> GLOBAL_ROLES =
      Set.of("platformAdmin", "support", "billingAdmin");
  private static final Set<String> LIFECYCLE_STATES =
      Set.of("ACTIVE", "SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED");
  private static final ObjectMapper JSON = StrictAuthorityEventSupport.strictJsonMapper();

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
    if (!StrictAuthorityEventSupport.isCanonicalSha256Digest(supplied)) {
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

  /**
   * Seals one closed category revision in the existing Account security-state event family. The
   * original ACCOUNT_SECURITY_STATE_CHANGED preimage and codec remain byte-for-byte fixed.
   */
  public static AccountRestrictionAuthorityEvent sealRestriction(Map<String, ?> preimage) {
    Objects.requireNonNull(preimage, "restriction event preimage is required");
    JsonNode tree = JSON.valueToTree(preimage);
    if (!(tree instanceof ObjectNode object)) {
      throw invalid("restriction event preimage must be an object");
    }
    validateRestrictionPreimage(object);
    String digest = digest(object);
    ObjectNode wire = object.deepCopy();
    wire.put("eventDigest", digest);
    return restrictionEvidence(wire, digest);
  }

  /** Verifies either exact v1 variant without widening the original variant's field set. */
  public static AccountSecurityStateFamilyEvent verifyFamily(String wireJson) {
    Objects.requireNonNull(wireJson, "event JSON is required");
    final JsonNode tree;
    try {
      Rfc8785CanonicalJson.canonicalizeUtf8(wireJson);
      tree = JSON.readTree(wireJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event JSON is malformed", exception);
    }
    if (!(tree instanceof ObjectNode object)) {
      throw invalid("event must be an object");
    }
    return switch (text(object, "eventType")) {
      case EVENT_TYPE -> AccountSecurityStateFamilyEvent.securityState(verify(wireJson));
      case RESTRICTION_EVENT_TYPE ->
          AccountSecurityStateFamilyEvent.restriction(verifyRestriction(wireJson));
      default -> throw invalid("Account security-state event variant is unsupported");
    };
  }

  /** Verifies only the new fixed-category restriction variant. */
  public static AccountRestrictionAuthorityEvent verifyRestriction(String wireJson) {
    Objects.requireNonNull(wireJson, "restriction event JSON is required");
    final JsonNode tree;
    try {
      Rfc8785CanonicalJson.canonicalizeUtf8(wireJson);
      tree = JSON.readTree(wireJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("restriction event JSON is malformed", exception);
    }
    if (!(tree instanceof ObjectNode wire)) {
      throw invalid("restriction event must be an object");
    }
    var wireFields = new java.util.HashSet<>(RESTRICTION_PREIMAGE_FIELDS);
    wireFields.add("eventDigest");
    exactFields(wire, wireFields);
    String supplied = text(wire, "eventDigest");
    if (!StrictAuthorityEventSupport.isCanonicalSha256Digest(supplied)) {
      throw invalid("eventDigest must be canonical sha256 hexadecimal");
    }
    ObjectNode preimage = wire.deepCopy();
    preimage.remove("eventDigest");
    validateRestrictionPreimage(preimage);
    if (!MessageDigest.isEqual(
        supplied.getBytes(StandardCharsets.US_ASCII),
        digest(preimage).getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("eventDigest does not match the complete restriction preimage");
    }
    return restrictionEvidence(wire, supplied);
  }

  private static void validateRestrictionPreimage(ObjectNode event) {
    exactFields(event, RESTRICTION_PREIMAGE_FIELDS);
    equalText(event, "schemaVersion", SCHEMA_VERSION);
    equalText(event, "eventType", RESTRICTION_EVENT_TYPE);
    String requestId = uuid(event, "requestId");
    equalText(event, "eventId", RESTRICTION_EVENT_ID_PREFIX + requestId);
    String accountId = uuid(event, "accountId");
    equalText(event, "sourceScope", "account/" + accountId);
    equalText(event, "outboxStreamKey", EVENT_STREAM_PREFIX + "account/" + accountId);
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
    String category = text(event, "restrictionCategory");
    if (!RESTRICTION_CATEGORIES.contains(category)) {
      throw invalid("restrictionCategory is unsupported");
    }
    positive(event, "restrictionRevision");
    positive(event, "restrictionEnforcementEpoch");
    if (!RESTRICTION_STATES.contains(text(event, "restrictionState"))) {
      throw invalid("restrictionState is unsupported");
    }
    uuid(event, "restrictionResultId");
    String requestDigest = text(event, "restrictionRequestDigest");
    if (!StrictAuthorityEventSupport.isCanonicalSha256Digest(requestDigest)) {
      throw invalid("restrictionRequestDigest must be canonical sha256 hexadecimal");
    }
    String sourceKind = text(event, "restrictionSourceKind");
    if (!RESTRICTION_SOURCE_KINDS.contains(sourceKind)
        || ("account_security_lock".equals(category)
            && ("LOGGING_ADMIN_MODERATION".equals(sourceKind)
                || ("ACCOUNT_SECURITY_POLICY".equals(sourceKind)
                    && !"RESTRICTED".equals(text(event, "restrictionState")))
                || ("ACCOUNT_SECURITY_RECOVERY".equals(sourceKind)
                    && !"NONRESTRICTED".equals(text(event, "restrictionState")))))
        || ("platform_access_ban".equals(category)
            && !"LOGGING_ADMIN_MODERATION".equals(sourceKind))) {
      throw invalid("restrictionSourceKind does not match its Account category owner");
    }
    uuid(event, "restrictionSourceRequestId");
    String sourceDigest = text(event, "restrictionSourceDigest");
    if (!StrictAuthorityEventSupport.isCanonicalSha256Digest(sourceDigest)) {
      throw invalid("restrictionSourceDigest must be canonical sha256 hexadecimal");
    }
  }

  private static AccountRestrictionAuthorityEvent restrictionEvidence(
      ObjectNode wire, String digest) {
    ObjectNode cutoff = object(wire, "accountSecurityCutoff");
    return new AccountRestrictionAuthorityEvent(
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
        text(wire, "restrictionCategory"),
        text(wire, "restrictionRevision"),
        text(wire, "restrictionEnforcementEpoch"),
        text(wire, "restrictionState"),
        text(wire, "restrictionResultId"),
        text(wire, "restrictionRequestDigest"),
        text(wire, "restrictionSourceKind"),
        text(wire, "restrictionSourceRequestId"),
        text(wire, "restrictionSourceDigest"),
        digest,
        canonicalJson(wire));
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
    if (!StrictAuthorityEventSupport.hasExactFields(object, expected)) {
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
    if (!StrictAuthorityEventSupport.isCanonicalUuid(value)) {
      throw invalid(field + " must be a canonical lowercase non-nil UUID");
    }
    return value;
  }

  private static void positive(ObjectNode object, String field) {
    if (!StrictAuthorityEventSupport.isPositiveCanonicalDecimal(text(object, field))) {
      throw invalid(field + " must be a positive canonical decimal string");
    }
  }

  private static String canonicalJson(ObjectNode object) {
    return StrictAuthorityEventSupport.canonicalJson(object, "event cannot be canonicalized");
  }

  private static String digest(ObjectNode object) {
    return StrictAuthorityEventSupport.digest(object, "event cannot be canonicalized");
  }

  private static AccountSecurityStateAuthorityEvent evidence(ObjectNode wire, String digest) {
    ObjectNode state = object(wire, "accountState");
    ObjectNode cutoff = object(wire, "accountSecurityCutoff");
    var cutoffFields = StrictAuthorityEventSupport.accountSecurityCutoffFields(cutoff);
    return new AccountSecurityStateAuthorityEvent(
        text(wire, "eventId"),
        text(wire, "requestId"),
        text(wire, "accountId"),
        text(wire, "outboxStreamKey"),
        text(wire, "outboxSequence"),
        text(wire, "accountAuthorityGeneration"),
        text(wire, "sourceVersion"),
        new AccountSecurityCutoff(
            cutoffFields.accountAuthorityGeneration(),
            cutoffFields.outboxStreamKey(),
            cutoffFields.outboxSequence()),
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

  /** One verified closed variant in this Account-owned source-event family. */
  public static final class AccountSecurityStateFamilyEvent {
    private final String eventId;
    private final String requestId;
    private final String accountId;
    private final String outboxStreamKey;
    private final String outboxSequence;
    private final String accountAuthorityGeneration;
    private final String sourceVersion;
    private final AccountSecurityCutoff accountSecurityCutoff;
    private final Optional<AccountSecurityStateAuthorityEvent> securityState;
    private final Optional<AccountRestrictionAuthorityEvent> restriction;
    private final String eventDigest;
    private final String canonicalJson;

    private AccountSecurityStateFamilyEvent(
        String eventId,
        String requestId,
        String accountId,
        String outboxStreamKey,
        String outboxSequence,
        String accountAuthorityGeneration,
        String sourceVersion,
        AccountSecurityCutoff accountSecurityCutoff,
        Optional<AccountSecurityStateAuthorityEvent> securityState,
        Optional<AccountRestrictionAuthorityEvent> restriction,
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
      this.securityState = securityState;
      this.restriction = restriction;
      this.eventDigest = eventDigest;
      this.canonicalJson = canonicalJson;
    }

    private static AccountSecurityStateFamilyEvent securityState(
        AccountSecurityStateAuthorityEvent event) {
      return new AccountSecurityStateFamilyEvent(
          event.eventId(),
          event.requestId(),
          event.accountId(),
          event.outboxStreamKey(),
          event.outboxSequence(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          event.accountSecurityCutoff(),
          Optional.of(event),
          Optional.empty(),
          event.eventDigest(),
          event.canonicalJson());
    }

    private static AccountSecurityStateFamilyEvent restriction(
        AccountRestrictionAuthorityEvent event) {
      return new AccountSecurityStateFamilyEvent(
          event.eventId(),
          event.requestId(),
          event.accountId(),
          event.outboxStreamKey(),
          event.outboxSequence(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          event.accountSecurityCutoff(),
          Optional.empty(),
          Optional.of(event),
          event.eventDigest(),
          event.canonicalJson());
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

    public Optional<AccountSecurityStateAuthorityEvent> securityState() {
      return securityState;
    }

    public Optional<AccountRestrictionAuthorityEvent> restriction() {
      return restriction;
    }

    public String eventDigest() {
      return eventDigest;
    }

    public String canonicalJson() {
      return canonicalJson;
    }
  }

  /** Immutable evidence constructible only by sealing or verifying a restriction variant. */
  public static final class AccountRestrictionAuthorityEvent {
    private final String eventId;
    private final String requestId;
    private final String accountId;
    private final String outboxStreamKey;
    private final String outboxSequence;
    private final String accountAuthorityGeneration;
    private final String sourceVersion;
    private final AccountSecurityCutoff accountSecurityCutoff;
    private final String category;
    private final String revision;
    private final String enforcementEpoch;
    private final String state;
    private final String resultId;
    private final String requestDigest;
    private final String sourceKind;
    private final String sourceRequestId;
    private final String sourceDigest;
    private final String eventDigest;
    private final String canonicalJson;

    private AccountRestrictionAuthorityEvent(
        String eventId,
        String requestId,
        String accountId,
        String outboxStreamKey,
        String outboxSequence,
        String accountAuthorityGeneration,
        String sourceVersion,
        AccountSecurityCutoff accountSecurityCutoff,
        String category,
        String revision,
        String enforcementEpoch,
        String state,
        String resultId,
        String requestDigest,
        String sourceKind,
        String sourceRequestId,
        String sourceDigest,
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
      this.category = category;
      this.revision = revision;
      this.enforcementEpoch = enforcementEpoch;
      this.state = state;
      this.resultId = resultId;
      this.requestDigest = requestDigest;
      this.sourceKind = sourceKind;
      this.sourceRequestId = sourceRequestId;
      this.sourceDigest = sourceDigest;
      this.eventDigest = eventDigest;
      this.canonicalJson = canonicalJson;
    }

    public String schemaVersion() {
      return SCHEMA_VERSION;
    }

    public String eventType() {
      return RESTRICTION_EVENT_TYPE;
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

    public String category() {
      return category;
    }

    public String revision() {
      return revision;
    }

    public String enforcementEpoch() {
      return enforcementEpoch;
    }

    public String state() {
      return state;
    }

    public String resultId() {
      return resultId;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public String sourceKind() {
      return sourceKind;
    }

    public String sourceRequestId() {
      return sourceRequestId;
    }

    public String sourceDigest() {
      return sourceDigest;
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
