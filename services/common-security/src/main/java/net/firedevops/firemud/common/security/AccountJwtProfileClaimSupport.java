package net.firedevops.firemud.common.security;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Shared exact-shape helpers for the Account JWT profile validators. */
final class AccountJwtProfileClaimSupport {
  static final Set<String> REQUIRED_BASE_CLAIMS =
      Set.of(
          "iss",
          "sub",
          "jti",
          "accountId",
          "aud",
          "iat",
          "nbf",
          "exp",
          "tokenGeneration",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence");
  static final Set<String> AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AccountJwtProfileClaimSupport() {}

  static void requireClaimFields(
      Map<String, Object> claims, Set<String> required, Set<String> optional) {
    if (claims == null
        || !claims.keySet().containsAll(required)
        || !union(required, optional).containsAll(claims.keySet())) {
      throw invalid();
    }
  }

  static void requireCommonIdentity(Map<String, Object> claims, String issuer, String audience) {
    requireCommonIdentity(claims, issuer, audience, false);
  }

  static void requireDelegationCommonIdentity(
      Map<String, Object> claims, String issuer, String audience) {
    requireCommonIdentity(claims, issuer, audience, true);
  }

  private static void requireCommonIdentity(
      Map<String, Object> claims, String issuer, String audience, boolean decimalCounters) {
    if (!issuer.equals(claims.get("iss")) || !audience.equals(claims.get("aud"))) {
      throw invalid();
    }
    String subject = requireUuid(claims.get("sub"));
    String accountId = requireUuid(claims.get("accountId"));
    requireUuid(claims.get("jti"));
    if (!subject.equals(accountId)) {
      throw invalid();
    }
    requirePositiveLong(claims.get("iat"));
    requirePositiveLong(claims.get("nbf"));
    requirePositiveLong(claims.get("exp"));
    if (decimalCounters) {
      requirePositiveDecimalLong(claims.get("tokenGeneration"));
      requirePositiveDecimalLong(claims.get("issuanceFence"));
    } else {
      requirePositiveLong(claims.get("tokenGeneration"));
      requirePositiveLong(claims.get("issuanceFence"));
    }
  }

  static String requireUuid(Object value) {
    if (!(value instanceof String text)) {
      throw invalid();
    }
    try {
      UUID uuid = UUID.fromString(text);
      if (!uuid.toString().equals(text) || new UUID(0L, 0L).equals(uuid)) {
        throw invalid();
      }
      return text;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  static long requirePositiveLong(Object value) {
    if (!(value instanceof Number number)) {
      throw invalid();
    }
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      if (result <= 0L) {
        throw invalid();
      }
      return result;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw invalid();
    }
  }

  static Map<String, Object> requireObjectMap(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      throw invalid();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid();
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  static Map<String, Long> requireGenerationMap(Object value) {
    return requireGenerationMap(value, false);
  }

  private static Map<String, Long> requireGenerationMap(Object value, boolean decimalCounters) {
    Map<String, Object> source = requireObjectMap(value);
    Map<String, Long> result = new LinkedHashMap<>();
    source.forEach(
        (key, generation) -> {
          requireUuid(key);
          result.put(
              key,
              decimalCounters
                  ? requirePositiveDecimalLong(generation)
                  : requirePositiveLong(generation));
        });
    return result;
  }

  static void requireRoleList(Object value) {
    if (!(value instanceof List<?> roles)) {
      throw invalid();
    }
    Set<String> distinct = new java.util.HashSet<>();
    for (Object role : roles) {
      if (!(role instanceof String text)
          || text.isBlank()
          || text.length() > 64
          || text.codePoints().anyMatch(Character::isISOControl)
          || !distinct.add(text)) {
        throw invalid();
      }
    }
  }

  static Map<String, Object> requireAuthorityTuple(
      Object value,
      boolean allowTenantBillingCutoff,
      boolean requireUnscoped,
      boolean requireAccountCutoffForAdvancedGeneration) {
    return requireAuthorityTuple(
        value,
        allowTenantBillingCutoff,
        requireUnscoped,
        requireAccountCutoffForAdvancedGeneration,
        false);
  }

  static Map<String, Object> requireDelegationAuthorityTuple(
      Object value,
      boolean allowTenantBillingCutoff,
      boolean requireUnscoped,
      boolean requireAccountCutoffForAdvancedGeneration) {
    return requireAuthorityTuple(
        value,
        allowTenantBillingCutoff,
        requireUnscoped,
        requireAccountCutoffForAdvancedGeneration,
        true);
  }

  private static Map<String, Object> requireAuthorityTuple(
      Object value,
      boolean allowTenantBillingCutoff,
      boolean requireUnscoped,
      boolean requireAccountCutoffForAdvancedGeneration,
      boolean decimalCounters) {
    Map<String, Object> tuple = requireObjectMap(value);
    Set<String> allowed = new java.util.HashSet<>(AUTHORITY_TUPLE_FIELDS);
    allowed.add("accountSecurityCutoff");
    if (allowTenantBillingCutoff) {
      allowed.add("tenantBillingCutoff");
    }
    if (!allowed.containsAll(tuple.keySet())
        || !tuple.keySet().containsAll(AUTHORITY_TUPLE_FIELDS)) {
      throw invalid();
    }
    requirePositiveCounter(tuple.get("issuerAuthGeneration"), decimalCounters);
    requirePositiveCounter(tuple.get("accountAuthorityGeneration"), decimalCounters);
    Map<String, Long> tenantGenerations =
        requireGenerationMap(tuple.get("tenantAuthorityGeneration"), decimalCounters);
    Map<String, Long> membershipGenerations =
        requireGenerationMap(tuple.get("membershipAuthorityGeneration"), decimalCounters);
    if (requireUnscoped && (!tenantGenerations.isEmpty() || !membershipGenerations.isEmpty())) {
      throw invalid();
    }
    if (!(tuple.get("privateRealmGrantVersions") instanceof List<?> grants)) {
      throw invalid();
    }
    if (requireUnscoped && !grants.isEmpty()) {
      throw invalid();
    }
    requireAuthorityTupleByteBound(tuple);
    long accountGeneration =
        requirePositiveCounter(tuple.get("accountAuthorityGeneration"), decimalCounters);
    Object accountCutoff = tuple.get("accountSecurityCutoff");
    if (tuple.containsKey("accountSecurityCutoff")) {
      if (accountCutoff == null) {
        throw invalid();
      }
      validateAccountSecurityCutoff(accountCutoff, accountGeneration);
    } else if (requireAccountCutoffForAdvancedGeneration && accountGeneration != 1L) {
      throw invalid();
    }
    Object billingCutoff = tuple.get("tenantBillingCutoff");
    if (tuple.containsKey("tenantBillingCutoff")) {
      if (!allowTenantBillingCutoff || billingCutoff == null) {
        throw invalid();
      }
      validateTenantBillingCutoff(billingCutoff, decimalCounters);
    }
    return tuple;
  }

  static Map<String, Long> requireVersionMap(Object value) {
    return requireGenerationMap(value);
  }

  static Map<String, Long> requireDelegationVersionMap(Object value) {
    return requireGenerationMap(value, true);
  }

  private static long requirePositiveCounter(Object value, boolean decimalCounters) {
    return decimalCounters ? requirePositiveDecimalLong(value) : requirePositiveLong(value);
  }

  private static long requirePositiveDecimalLong(Object value) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(value).longValueExact();
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw invalid();
    }
  }

  static void validateAccountSecurityCutoff(Object value, long accountGeneration) {
    Map<String, Object> cutoff = requireObjectMap(value);
    if (!cutoff
        .keySet()
        .equals(Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence"))) {
      throw invalid();
    }
    if (!(cutoff.get("accountAuthorityGeneration") instanceof String generation)
        || !(cutoff.get("outboxStreamKey") instanceof String streamKey)
        || !(cutoff.get("outboxSequence") instanceof String sequence)) {
      throw invalid();
    }
    try {
      GameSessionAccountDelegationProfile.AccountSecurityCutoff validatedCutoff =
          new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
              generation, streamKey, sequence);
      if (accountGeneration <= 1L
          || !Long.toString(accountGeneration)
              .equals(validatedCutoff.accountAuthorityGeneration())) {
        throw invalid();
      }
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static void validateTenantBillingCutoff(Object value, boolean decimalCounters) {
    Map<String, Object> cutoffs = requireObjectMap(value);
    if (cutoffs.isEmpty()) {
      throw invalid();
    }
    cutoffs.forEach(
        (tenantId, cutoffValue) -> {
          requireUuid(tenantId);
          Map<String, Object> cutoff = requireObjectMap(cutoffValue);
          if (!cutoff
              .keySet()
              .equals(
                  Set.of(
                      "tenantAuthorityGeneration",
                      "tenantBillingSequence",
                      "outboxStreamKey",
                      "outboxSequence"))) {
            throw invalid();
          }
          requirePositiveCounter(cutoff.get("tenantAuthorityGeneration"), decimalCounters);
          requirePositiveCounter(cutoff.get("tenantBillingSequence"), decimalCounters);
          requirePositiveCounter(cutoff.get("outboxSequence"), decimalCounters);
          requireBoundedText(cutoff.get("outboxStreamKey"), 2048);
        });
  }

  static void requireBoundedText(Object value, int maximumLength) {
    if (!(value instanceof String text)
        || text.isBlank()
        || text.length() > maximumLength
        || text.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid();
    }
  }

  static void requireExactAccountAuthorityStream(Object value, String accountId) {
    String expected = "account:auth-authority:v1:account/" + requireUuid(accountId);
    if (!expected.equals(value)) {
      throw invalid();
    }
  }

  static void requireExactTenantAuthorityStream(Object value, String tenantId) {
    String expected = "account:auth-authority:v1:tenant/" + requireUuid(tenantId);
    if (!expected.equals(value)) {
      throw invalid();
    }
  }

  static AccountAsymmetricJwtVerifier.VerificationException invalid() {
    return new AccountAsymmetricJwtVerifier.VerificationException();
  }

  private static Set<String> union(Set<String> required, Set<String> optional) {
    Set<String> result = new java.util.HashSet<>(Objects.requireNonNull(required));
    result.addAll(Objects.requireNonNull(optional));
    return result;
  }

  private static void requireAuthorityTupleByteBound(Map<String, Object> tuple) {
    byte[] canonicalBytes;
    try {
      canonicalBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(tuple));
    } catch (IOException failure) {
      throw invalid();
    }
    if (canonicalBytes.length > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw invalid();
    }
  }
}
