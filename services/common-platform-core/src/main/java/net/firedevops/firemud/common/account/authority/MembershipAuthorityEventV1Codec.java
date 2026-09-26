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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Strict codec for committed Account membership authority events. */
public final class MembershipAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-authority-event/v1";
  public static final String EVENT_TYPE = "MEMBERSHIP_CHANGED";
  public static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:";

  private static final String EVENT_SCOPE_PREFIX = "membership/";
  private static final Set<String> PREIMAGE_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "outboxStreamKey",
          "outboxSequence",
          "sourceScope",
          "accountId",
          "tenantId",
          "membershipExists",
          "membershipLifecycleState",
          "membershipVersion",
          "membershipAuthorityGeneration",
          "authorityTuple",
          "issuanceFence",
          "roles",
          "gameplayAdmissionAllowed",
          "callerBoundAuthorityInvalidated");
  private static final Set<String> WIRE_FIELDS;
  private static final Set<String> AUTHORITY_TUPLE_REQUIRED_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> AUTHORITY_TUPLE_OPTIONAL_FIELDS =
      Set.of("accountSecurityCutoff", "tenantBillingCutoff");
  private static final Set<String> GRANT_FIELDS =
      Set.of("tenantId", "worldSlug", "realmSlug", "playtestLifecycleId", "grantVersion");
  private static final Set<String> ACCOUNT_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> TENANT_CUTOFF_FIELDS =
      Set.of(
          "tenantAuthorityGeneration",
          "tenantBillingSequence",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> TENANT_ROLES =
      Set.of("player", "designer", "tenantAdmin", "moderator");
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
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

  private MembershipAuthorityEventV1Codec() {}

  /** Validates a preimage and returns immutable evidence sealed with its canonical digest. */
  public static MembershipEvent seal(Map<String, ?> preimage) {
    Objects.requireNonNull(preimage, "preimage must not be null");
    JsonNode tree = JSON.valueToTree(preimage);
    if (!(tree instanceof ObjectNode object)) {
      throw invalid("event", "must be a JSON object");
    }
    requireExactFields(object, PREIMAGE_FIELDS, Set.of(), "event preimage");
    validatePreimage(object);
    String digest = digest(object);
    ObjectNode wire = object.deepCopy();
    wire.put("eventDigest", digest);
    return toEvidence(wire, digest, canonicalJson(wire));
  }

  /** Validates and verifies one wire event from JSON, rejecting duplicate properties. */
  public static MembershipEvent verify(String wireJson) {
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
  public static MembershipEvent verify(Map<String, ?> wireEvent) {
    Objects.requireNonNull(wireEvent, "wireEvent must not be null");
    JsonNode tree = JSON.valueToTree(wireEvent);
    if (!(tree instanceof ObjectNode wire)) {
      throw invalid("event", "must be a JSON object");
    }
    return verifyWireNode(wire);
  }

  private static MembershipEvent verifyWireNode(ObjectNode wire) {
    requireExactFields(wire, WIRE_FIELDS, Set.of(), "event");
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
    requireExactFields(event, PREIMAGE_FIELDS, Set.of(), "event preimage");
    requireExactText(event, "schemaVersion", SCHEMA_VERSION, "event");
    requireExactText(event, "eventType", EVENT_TYPE, "event");
    requireNonEmptyText(event, "eventId", "event");
    requireNonEmptyText(event, "requestId", "event");

    String accountId = requireUuid(event, "accountId", "event");
    String tenantId = requireUuid(event, "tenantId", "event");
    String sourceScope = EVENT_SCOPE_PREFIX + accountId + "/" + tenantId;
    String streamKey = EVENT_STREAM_PREFIX + sourceScope;
    requireExactText(event, "sourceScope", sourceScope, "event");
    requireExactText(event, "outboxStreamKey", streamKey, "event");

    requirePositiveDecimal(event, "outboxSequence", "event");
    requirePositiveDecimal(event, "membershipVersion", "event");
    String membershipGeneration =
        requirePositiveDecimal(event, "membershipAuthorityGeneration", "event");
    requirePositiveDecimal(event, "issuanceFence", "event");

    if (!event.path("membershipExists").isBoolean()
        || !event.path("membershipExists").booleanValue()) {
      throw invalid("membershipExists", "must be exactly true for a membership-change event");
    }
    String lifecycle = requireText(event, "membershipLifecycleState", "event");
    if (!lifecycle.equals("ACTIVE") && !lifecycle.equals("INACTIVE")) {
      throw invalid("membershipLifecycleState", "must be ACTIVE or INACTIVE");
    }
    boolean admission = requireBoolean(event, "gameplayAdmissionAllowed", "event");
    if (lifecycle.equals("INACTIVE") && admission) {
      throw invalid("gameplayAdmissionAllowed", "must be false for INACTIVE membership");
    }
    requireBoolean(event, "callerBoundAuthorityInvalidated", "event");

    validateRoles(event.get("roles"));
    validateAuthorityTuple(event.get("authorityTuple"), accountId, tenantId, membershipGeneration);
  }

  private static void validateRoles(JsonNode rolesNode) {
    if (rolesNode == null || !rolesNode.isArray()) {
      throw invalid("roles", "must be a required array");
    }
    String previous = null;
    for (JsonNode roleNode : rolesNode) {
      if (!roleNode.isTextual()) {
        throw invalid("roles", "entries must be canonical tenant-role strings");
      }
      String role = roleNode.textValue();
      if (!TENANT_ROLES.contains(role)) {
        throw invalid("roles", "contains an undeclared tenant role");
      }
      if (previous != null && compareCodePoints(previous, role) >= 0) {
        throw invalid(
            "roles", "must be sorted by Unicode code-point order and contain no duplicates");
      }
      previous = role;
    }
  }

  private static void validateAuthorityTuple(
      JsonNode tupleNode,
      String eventAccountId,
      String eventTenantId,
      String eventMembershipGeneration) {
    if (!(tupleNode instanceof ObjectNode tuple)) {
      throw invalid("authorityTuple", "must be an object");
    }
    requireExactFields(
        tuple, AUTHORITY_TUPLE_REQUIRED_FIELDS, AUTHORITY_TUPLE_OPTIONAL_FIELDS, "authorityTuple");
    requirePositiveDecimal(tuple, "issuerAuthGeneration", "authorityTuple");
    requirePositiveDecimal(tuple, "accountAuthorityGeneration", "authorityTuple");

    Map<String, String> tenantGenerations =
        validateGenerationMap(
            tuple.get("tenantAuthorityGeneration"), "authorityTuple.tenantAuthorityGeneration");
    if (tenantGenerations.size() != 1 || !tenantGenerations.containsKey(eventTenantId)) {
      throw invalid(
          "authorityTuple.tenantAuthorityGeneration",
          "must contain exactly the event tenantId key");
    }

    Map<String, String> membershipGenerations =
        validateGenerationMap(
            tuple.get("membershipAuthorityGeneration"),
            "authorityTuple.membershipAuthorityGeneration");
    if (!membershipGenerations.equals(Map.of(eventTenantId, eventMembershipGeneration))) {
      throw invalid(
          "authorityTuple.membershipAuthorityGeneration",
          "must contain exactly the event tenantId and match membershipAuthorityGeneration");
    }

    validatePrivateRealmGrants(tuple.get("privateRealmGrantVersions"), eventTenantId);
    if (tuple.has("accountSecurityCutoff")) {
      validateAccountSecurityCutoff(tuple.get("accountSecurityCutoff"), eventAccountId);
    }
    if (tuple.has("tenantBillingCutoff")) {
      validateTenantBillingCutoff(tuple.get("tenantBillingCutoff"), eventTenantId);
    }
  }

  private static Map<String, String> validateGenerationMap(JsonNode mapNode, String path) {
    if (!(mapNode instanceof ObjectNode object)) {
      throw invalid(path, "must be an object keyed by canonical tenant UUID");
    }
    Map<String, String> values = new TreeMap<>();
    object
        .fields()
        .forEachRemaining(
            entry -> {
              requireUuidKey(entry.getKey(), path);
              JsonNode value = entry.getValue();
              if (value == null || !value.isTextual() || !isPositiveDecimal(value.textValue())) {
                throw invalid(
                    path + "." + entry.getKey(), "must be a positive canonical decimal string");
              }
              values.put(entry.getKey(), value.textValue());
            });
    return Collections.unmodifiableMap(values);
  }

  private static void validatePrivateRealmGrants(JsonNode grantsNode, String eventTenantId) {
    if (grantsNode == null || !grantsNode.isArray()) {
      throw invalid("authorityTuple.privateRealmGrantVersions", "must be a required array");
    }
    GrantIdentity previous = null;
    for (JsonNode grantNode : grantsNode) {
      if (!(grantNode instanceof ObjectNode grant)) {
        throw invalid("authorityTuple.privateRealmGrantVersions", "entries must be objects");
      }
      requireExactFields(grant, GRANT_FIELDS, Set.of(), "privateRealmGrantVersions entry");
      String tenantId = requireUuid(grant, "tenantId", "privateRealmGrantVersions entry");
      if (!tenantId.equals(eventTenantId)) {
        throw invalid(
            "authorityTuple.privateRealmGrantVersions",
            "entries must be scoped to the event tenantId");
      }
      String worldSlug = requireSlug(grant, "worldSlug", "privateRealmGrantVersions entry");
      String realmSlug = requireSlug(grant, "realmSlug", "privateRealmGrantVersions entry");
      String lifecycleId =
          requireUuid(grant, "playtestLifecycleId", "privateRealmGrantVersions entry");
      requirePositiveDecimal(grant, "grantVersion", "privateRealmGrantVersions entry");
      GrantIdentity identity = new GrantIdentity(tenantId, worldSlug, realmSlug, lifecycleId);
      if (previous != null && compareGrantIdentity(previous, identity) >= 0) {
        throw invalid(
            "authorityTuple.privateRealmGrantVersions",
            "entries must be lexicographically sorted by grant identity and contain no duplicates");
      }
      previous = identity;
    }
  }

  private static void validateAccountSecurityCutoff(JsonNode cutoffNode, String eventAccountId) {
    if (!(cutoffNode instanceof ObjectNode cutoff)) {
      throw invalid("authorityTuple.accountSecurityCutoff", "must be an object when applicable");
    }
    requireExactFields(cutoff, ACCOUNT_CUTOFF_FIELDS, Set.of(), "accountSecurityCutoff");
    requirePositiveDecimal(cutoff, "accountAuthorityGeneration", "accountSecurityCutoff");
    requirePositiveDecimal(cutoff, "outboxSequence", "accountSecurityCutoff");
    requireExactText(
        cutoff,
        "outboxStreamKey",
        EVENT_STREAM_PREFIX + "account/" + eventAccountId,
        "accountSecurityCutoff");
  }

  private static void validateTenantBillingCutoff(JsonNode cutoffNode, String eventTenantId) {
    if (!(cutoffNode instanceof ObjectNode cutoffs) || cutoffs.isEmpty()) {
      throw invalid(
          "authorityTuple.tenantBillingCutoff", "must be a non-empty tenant map when applicable");
    }
    if (cutoffs.size() != 1 || !cutoffs.has(eventTenantId)) {
      throw invalid(
          "authorityTuple.tenantBillingCutoff", "must contain exactly the event tenantId key");
    }
    cutoffs
        .fields()
        .forEachRemaining(
            entry -> {
              String tenantId = entry.getKey();
              requireUuidKey(tenantId, "authorityTuple.tenantBillingCutoff");
              if (!(entry.getValue() instanceof ObjectNode cutoff)) {
                throw invalid(
                    "authorityTuple.tenantBillingCutoff." + tenantId, "must be an object");
              }
              requireExactFields(
                  cutoff, TENANT_CUTOFF_FIELDS, Set.of(), "tenantBillingCutoff entry");
              requirePositiveDecimal(
                  cutoff, "tenantAuthorityGeneration", "tenantBillingCutoff entry");
              requirePositiveDecimal(cutoff, "tenantBillingSequence", "tenantBillingCutoff entry");
              requirePositiveDecimal(cutoff, "outboxSequence", "tenantBillingCutoff entry");
              requireExactText(
                  cutoff,
                  "outboxStreamKey",
                  EVENT_STREAM_PREFIX + "tenant/" + tenantId,
                  "tenantBillingCutoff entry");
            });
  }

  private static MembershipEvent toEvidence(ObjectNode wire, String digest, String canonicalJson) {
    ObjectNode tupleNode = (ObjectNode) wire.get("authorityTuple");
    Map<String, String> tenantGenerations =
        readStringMap(tupleNode.get("tenantAuthorityGeneration"));
    Map<String, String> membershipGenerations =
        readStringMap(tupleNode.get("membershipAuthorityGeneration"));

    List<PrivateRealmGrantVersion> grants = new ArrayList<>();
    for (JsonNode grant : tupleNode.get("privateRealmGrantVersions")) {
      grants.add(
          new PrivateRealmGrantVersion(
              grant.get("tenantId").textValue(),
              grant.get("worldSlug").textValue(),
              grant.get("realmSlug").textValue(),
              grant.get("playtestLifecycleId").textValue(),
              grant.get("grantVersion").textValue()));
    }

    Optional<AccountSecurityCutoff> accountCutoff = Optional.empty();
    if (tupleNode.has("accountSecurityCutoff")) {
      JsonNode cutoff = tupleNode.get("accountSecurityCutoff");
      accountCutoff =
          Optional.of(
              new AccountSecurityCutoff(
                  cutoff.get("accountAuthorityGeneration").textValue(),
                  cutoff.get("outboxStreamKey").textValue(),
                  cutoff.get("outboxSequence").textValue()));
    }

    Optional<Map<String, TenantBillingCutoff>> tenantCutoffs = Optional.empty();
    if (tupleNode.has("tenantBillingCutoff")) {
      Map<String, TenantBillingCutoff> values = new TreeMap<>();
      tupleNode
          .get("tenantBillingCutoff")
          .fields()
          .forEachRemaining(
              entry -> {
                JsonNode cutoff = entry.getValue();
                values.put(
                    entry.getKey(),
                    new TenantBillingCutoff(
                        cutoff.get("tenantAuthorityGeneration").textValue(),
                        cutoff.get("tenantBillingSequence").textValue(),
                        cutoff.get("outboxStreamKey").textValue(),
                        cutoff.get("outboxSequence").textValue()));
              });
      tenantCutoffs = Optional.of(immutableMap(values));
    }

    AuthorityTuple authorityTuple =
        new AuthorityTuple(
            tupleNode.get("issuerAuthGeneration").textValue(),
            tupleNode.get("accountAuthorityGeneration").textValue(),
            tenantGenerations,
            membershipGenerations,
            grants,
            accountCutoff,
            tenantCutoffs);
    List<String> roles = new ArrayList<>();
    wire.get("roles").forEach(role -> roles.add(role.textValue()));
    return new MembershipEvent(
        wire.get("schemaVersion").textValue(),
        wire.get("eventType").textValue(),
        wire.get("eventId").textValue(),
        wire.get("requestId").textValue(),
        wire.get("outboxStreamKey").textValue(),
        wire.get("outboxSequence").textValue(),
        wire.get("sourceScope").textValue(),
        wire.get("accountId").textValue(),
        wire.get("tenantId").textValue(),
        wire.get("membershipLifecycleState").textValue(),
        wire.get("membershipVersion").textValue(),
        wire.get("membershipAuthorityGeneration").textValue(),
        authorityTuple,
        wire.get("issuanceFence").textValue(),
        roles,
        wire.get("gameplayAdmissionAllowed").booleanValue(),
        wire.get("callerBoundAuthorityInvalidated").booleanValue(),
        digest,
        canonicalJson);
  }

  private static Map<String, String> readStringMap(JsonNode mapNode) {
    Map<String, String> values = new TreeMap<>();
    mapNode
        .fields()
        .forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().textValue()));
    return immutableMap(values);
  }

  private static String digest(ObjectNode preimage) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical);
      StringBuilder hex = new StringBuilder(64);
      for (byte item : hash) {
        hex.append(Character.forDigit((item >>> 4) & 0x0f, 16));
        hex.append(Character.forDigit(item & 0x0f, 16));
      }
      return "sha256:" + hex;
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "Unable to compute canonical membership event digest", exception);
    }
  }

  private static String canonicalJson(ObjectNode node) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(node)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("event cannot be serialized as RFC 8785 JSON", exception);
    }
  }

  private static void requireExactFields(
      ObjectNode object, Set<String> required, Set<String> optional, String path) {
    for (String field : required) {
      if (!object.has(field)) {
        throw invalid(path + "." + field, "is required");
      }
    }
    object
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!required.contains(field) && !optional.contains(field)) {
                throw invalid(path + "." + field, "is not declared by this schema");
              }
            });
  }

  private static String requireExactText(
      ObjectNode object, String field, String expected, String path) {
    String value = requireText(object, field, path);
    if (!value.equals(expected)) {
      throw invalid(path + "." + field, "must equal " + expected);
    }
    return value;
  }

  private static String requireNonEmptyText(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (value.isBlank() || value.codePointCount(0, value.length()) > 512) {
      throw invalid(path + "." + field, "must be nonblank and at most 512 characters");
    }
    return value;
  }

  private static String requireText(ObjectNode object, String field, String path) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(path + "." + field, "must be a string");
    }
    return value.textValue();
  }

  private static String requireUuid(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (!isUuid(value)) {
      throw invalid(path + "." + field, "must be a canonical lowercase UUID string");
    }
    return value;
  }

  private static void requireUuidKey(String value, String path) {
    if (!isUuid(value)) {
      throw invalid(path + "." + value, "map key must be a canonical lowercase UUID");
    }
  }

  private static boolean isUuid(String value) {
    return UUID_PATTERN.matcher(value).matches();
  }

  private static String requireSlug(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (value.getBytes(StandardCharsets.UTF_8).length > 120
        || !SLUG_PATTERN.matcher(value).matches()) {
      throw invalid(path + "." + field, "must be a canonical lowercase ASCII slug of 1-120 bytes");
    }
    return value;
  }

  private static String requirePositiveDecimal(ObjectNode object, String field, String path) {
    String value = requireText(object, field, path);
    if (!isPositiveDecimal(value)) {
      throw invalid(path + "." + field, "must be a positive canonical unsigned decimal string");
    }
    return value;
  }

  private static boolean isPositiveDecimal(String value) {
    return POSITIVE_DECIMAL_PATTERN.matcher(value).matches();
  }

  private static boolean requireBoolean(ObjectNode object, String field, String path) {
    JsonNode value = object.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalid(path + "." + field, "must be a boolean");
    }
    return value.booleanValue();
  }

  private static <V> Map<String, V> immutableMap(Map<String, V> values) {
    return Collections.unmodifiableMap(new TreeMap<>(values));
  }

  private static int compareGrantIdentity(GrantIdentity left, GrantIdentity right) {
    int compare = compareCodePoints(left.tenantId(), right.tenantId());
    if (compare == 0) {
      compare = compareCodePoints(left.worldSlug(), right.worldSlug());
    }
    if (compare == 0) {
      compare = compareCodePoints(left.realmSlug(), right.realmSlug());
    }
    if (compare == 0) {
      compare = compareCodePoints(left.playtestLifecycleId(), right.playtestLifecycleId());
    }
    return compare;
  }

  private static int compareCodePoints(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftPoint = left.codePointAt(leftIndex);
      int rightPoint = right.codePointAt(rightIndex);
      if (leftPoint != rightPoint) {
        return Integer.compare(leftPoint, rightPoint);
      }
      leftIndex += Character.charCount(leftPoint);
      rightIndex += Character.charCount(rightPoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }

  private static IllegalArgumentException invalid(String path, String message) {
    return new IllegalArgumentException(path + " " + message);
  }

  private record GrantIdentity(
      String tenantId, String worldSlug, String realmSlug, String playtestLifecycleId) {}

  /** Immutable verified or producer-sealed event evidence. */
  public static final class MembershipEvent {
    private final String schemaVersion;
    private final String eventType;
    private final String eventId;
    private final String requestId;
    private final String outboxStreamKey;
    private final String outboxSequence;
    private final String sourceScope;
    private final String accountId;
    private final String tenantId;
    private final String membershipLifecycleState;
    private final String membershipVersion;
    private final String membershipAuthorityGeneration;
    private final AuthorityTuple authorityTuple;
    private final String issuanceFence;
    private final List<String> roles;
    private final boolean gameplayAdmissionAllowed;
    private final boolean callerBoundAuthorityInvalidated;
    private final String eventDigest;
    private final String canonicalJson;

    private MembershipEvent(
        String schemaVersion,
        String eventType,
        String eventId,
        String requestId,
        String outboxStreamKey,
        String outboxSequence,
        String sourceScope,
        String accountId,
        String tenantId,
        String membershipLifecycleState,
        String membershipVersion,
        String membershipAuthorityGeneration,
        AuthorityTuple authorityTuple,
        String issuanceFence,
        List<String> roles,
        boolean gameplayAdmissionAllowed,
        boolean callerBoundAuthorityInvalidated,
        String eventDigest,
        String canonicalJson) {
      this.schemaVersion = schemaVersion;
      this.eventType = eventType;
      this.eventId = eventId;
      this.requestId = requestId;
      this.outboxStreamKey = outboxStreamKey;
      this.outboxSequence = outboxSequence;
      this.sourceScope = sourceScope;
      this.accountId = accountId;
      this.tenantId = tenantId;
      this.membershipLifecycleState = membershipLifecycleState;
      this.membershipVersion = membershipVersion;
      this.membershipAuthorityGeneration = membershipAuthorityGeneration;
      this.authorityTuple = authorityTuple;
      this.issuanceFence = issuanceFence;
      this.roles = List.copyOf(roles);
      this.gameplayAdmissionAllowed = gameplayAdmissionAllowed;
      this.callerBoundAuthorityInvalidated = callerBoundAuthorityInvalidated;
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

    public String outboxStreamKey() {
      return outboxStreamKey;
    }

    public String outboxSequence() {
      return outboxSequence;
    }

    public String sourceScope() {
      return sourceScope;
    }

    public String accountId() {
      return accountId;
    }

    public String tenantId() {
      return tenantId;
    }

    public String membershipLifecycleState() {
      return membershipLifecycleState;
    }

    public String membershipVersion() {
      return membershipVersion;
    }

    public String membershipAuthorityGeneration() {
      return membershipAuthorityGeneration;
    }

    public AuthorityTuple authorityTuple() {
      return authorityTuple;
    }

    public String issuanceFence() {
      return issuanceFence;
    }

    public List<String> roles() {
      return roles;
    }

    public boolean gameplayAdmissionAllowed() {
      return gameplayAdmissionAllowed;
    }

    public boolean callerBoundAuthorityInvalidated() {
      return callerBoundAuthorityInvalidated;
    }

    public String eventDigest() {
      return eventDigest;
    }

    /** Returns the canonical complete wire event. */
    public String canonicalJson() {
      return canonicalJson;
    }

    /** Returns a caller-owned copy of the canonical complete wire event bytes. */
    public byte[] canonicalJsonUtf8() {
      return canonicalJson.getBytes(StandardCharsets.UTF_8);
    }
  }

  /** Immutable authority tuple carried by a membership event. */
  public record AuthorityTuple(
      String issuerAuthGeneration,
      String accountAuthorityGeneration,
      Map<String, String> tenantAuthorityGeneration,
      Map<String, String> membershipAuthorityGeneration,
      List<PrivateRealmGrantVersion> privateRealmGrantVersions,
      Optional<AccountSecurityCutoff> accountSecurityCutoff,
      Optional<Map<String, TenantBillingCutoff>> tenantBillingCutoff) {
    public AuthorityTuple {
      tenantAuthorityGeneration = immutableMap(tenantAuthorityGeneration);
      membershipAuthorityGeneration = immutableMap(membershipAuthorityGeneration);
      privateRealmGrantVersions = List.copyOf(privateRealmGrantVersions);
      accountSecurityCutoff = Objects.requireNonNull(accountSecurityCutoff);
      tenantBillingCutoff = tenantBillingCutoff.map(MembershipAuthorityEventV1Codec::immutableMap);
    }

    @Override
    public Map<String, String> tenantAuthorityGeneration() {
      return immutableMap(tenantAuthorityGeneration);
    }

    @Override
    public Map<String, String> membershipAuthorityGeneration() {
      return immutableMap(membershipAuthorityGeneration);
    }
  }

  /** Immutable lifecycle-bound private-realm grant version. */
  public record PrivateRealmGrantVersion(
      String tenantId,
      String worldSlug,
      String realmSlug,
      String playtestLifecycleId,
      String grantVersion) {}

  /** Immutable Account security cutoff checkpoint. */
  public record AccountSecurityCutoff(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {}

  /** Immutable tenant billing cutoff checkpoint. */
  public record TenantBillingCutoff(
      String tenantAuthorityGeneration,
      String tenantBillingSequence,
      String outboxStreamKey,
      String outboxSequence) {}
}
