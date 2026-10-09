package net.firedevops.firemud.common.operator;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integrity-only decoder for the closed StartSession human tenant-admin {@code
 * authorityEvidenceBundle/v1} contract.
 *
 * <p>This value proves only that supplied bytes are canonical and structurally bound to a typed
 * StartSession tuple and the separate Account source reference. It does not establish current
 * Account authority, source freshness, a live reservation claim, or permission to dispatch.
 */
public final class StartSessionAuthorityEvidenceBundle {
  public static final String BUNDLE_VERSION = "authorityEvidenceBundle/v1";
  public static final String HUMAN_EVIDENCE_TYPE = "HumanAuthorityEvidence/v1";
  public static final int MAX_BUNDLE_BYTES = 128 * 1024;

  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SOURCE_EVIDENCE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern POSITIVE_U64 = Pattern.compile("[1-9][0-9]{0,19}");
  private static final BigInteger MAX_UNSIGNED_LONG =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "bundleVersion",
          "authorityScope",
          "accountProjectionEvidence",
          "issuanceOperationIdentity",
          "issuanceKind",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence",
          "issuanceEvidence");
  private static final Set<String> AUTHORITY_SCOPE_FIELDS =
      Set.of("scope", "actionFamily", "applicableAccountId", "applicableTenantId");
  private static final Set<String> SCOPE_FIELDS = Set.of("tenantId", "targetNamespace");
  private static final Set<String> PROJECTION_FIELDS =
      Set.of(
          "sourceType",
          "sourceEvidenceId",
          "sourceEvidenceVersion",
          "projectionStatus",
          "evaluatedAt",
          "expiresAt");
  private static final Set<String> OPERATION_FIELDS =
      Set.of(
          "issuanceOperationId",
          "controlPlaneRequestId",
          "actionFamilyRequestIdentity",
          "mutationDigest");
  private static final Set<String> REQUEST_IDENTITY_FIELDS =
      Set.of("requestIdentityKind", "requestId");
  private static final Set<String> HUMAN_TENANT_FIELDS =
      Set.of(
          "evidenceType",
          "actorAccountId",
          "controlUiTokenJti",
          "role",
          "accountGeneration",
          "tenantGeneration");
  private static final Set<String> REQUIRED_AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> ALLOWED_AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions",
          "accountSecurityCutoff",
          "tenantBillingCutoff");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final byte[] canonicalBytes;
  private final Map<String, Object> value;

  private StartSessionAuthorityEvidenceBundle(byte[] canonicalBytes, Map<String, Object> value) {
    this.canonicalBytes = canonicalBytes.clone();
    this.value = immutableObject(value);
  }

  /** Strictly decodes one canonical, closed, bounded StartSession tenant-admin bundle. */
  public static StartSessionAuthorityEvidenceBundle decode(byte[] exactCanonicalBytes) {
    if (exactCanonicalBytes == null
        || exactCanonicalBytes.length == 0
        || exactCanonicalBytes.length > MAX_BUNDLE_BYTES) {
      throw denied("Authority evidence bundle is empty or over the supported bound");
    }
    try {
      String json = strictUtf8(exactCanonicalBytes);
      Map<String, Object> decoded = JSON.readValue(json, new TypeReference<>() {});
      requireFields(decoded, ROOT_FIELDS, "authorityEvidenceBundle/v1");
      if (!BUNDLE_VERSION.equals(string(decoded.get("bundleVersion"), "bundleVersion"))) {
        throw denied("Unsupported authority evidence bundle version");
      }
      validateAuthorityScope(object(decoded.get("authorityScope"), "authorityScope"));
      validateProjection(
          object(decoded.get("accountProjectionEvidence"), "accountProjectionEvidence"));
      validateOperationIdentity(
          object(decoded.get("issuanceOperationIdentity"), "issuanceOperationIdentity"));
      if (!"human_operator".equals(string(decoded.get("issuanceKind"), "issuanceKind"))) {
        throw denied("Only the human StartSession operator branch is supported");
      }
      validateAuthorityTuple(object(decoded.get("authorityTuple"), "authorityTuple"));
      validateMembershipVersion(object(decoded.get("membershipVersion"), "membershipVersion"));
      positiveDecimal(string(decoded.get("issuanceFence"), "issuanceFence"), "issuanceFence");
      validateTenantHumanEvidence(object(decoded.get("issuanceEvidence"), "issuanceEvidence"));

      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      if (!MessageDigest.isEqual(canonical, exactCanonicalBytes)) {
        throw denied("Authority evidence bundle is not canonical UTF-8 JSON");
      }
      return new StartSessionAuthorityEvidenceBundle(canonical, decoded);
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw denied("Authority evidence bundle is malformed");
    }
  }

  /** Compares every tuple-bound identity and digest without granting authority or freshness. */
  public void requireTupleBinding(StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    Map<String, Object> authorityScope = object(value.get("authorityScope"), "authorityScope");
    Map<String, Object> scope = object(authorityScope.get("scope"), "authorityScope.scope");
    Map<String, Object> operation =
        object(value.get("issuanceOperationIdentity"), "issuanceOperationIdentity");
    Map<String, Object> identity =
        object(operation.get("actionFamilyRequestIdentity"), "actionFamilyRequestIdentity");
    Map<String, Object> human = object(value.get("issuanceEvidence"), "issuanceEvidence");
    String tenantId = tuple.action().scope().tenantId().toString();
    String actorId = tuple.actor().accountId().toString();
    Map<String, Object> authorityTuple = object(value.get("authorityTuple"), "authorityTuple");
    Map<String, Object> tenantGenerations =
        object(authorityTuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    Map<String, Object> membershipGenerations =
        object(
            authorityTuple.get("membershipAuthorityGeneration"), "membershipAuthorityGeneration");
    Map<String, Object> membershipVersion =
        object(value.get("membershipVersion"), "membershipVersion");
    long accountGeneration =
        positiveNumber(
            authorityTuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    if (!tuple.actionFamily().equals(authorityScope.get("actionFamily"))
        || !actorId.equals(authorityScope.get("applicableAccountId"))
        || !tenantId.equals(authorityScope.get("applicableTenantId"))
        || !tenantId.equals(scope.get("tenantId"))
        || !tuple.action().scope().targetNamespace().equals(scope.get("targetNamespace"))
        || !tuple.controlPlaneRequestId().equals(operation.get("controlPlaneRequestId"))
        || !tuple.controlPlaneRequestId().equals(identity.get("requestId"))
        || !"controlPlaneRequestId".equals(identity.get("requestIdentityKind"))
        || !tuple.mutationDigest().equals(operation.get("mutationDigest"))
        || !actorId.equals(human.get("actorAccountId"))
        || !tenantGenerations.keySet().equals(Set.of(tenantId))
        || !membershipGenerations.keySet().equals(Set.of(tenantId))
        || !membershipVersion.keySet().equals(Set.of(tenantId))
        || accountGeneration
            != parsePositiveDecimal(
                string(human.get("accountGeneration"), "accountGeneration"), "accountGeneration")
        || positiveNumber(tenantGenerations.get(tenantId), "tenantAuthorityGeneration")
            != parsePositiveDecimal(
                string(human.get("tenantGeneration"), "tenantGeneration"), "tenantGeneration")) {
      throw denied("Authority evidence differs from the exact StartSession tuple");
    }
    if (authorityTuple.containsKey("tenantBillingCutoff")) {
      Map<String, Object> billingCutoffs =
          object(authorityTuple.get("tenantBillingCutoff"), "tenantBillingCutoff");
      if (!billingCutoffs.isEmpty() && !billingCutoffs.keySet().equals(Set.of(tenantId))) {
        throw denied("Tenant billing cutoff differs from the exact StartSession tenant");
      }
    }
  }

  /** Binds the separate Account source reference; this does not prove source freshness. */
  public void requireReferenceBinding(BundleReference reference) {
    Objects.requireNonNull(reference, "Account bundle reference is required");
    Map<String, Object> projection =
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence");
    if (!BUNDLE_VERSION.equals(reference.bundleVersion())
        || !reference.sourceVersion().equals(projection.get("sourceEvidenceVersion"))
        || !reference.sourceFence().equals(value.get("issuanceFence"))) {
      throw denied("Account bundle reference differs from immutable authority evidence");
    }
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String bundleVersion() {
    return BUNDLE_VERSION;
  }

  public String actionFamily() {
    return string(
        object(value.get("authorityScope"), "authorityScope").get("actionFamily"), "actionFamily");
  }

  public UUID actorAccountId() {
    return uuid(
        object(value.get("authorityScope"), "authorityScope").get("applicableAccountId"),
        "applicableAccountId");
  }

  public UUID tenantId() {
    Map<String, Object> authorityScope = object(value.get("authorityScope"), "authorityScope");
    return uuid(object(authorityScope.get("scope"), "scope").get("tenantId"), "tenantId");
  }

  public String targetNamespace() {
    Map<String, Object> authorityScope = object(value.get("authorityScope"), "authorityScope");
    return string(
        object(authorityScope.get("scope"), "scope").get("targetNamespace"), "targetNamespace");
  }

  public String controlPlaneRequestId() {
    return string(
        object(value.get("issuanceOperationIdentity"), "issuanceOperationIdentity")
            .get("controlPlaneRequestId"),
        "controlPlaneRequestId");
  }

  public String mutationDigest() {
    return string(
        object(value.get("issuanceOperationIdentity"), "issuanceOperationIdentity")
            .get("mutationDigest"),
        "mutationDigest");
  }

  public UUID issuanceOperationId() {
    return uuid(
        object(value.get("issuanceOperationIdentity"), "issuanceOperationIdentity")
            .get("issuanceOperationId"),
        "issuanceOperationId");
  }

  public String sourceEvidenceId() {
    return string(
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence")
            .get("sourceEvidenceId"),
        "sourceEvidenceId");
  }

  public String sourceEvidenceVersion() {
    return string(
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence")
            .get("sourceEvidenceVersion"),
        "sourceEvidenceVersion");
  }

  public Instant evaluatedAt() {
    return instant(
        string(
            object(value.get("accountProjectionEvidence"), "accountProjectionEvidence")
                .get("evaluatedAt"),
            "evaluatedAt"));
  }

  public Instant expiresAt() {
    return instant(
        string(
            object(value.get("accountProjectionEvidence"), "accountProjectionEvidence")
                .get("expiresAt"),
            "expiresAt"));
  }

  public String issuanceFence() {
    return string(value.get("issuanceFence"), "issuanceFence");
  }

  public String evidenceType() {
    return string(
        object(value.get("issuanceEvidence"), "issuanceEvidence").get("evidenceType"),
        "evidenceType");
  }

  public String role() {
    return string(object(value.get("issuanceEvidence"), "issuanceEvidence").get("role"), "role");
  }

  public UUID controlUiTokenJti() {
    return uuid(
        object(value.get("issuanceEvidence"), "issuanceEvidence").get("controlUiTokenJti"),
        "controlUiTokenJti");
  }

  public String authorizationExpiresAt() {
    return string(
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence")
            .get("expiresAt"),
        "expiresAt");
  }

  /** Returns the deeply immutable decoded JSON value for Account's exact current-source checks. */
  public Map<String, Object> jsonValue() {
    return value;
  }

  public Map<String, Object> authorityTuple() {
    return object(value.get("authorityTuple"), "authorityTuple");
  }

  public Map<String, Object> membershipVersion() {
    return object(value.get("membershipVersion"), "membershipVersion");
  }

  private static void validateAuthorityScope(Map<String, Object> scopeValue) {
    requireFields(scopeValue, AUTHORITY_SCOPE_FIELDS, "authorityScope");
    Map<String, Object> scope = object(scopeValue.get("scope"), "authorityScope.scope");
    requireFields(scope, SCOPE_FIELDS, "StartSession scope");
    uuid(scope.get("tenantId"), "scope.tenantId");
    if (!GrpcPeerIdentity.isValidNamespace(
        string(scope.get("targetNamespace"), "targetNamespace"))) {
      throw denied("StartSession target namespace is invalid");
    }
    if (!"StartSession".equals(string(scopeValue.get("actionFamily"), "actionFamily"))) {
      throw denied("Unsupported StartSession action family");
    }
    uuid(scopeValue.get("applicableAccountId"), "applicableAccountId");
    uuid(scopeValue.get("applicableTenantId"), "applicableTenantId");
    if (!scope.get("tenantId").equals(scopeValue.get("applicableTenantId"))) {
      throw denied("Tenant scope and applicable tenant must match");
    }
  }

  private static void validateProjection(Map<String, Object> projection) {
    requireFields(projection, PROJECTION_FIELDS, "accountProjectionEvidence");
    if (!"ACCOUNT".equals(string(projection.get("sourceType"), "sourceType"))
        || !"CURRENT".equals(string(projection.get("projectionStatus"), "projectionStatus"))
        || !SOURCE_EVIDENCE_ID
            .matcher(string(projection.get("sourceEvidenceId"), "sourceEvidenceId"))
            .matches()) {
      throw denied("Current Account projection evidence is required");
    }
    positiveDecimal(
        string(projection.get("sourceEvidenceVersion"), "sourceEvidenceVersion"),
        "sourceEvidenceVersion");
    Instant evaluatedAt = instant(string(projection.get("evaluatedAt"), "evaluatedAt"));
    Instant expiresAt = instant(string(projection.get("expiresAt"), "expiresAt"));
    if (!expiresAt.isAfter(evaluatedAt)) {
      throw denied("Account projection evidence expiry must follow its evaluation");
    }
  }

  private static void validateOperationIdentity(Map<String, Object> operation) {
    requireFields(operation, OPERATION_FIELDS, "issuanceOperationIdentity");
    uuid(operation.get("issuanceOperationId"), "issuanceOperationId");
    String requestId =
        StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
            string(operation.get("controlPlaneRequestId"), "controlPlaneRequestId"));
    Map<String, Object> identity =
        object(operation.get("actionFamilyRequestIdentity"), "actionFamilyRequestIdentity");
    requireFields(identity, REQUEST_IDENTITY_FIELDS, "actionFamilyRequestIdentity");
    if (!"controlPlaneRequestId".equals(identity.get("requestIdentityKind"))
        || !requestId.equals(identity.get("requestId"))
        || !DIGEST.matcher(string(operation.get("mutationDigest"), "mutationDigest")).matches()) {
      throw denied("StartSession operation identity or digest is invalid");
    }
  }

  private static void validateAuthorityTuple(Map<String, Object> authority) {
    if (!authority.keySet().containsAll(REQUIRED_AUTHORITY_TUPLE_FIELDS)
        || !ALLOWED_AUTHORITY_TUPLE_FIELDS.containsAll(authority.keySet())) {
      throw denied("Authority tuple has missing or unsupported fields");
    }
    positiveNumber(authority.get("issuerAuthGeneration"), "issuerAuthGeneration");
    positiveNumber(authority.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    mapOfPositiveNumbers(authority.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    mapOfPositiveNumbers(
        authority.get("membershipAuthorityGeneration"), "membershipAuthorityGeneration");
    if (!(authority.get("privateRealmGrantVersions") instanceof List<?> grants)
        || !grants.isEmpty()) {
      throw denied("StartSession control-plane authority must not acquire gameplay grants");
    }
    if (authority.containsKey("accountSecurityCutoff")) {
      validateAccountSecurityCutoff(
          object(authority.get("accountSecurityCutoff"), "accountSecurityCutoff"));
    }
    if (authority.containsKey("tenantBillingCutoff")) {
      validateTenantBillingCutoffs(
          object(authority.get("tenantBillingCutoff"), "tenantBillingCutoff"));
    }
  }

  private static void validateAccountSecurityCutoff(Map<String, Object> cutoff) {
    requireFields(
        cutoff,
        Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence"),
        "accountSecurityCutoff");
    positiveNumber(cutoff.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    boundedText(cutoff.get("outboxStreamKey"), "outboxStreamKey", 256);
    positiveNumber(cutoff.get("outboxSequence"), "outboxSequence");
  }

  private static void validateTenantBillingCutoffs(Map<String, Object> cutoffs) {
    if (cutoffs.size() > 1) {
      throw denied("StartSession bundle must not widen tenant billing scope");
    }
    cutoffs.forEach(
        (tenantId, raw) -> {
          uuid(tenantId, "tenantBillingCutoff tenant ID");
          Map<String, Object> cutoff = object(raw, "tenantBillingCutoff entry");
          requireFields(
              cutoff,
              Set.of(
                  "tenantAuthorityGeneration",
                  "tenantBillingSequence",
                  "outboxStreamKey",
                  "outboxSequence"),
              "tenantBillingCutoff entry");
          positiveNumber(cutoff.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
          positiveNumber(cutoff.get("tenantBillingSequence"), "tenantBillingSequence");
          boundedText(cutoff.get("outboxStreamKey"), "outboxStreamKey", 256);
          positiveNumber(cutoff.get("outboxSequence"), "outboxSequence");
        });
  }

  private static void validateMembershipVersion(Map<String, Object> membership) {
    if (membership.size() > 1) {
      throw denied("StartSession bundle must not widen membership scope");
    }
    membership.forEach(
        (tenantId, version) -> {
          uuid(tenantId, "membershipVersion tenant ID");
          positiveNumber(version, "membershipVersion");
        });
  }

  private static void validateTenantHumanEvidence(Map<String, Object> evidence) {
    requireFields(evidence, HUMAN_TENANT_FIELDS, "HumanAuthorityEvidence/v1 tenant branch");
    if (!HUMAN_EVIDENCE_TYPE.equals(string(evidence.get("evidenceType"), "evidenceType"))
        || !"tenantAdmin".equals(string(evidence.get("role"), "role"))) {
      throw denied("Only the current tenantAdmin HumanAuthorityEvidence/v1 arm applies");
    }
    uuid(evidence.get("actorAccountId"), "actorAccountId");
    uuid(evidence.get("controlUiTokenJti"), "controlUiTokenJti");
    positiveDecimal(
        string(evidence.get("accountGeneration"), "accountGeneration"), "accountGeneration");
    positiveDecimal(
        string(evidence.get("tenantGeneration"), "tenantGeneration"), "tenantGeneration");
  }

  private static Instant instant(String value) {
    try {
      Instant parsed = Instant.parse(value);
      if (!parsed.toString().equals(value) || parsed.getNano() % 1_000_000 != 0) {
        throw denied("Timestamp is not canonical exact-millisecond UTC text");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw denied("Timestamp is malformed");
    }
  }

  private static void mapOfPositiveNumbers(Object raw, String field) {
    Map<String, Object> values = object(raw, field);
    if (values.size() > 1) {
      throw denied(field + " must be limited to the selected tenant");
    }
    values.forEach(
        (key, value) -> {
          uuid(key, field + " tenant ID");
          positiveNumber(value, field);
        });
  }

  private static long positiveNumber(Object value, String field) {
    try {
      long parsed = value instanceof Number number ? Long.parseLong(number.toString()) : 0L;
      if (parsed <= 0L) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw denied(field + " must be a positive signed 64-bit integer");
    }
  }

  private static void positiveDecimal(String value, String field) {
    parsePositiveDecimal(value, field);
  }

  private static long parsePositiveDecimal(String value, String field) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw denied(field + " must be a positive canonical signed 64-bit decimal");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException malformed) {
      throw denied(field + " is outside the supported signed 64-bit range");
    }
  }

  private static void positiveU64(String value, String field) {
    if (value == null || !POSITIVE_U64.matcher(value).matches()) {
      throw denied(field + " must be a positive canonical unsigned 64-bit decimal");
    }
    try {
      if (new BigInteger(value).compareTo(MAX_UNSIGNED_LONG) > 0) {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException malformed) {
      throw denied(field + " is outside the supported unsigned 64-bit range");
    }
  }

  private static UUID uuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)
          || !CANONICAL_UUID.matcher(text).matches()
          || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw denied(field + " must be a canonical lowercase non-nil UUID");
    }
  }

  private static void boundedText(Object value, String field, int maxUtf8Bytes) {
    String text = string(value, field);
    if (text.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
      throw denied(field + " exceeds its supported bound");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw denied("Authority evidence bundle is not valid UTF-8");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)
        || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
      throw denied(field + " must be an object");
    }
    return (Map<String, Object>) map;
  }

  private static void requireFields(Map<String, Object> value, Set<String> fields, String field) {
    if (value == null || !value.keySet().equals(fields)) {
      throw denied(field + " has missing or unsupported fields");
    }
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text) || text.isEmpty()) {
      throw denied(field + " must be a nonempty string");
    }
    return text;
  }

  private static Map<String, Object> immutableObject(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, immutableValue(value)));
    return Map.copyOf(copy);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach(
          (key, nested) -> {
            if (!(key instanceof String text)) {
              throw denied("Object keys must be strings");
            }
            copy.put(text, immutableValue(nested));
          });
      return Map.copyOf(copy);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(StartSessionAuthorityEvidenceBundle::immutableValue).toList();
    }
    return value;
  }

  private static IllegalArgumentException denied(String message) {
    return new IllegalArgumentException(message);
  }

  /** Separate source provenance returned beside the original bundle bytes. */
  public record BundleReference(
      String bundleVersion, String sourceVersion, String sourceFence, String linearization) {
    public BundleReference {
      if (!BUNDLE_VERSION.equals(bundleVersion)) {
        throw denied("Unsupported Account bundle-reference version");
      }
      positiveDecimal(sourceVersion, "bundle sourceVersion");
      positiveDecimal(sourceFence, "bundle sourceFence");
      positiveU64(linearization, "bundle linearization");
    }
  }
}
