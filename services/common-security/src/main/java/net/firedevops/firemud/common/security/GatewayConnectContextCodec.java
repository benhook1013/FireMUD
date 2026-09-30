package net.firedevops.firemud.common.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies and validates the registered Gateway-to-Game Session connect-context schema.
 *
 * <p>Signature verification deliberately precedes JSON parsing and semantic interpretation. This
 * codec accepts only the canonical selected-target schema; it does not accept JWTs, aliases,
 * partial routing claims, or legacy numeric identifiers.
 */
public final class GatewayConnectContextCodec {
  public static final String AUDIENCE = "game-session";
  public static final String RECIPIENT = "game-session-service";
  public static final long MAX_CONTEXT_LIFETIME_SECONDS = 30L;
  public static final long MAX_CLOCK_SKEW_SECONDS = 5L;

  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "accountId",
          "tenantId",
          "realmId",
          "worldSlug",
          "realmSlug",
          "playableStateNamespaceId",
          "playableStateScope",
          "gameInstanceId",
          "pointerVersion",
          "catalogRevision",
          "connectScopeId",
          "connectRequestId",
          "audience",
          "recipient",
          "authorityTuple",
          "membershipVersion",
          "replayAdmissionFence",
          "connectTokenJti",
          "issuedAt",
          "verifiedAt",
          "expiresAt",
          "gatewayRequestId");
  private static final Set<String> SOURCE_REQUIRED_FIELDS =
      Set.of(
          "iss",
          "aud",
          "iat",
          "exp",
          "jti",
          "accountId",
          "tenantId",
          "realmId",
          "worldSlug",
          "realmSlug",
          "playableStateNamespaceId",
          "playableStateScope",
          "gameInstanceId",
          "pointerVersion",
          "catalogRevision",
          "connectScopeId",
          "requestId",
          "authorityTuple",
          "membershipVersion",
          "replayAdmissionFence");
  private static final Set<String> CONDITIONAL_FIELDS =
      Set.of("playtestLifecycleId", "playtestStateGeneration");
  private static final Set<String> AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> ACCOUNT_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> TENANT_CUTOFF_FIELDS =
      Set.of(
          "tenantAuthorityGeneration",
          "tenantBillingSequence",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> PRIVATE_GRANT_FIELDS =
      Set.of("tenantId", "worldSlug", "realmSlug", "playtestLifecycleId", "grantVersion");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

  private GatewayConnectContextCodec() {}

  /**
   * Projects an already signature/profile-verified Account gameplay-connect claim map into the
   * registered Gateway context shape. This method is not an Account JWT verifier or an
   * authentication grant; callers must complete Account signature, issuer, audience, time, key, and
   * profile verification before invoking it. It validates the closed source shape again, preserves
   * all selected-target and authority evidence, maps only the registered claim names, and
   * calculates a deadline that cannot outlive the source token.
   */
  public static Map<String, Object> projectVerifiedAccountGameplayConnectClaims(
      Map<String, ?> verifiedSourceClaims, long gatewayVerifiedAt, String gatewayRequestId) {
    Map<String, Object> source = normalizeObject(verifiedSourceClaims, "Account claims");
    validateSourceClaims(source, gatewayVerifiedAt);
    try {
      Instant.ofEpochSecond(gatewayVerifiedAt);
    } catch (java.time.DateTimeException ex) {
      throw invalid("Gateway verification time is outside the supported epoch-second range");
    }
    Map<String, Object> context = new LinkedHashMap<>();
    for (String field :
        List.of(
            "accountId",
            "tenantId",
            "realmId",
            "worldSlug",
            "realmSlug",
            "playableStateNamespaceId",
            "playableStateScope",
            "gameInstanceId",
            "pointerVersion",
            "catalogRevision",
            "connectScopeId",
            "authorityTuple",
            "membershipVersion",
            "replayAdmissionFence")) {
      context.put(field, source.get(field));
    }
    if (source.containsKey("playtestLifecycleId")) {
      context.put("playtestLifecycleId", source.get("playtestLifecycleId"));
      context.put("playtestStateGeneration", source.get("playtestStateGeneration"));
    }
    context.put("connectRequestId", source.get("requestId"));
    context.put("audience", AUDIENCE);
    context.put("recipient", RECIPIENT);
    context.put("connectTokenJti", source.get("jti"));
    context.put("issuedAt", source.get("iat"));
    context.put("verifiedAt", BigInteger.valueOf(gatewayVerifiedAt));
    context.put(
        "expiresAt",
        BigInteger.valueOf(
            requireGatewayDeadline(
                requirePositiveInteger(source, "iat").longValueExact(),
                requirePositiveInteger(source, "exp").longValueExact(),
                gatewayVerifiedAt)));
    context.put("gatewayRequestId", gatewayRequestId);
    validate(context, Instant.ofEpochSecond(gatewayVerifiedAt));
    return immutableObject(context);
  }

  /**
   * Verifies a compact Gateway Ed25519 JWS, then validates its complete semantic schema and time
   * window. Expiration has no skew grace: skew cannot extend the signed lifetime.
   */
  public static GatewayConnectContext verifyAndDecode(
      String signedEnvelope,
      Map<String, ? extends java.security.PublicKey> gatewayVerificationKeys,
      Clock clock) {
    if (clock == null) {
      throw new IllegalArgumentException("clock is required");
    }
    GatewayConnectContextSignature.VerifiedContext verified =
        GatewayConnectContextSignature.verify(signedEnvelope, gatewayVerificationKeys);
    Map<String, Object> claims = parsePayload(verified.payload());
    validate(claims, clock.instant());
    return new GatewayConnectContext(signedEnvelope, verified.payload(), verified.kid(), claims);
  }

  /**
   * Computes the Gateway assertion deadline without changing the Account-issued time anchor. The
   * caller must reject an already-expired source token; no configured skew is added here.
   */
  public static long requireGatewayDeadline(
      long sourceIssuedAt, long sourceExpiresAt, long gatewayVerifiedAt) {
    if (sourceIssuedAt <= 0 || sourceExpiresAt <= sourceIssuedAt || gatewayVerifiedAt <= 0) {
      throw invalid("invalid source connect-token lifetime");
    }
    if (BigInteger.valueOf(sourceIssuedAt)
            .subtract(BigInteger.valueOf(gatewayVerifiedAt))
            .compareTo(BigInteger.valueOf(MAX_CLOCK_SKEW_SECONDS))
        > 0) {
      throw invalid("source connect-token issuedAt exceeds the permitted future clock skew");
    }
    long lifetimeDeadline;
    try {
      lifetimeDeadline = Math.addExact(sourceIssuedAt, MAX_CONTEXT_LIFETIME_SECONDS);
    } catch (ArithmeticException ex) {
      throw invalid("source connect-token issuedAt is outside the supported range");
    }
    if (sourceExpiresAt > lifetimeDeadline) {
      throw invalid("source connect-token lifetime exceeds 30 seconds");
    }
    long deadline = Math.min(sourceExpiresAt, lifetimeDeadline);
    if (gatewayVerifiedAt >= deadline) {
      throw invalid("source connect token is expired");
    }
    return deadline;
  }

  private static Map<String, Object> parsePayload(byte[] payload) {
    try (var parser = JSON.getFactory().createParser(payload)) {
      Object decoded = JSON.readValue(parser, Object.class);
      if (parser.nextToken() != null || !(decoded instanceof Map<?, ?> rawObject)) {
        throw invalid("Gateway context payload must be one JSON object");
      }
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawObject.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalid("Gateway context object keys must be strings");
        }
        result.put(key, entry.getValue());
      }
      return result;
    } catch (IOException ex) {
      throw invalid("Gateway context payload is not valid JSON");
    }
  }

  private static void validate(Map<String, Object> claims, Instant now) {
    Set<String> allowed = new java.util.HashSet<>(REQUIRED_FIELDS);
    allowed.addAll(CONDITIONAL_FIELDS);
    if (!allowed.containsAll(claims.keySet()) || !claims.keySet().containsAll(REQUIRED_FIELDS)) {
      throw invalid("Gateway context fields do not match the registered schema");
    }

    requireUuid(claims, "accountId");
    String tenantId = requireUuid(claims, "tenantId");
    requireUuid(claims, "realmId");
    requireUuid(claims, "gameInstanceId");
    requireCanonicalSlug(claims, "worldSlug");
    requireCanonicalSlug(claims, "realmSlug");
    requireUuid(claims, "playableStateNamespaceId");
    requireOneOf(
        claims,
        "playableStateScope",
        "PLAYABLE_STATE_SCOPE_SHARED",
        "PLAYABLE_STATE_SCOPE_ISOLATED");
    requirePositiveInteger(claims, "pointerVersion");
    requirePositiveInteger(claims, "catalogRevision");
    requireText(claims, "connectScopeId");
    requireText(claims, "connectRequestId");
    requireText(claims, "connectTokenJti");
    requireText(claims, "gatewayRequestId");
    requireEqual(claims, "audience", AUDIENCE);
    requireEqual(claims, "recipient", RECIPIENT);

    boolean hasLifecycle = claims.containsKey("playtestLifecycleId");
    boolean hasGeneration = claims.containsKey("playtestStateGeneration");
    if (hasLifecycle != hasGeneration) {
      throw invalid("playtest lifecycle and generation must be present together");
    }
    String lifecycleId = null;
    if (hasLifecycle) {
      lifecycleId = requireUuid(claims, "playtestLifecycleId");
      requirePositiveInteger(claims, "playtestStateGeneration");
    }

    Map<String, Object> tuple = requireObject(claims.get("authorityTuple"), "authorityTuple");
    validateAuthorityTuple(tuple, tenantId, claims, lifecycleId);
    Map<String, Object> membershipVersion =
        requireObject(claims.get("membershipVersion"), "membershipVersion");
    requireSingleTenantCounter(membershipVersion, tenantId, "membershipVersion");
    requirePositiveInteger(claims, "replayAdmissionFence");

    BigInteger issuedAt = requirePositiveInteger(claims, "issuedAt");
    BigInteger verifiedAt = requirePositiveInteger(claims, "verifiedAt");
    BigInteger expiresAt = requirePositiveInteger(claims, "expiresAt");
    BigInteger maxLifetime = BigInteger.valueOf(MAX_CONTEXT_LIFETIME_SECONDS);
    BigInteger duration = expiresAt.subtract(issuedAt);
    if (duration.signum() <= 0 || duration.compareTo(maxLifetime) > 0) {
      throw invalid("Gateway context lifetime must be positive and no longer than 30 seconds");
    }
    if (verifiedAt.compareTo(expiresAt) >= 0
        || issuedAt.subtract(verifiedAt).compareTo(BigInteger.valueOf(MAX_CLOCK_SKEW_SECONDS))
            > 0) {
      throw invalid("Gateway context verification time is outside its signed lifetime");
    }
    BigInteger nowSeconds = BigInteger.valueOf(now.getEpochSecond());
    BigInteger latestAllowedVerification =
        nowSeconds.add(BigInteger.valueOf(MAX_CLOCK_SKEW_SECONDS));
    if (verifiedAt.compareTo(latestAllowedVerification) > 0
        || issuedAt.compareTo(latestAllowedVerification) > 0) {
      throw invalid("Gateway context time is too far in the future");
    }
    if (expiresAt.compareTo(nowSeconds) <= 0) {
      throw invalid("Gateway context is expired");
    }
    try {
      Instant.ofEpochSecond(issuedAt.longValueExact());
      Instant.ofEpochSecond(verifiedAt.longValueExact());
      Instant.ofEpochSecond(expiresAt.longValueExact());
    } catch (ArithmeticException | java.time.DateTimeException ex) {
      throw invalid("Gateway context time is outside the supported epoch-second range");
    }
  }

  private static void validateAuthorityTuple(
      Map<String, Object> tuple, String tenantId, Map<String, Object> context, String lifecycleId) {
    Set<String> allowed = new java.util.HashSet<>(AUTHORITY_TUPLE_FIELDS);
    allowed.add("accountSecurityCutoff");
    allowed.add("tenantBillingCutoff");
    if (!allowed.containsAll(tuple.keySet())
        || !tuple.keySet().containsAll(AUTHORITY_TUPLE_FIELDS)) {
      throw invalid("authorityTuple fields do not match the canonical schema");
    }
    requirePositiveInteger(tuple, "issuerAuthGeneration");
    requirePositiveInteger(tuple, "accountAuthorityGeneration");
    requireSingleTenantCounter(
        requireObject(
            tuple.get("tenantAuthorityGeneration"), "authorityTuple.tenantAuthorityGeneration"),
        tenantId,
        "authorityTuple.tenantAuthorityGeneration");
    requireSingleTenantCounter(
        requireObject(
            tuple.get("membershipAuthorityGeneration"),
            "authorityTuple.membershipAuthorityGeneration"),
        tenantId,
        "authorityTuple.membershipAuthorityGeneration");

    Object grantsValue = tuple.get("privateRealmGrantVersions");
    if (!(grantsValue instanceof List<?> grants)) {
      throw invalid("authorityTuple.privateRealmGrantVersions must be an array");
    }
    boolean hasLifecycle = lifecycleId != null;
    if (hasLifecycle && grants.size() != 1 || !hasLifecycle && !grants.isEmpty()) {
      throw invalid("private-realm grant evidence does not match target lifecycle shape");
    }
    if (hasLifecycle) {
      Map<String, Object> grant = requireObject(grants.getFirst(), "private realm grant");
      requireExactFields(grant, PRIVATE_GRANT_FIELDS, "private realm grant");
      requireEqual(grant, "tenantId", tenantId);
      requireEqual(grant, "worldSlug", requireText(context, "worldSlug"));
      requireEqual(grant, "realmSlug", requireText(context, "realmSlug"));
      requireEqual(grant, "playtestLifecycleId", lifecycleId);
      requirePositiveInteger(grant, "grantVersion");
    }

    if (tuple.containsKey("accountSecurityCutoff")) {
      Map<String, Object> cutoff =
          requireObject(tuple.get("accountSecurityCutoff"), "authorityTuple.accountSecurityCutoff");
      requireExactFields(cutoff, ACCOUNT_CUTOFF_FIELDS, "accountSecurityCutoff");
      if (!requirePositiveInteger(cutoff, "accountAuthorityGeneration")
          .equals(requirePositiveInteger(tuple, "accountAuthorityGeneration"))) {
        throw invalid("accountSecurityCutoff generation does not match authorityTuple");
      }
      requireEqual(
          cutoff,
          "outboxStreamKey",
          "account:auth-authority:v1:account/" + requireUuid(context, "accountId"));
      requirePositiveInteger(cutoff, "outboxSequence");
    }
    if (tuple.containsKey("tenantBillingCutoff")) {
      Map<String, Object> cutoffs =
          requireObject(tuple.get("tenantBillingCutoff"), "authorityTuple.tenantBillingCutoff");
      if (cutoffs.size() != 1 || !cutoffs.containsKey(tenantId)) {
        throw invalid("tenantBillingCutoff must contain only the selected tenant");
      }
      Map<String, Object> cutoff =
          requireObject(cutoffs.get(tenantId), "authorityTuple.tenantBillingCutoff entry");
      requireExactFields(cutoff, TENANT_CUTOFF_FIELDS, "tenantBillingCutoff entry");
      if (!requirePositiveInteger(cutoff, "tenantAuthorityGeneration")
          .equals(
              requirePositiveInteger(
                  requireObject(
                      tuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration"),
                  tenantId))) {
        throw invalid("tenantBillingCutoff generation does not match authorityTuple");
      }
      requireNonNegativeInteger(cutoff, "tenantBillingSequence");
      requireEqual(cutoff, "outboxStreamKey", "account:auth-authority:v1:tenant/" + tenantId);
      requirePositiveInteger(cutoff, "outboxSequence");
    }
  }

  private static void validateSourceClaims(Map<String, Object> source, long gatewayVerifiedAt) {
    Set<String> allowed = new java.util.HashSet<>(SOURCE_REQUIRED_FIELDS);
    allowed.addAll(CONDITIONAL_FIELDS);
    if (!allowed.containsAll(source.keySet())
        || !source.keySet().containsAll(SOURCE_REQUIRED_FIELDS)) {
      throw invalid("Account gameplay-connect fields do not match the registered source schema");
    }
    requireText(source, "iss");
    requireEqual(source, "aud", "gameplay-connect");
    requireUuid(source, "accountId");
    String tenantId = requireUuid(source, "tenantId");
    requireUuid(source, "realmId");
    requireUuid(source, "gameInstanceId");
    requireCanonicalSlug(source, "worldSlug");
    requireCanonicalSlug(source, "realmSlug");
    requireUuid(source, "playableStateNamespaceId");
    requireOneOf(
        source,
        "playableStateScope",
        "PLAYABLE_STATE_SCOPE_SHARED",
        "PLAYABLE_STATE_SCOPE_ISOLATED");
    requirePositiveInteger(source, "pointerVersion");
    requirePositiveInteger(source, "catalogRevision");
    requireText(source, "connectScopeId");
    requireText(source, "requestId");
    requireText(source, "jti");

    boolean hasLifecycle = source.containsKey("playtestLifecycleId");
    boolean hasGeneration = source.containsKey("playtestStateGeneration");
    if (hasLifecycle != hasGeneration) {
      throw invalid("Account playtest lifecycle and generation must be present together");
    }
    String lifecycleId = null;
    if (hasLifecycle) {
      lifecycleId = requireUuid(source, "playtestLifecycleId");
      requirePositiveInteger(source, "playtestStateGeneration");
    }

    validateAuthorityTuple(
        requireObject(source.get("authorityTuple"), "authorityTuple"),
        tenantId,
        source,
        lifecycleId);
    requireSingleTenantCounter(
        requireObject(source.get("membershipVersion"), "membershipVersion"),
        tenantId,
        "membershipVersion");
    requirePositiveInteger(source, "replayAdmissionFence");
    BigInteger issuedAt = requirePositiveInteger(source, "iat");
    BigInteger expiresAt = requirePositiveInteger(source, "exp");
    try {
      requireGatewayDeadline(
          issuedAt.longValueExact(), expiresAt.longValueExact(), gatewayVerifiedAt);
    } catch (ArithmeticException ex) {
      throw invalid("Account gameplay-connect time is outside the supported epoch-second range");
    }
  }

  private static Map<String, Object> normalizeObject(Map<String, ?> source, String name) {
    if (source == null) {
      throw invalid(name + " are required");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) source).entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid(name + " keys must be strings");
      }
      result.put(key, normalizeJsonValue(entry.getValue()));
    }
    return result;
  }

  private static Object normalizeJsonValue(Object value) {
    if (value instanceof BigInteger integer) {
      return integer;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return BigInteger.valueOf(((Number) value).longValue());
    }
    if (value instanceof Map<?, ?> rawObject) {
      Map<String, Object> nested = new LinkedHashMap<>();
      rawObject.forEach(
          (key, nestedValue) -> {
            if (!(key instanceof String text)) {
              throw invalid("Account gameplay-connect object keys must be strings");
            }
            nested.put(text, normalizeJsonValue(nestedValue));
          });
      return nested;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(GatewayConnectContextCodec::normalizeJsonValue).toList();
    }
    if (value instanceof String || value instanceof Boolean || value == null) {
      return value;
    }
    throw invalid("Account gameplay-connect claims contain a non-JSON or non-integral value");
  }

  private static void requireSingleTenantCounter(
      Map<String, Object> counters, String tenantId, String field) {
    if (counters.size() != 1 || !counters.containsKey(tenantId)) {
      throw invalid(field + " must contain exactly the selected tenant");
    }
    requirePositiveInteger(counters, tenantId);
  }

  private static String requireUuid(Map<String, Object> object, String name) {
    String value = requireText(object, name);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw invalid(name + " must be a canonical UUID");
      }
    } catch (IllegalArgumentException ex) {
      throw invalid(name + " must be a canonical UUID");
    }
    return value;
  }

  private static String requireCanonicalSlug(Map<String, Object> object, String name) {
    String value = requireText(object, name);
    GameplayRoutingBundleValidator.requireCanonicalSlug(value, name);
    return value;
  }

  private static String requireText(Map<String, Object> object, String name) {
    Object value = object.get(name);
    if (!(value instanceof String text)
        || text.isEmpty()
        || text.length() > 512
        || !text.equals(text.strip())
        || text.chars().anyMatch(Character::isISOControl)) {
      throw invalid(name + " must be non-empty bounded text");
    }
    return text;
  }

  private static String requireOneOf(
      Map<String, Object> object, String name, String first, String second) {
    String value = requireText(object, name);
    if (!first.equals(value) && !second.equals(value)) {
      throw invalid(name + " has an unsupported value");
    }
    return value;
  }

  private static void requireEqual(Map<String, Object> object, String name, String expected) {
    if (!expected.equals(requireText(object, name))) {
      throw invalid(name + " does not match its registered value");
    }
  }

  private static BigInteger requirePositiveInteger(Map<String, Object> object, String name) {
    BigInteger value = requireInteger(object, name);
    if (value.signum() <= 0) {
      throw invalid(name + " must be positive");
    }
    return value;
  }

  private static BigInteger requireNonNegativeInteger(Map<String, Object> object, String name) {
    BigInteger value = requireInteger(object, name);
    if (value.signum() < 0) {
      throw invalid(name + " must not be negative");
    }
    return value;
  }

  private static BigInteger requireInteger(Map<String, Object> object, String name) {
    Object value = object.get(name);
    if (!(value instanceof BigInteger integer)) {
      throw invalid(name + " must be an exact JSON integer");
    }
    return integer;
  }

  private static Map<String, Object> requireObject(Object value, String name) {
    if (!(value instanceof Map<?, ?> rawObject)) {
      throw invalid(name + " must be a JSON object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawObject.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid(name + " keys must be strings");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static void requireExactFields(
      Map<String, Object> object, Set<String> fields, String name) {
    if (!object.keySet().equals(fields)) {
      throw invalid(name + " fields do not match the canonical schema");
    }
  }

  private static Map<String, Object> immutableObject(Map<String, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> rawObject) {
      Map<String, Object> nested = new LinkedHashMap<>();
      rawObject.forEach((key, child) -> nested.put((String) key, immutableValue(child)));
      return Collections.unmodifiableMap(nested);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(GatewayConnectContextCodec::immutableValue).toList();
    }
    return value;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
