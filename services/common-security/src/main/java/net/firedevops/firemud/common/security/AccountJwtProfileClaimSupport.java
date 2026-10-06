package net.firedevops.firemud.common.security;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    requirePositiveCounter(claims.get("tokenGeneration"));
    requirePositiveCounter(claims.get("issuanceFence"));
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
    try {
      return AccountJwtExactValues.positiveCounter(value).longValueExact();
    } catch (IllegalArgumentException | ArithmeticException failure) {
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

  static BigInteger requirePositiveCounter(Object value) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(value);
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  static Map<String, BigInteger> requireGenerationMap(Object value) {
    Map<String, Object> source = requireObjectMap(value);
    Map<String, BigInteger> result = new LinkedHashMap<>();
    source.forEach(
        (key, generation) -> {
          requireUuid(key);
          result.put(key, requirePositiveCounter(generation));
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
      boolean requireAccountCutoffForAdvancedGeneration,
      long maximumAuthorityTupleBytes) {
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
    requirePositiveCounter(tuple.get("issuerAuthGeneration"));
    requirePositiveCounter(tuple.get("accountAuthorityGeneration"));
    Map<String, BigInteger> tenantGenerations =
        requireGenerationMap(tuple.get("tenantAuthorityGeneration"));
    Map<String, BigInteger> membershipGenerations =
        requireGenerationMap(tuple.get("membershipAuthorityGeneration"));
    if (requireUnscoped && (!tenantGenerations.isEmpty() || !membershipGenerations.isEmpty())) {
      throw invalid();
    }
    if (!(tuple.get("privateRealmGrantVersions") instanceof List<?> grants)) {
      throw invalid();
    }
    if (requireUnscoped && !grants.isEmpty()) {
      throw invalid();
    }
    requireAuthorityTupleByteBound(tuple, maximumAuthorityTupleBytes);
    BigInteger accountGeneration = requirePositiveCounter(tuple.get("accountAuthorityGeneration"));
    Object accountCutoff = tuple.get("accountSecurityCutoff");
    if (tuple.containsKey("accountSecurityCutoff")) {
      if (accountCutoff == null) {
        throw invalid();
      }
      validateAccountSecurityCutoff(accountCutoff, accountGeneration);
    } else if (requireAccountCutoffForAdvancedGeneration
        && !accountGeneration.equals(BigInteger.ONE)) {
      throw invalid();
    }
    Object billingCutoff = tuple.get("tenantBillingCutoff");
    if (tuple.containsKey("tenantBillingCutoff")) {
      if (!allowTenantBillingCutoff || billingCutoff == null) {
        throw invalid();
      }
      validateTenantBillingCutoff(billingCutoff);
    }
    return tuple;
  }

  static Map<String, BigInteger> requireVersionMap(Object value) {
    Map<String, Object> source = requireObjectMap(value);
    Map<String, BigInteger> result = new LinkedHashMap<>();
    source.forEach(
        (key, version) -> {
          requireUuid(key);
          try {
            result.put(key, AccountJwtExactValues.nonNegativeDecimalCounter(version));
          } catch (IllegalArgumentException failure) {
            throw invalid();
          }
        });
    return result;
  }

  static void validateAccountSecurityCutoff(Object value, BigInteger accountGeneration) {
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
    if (accountGeneration.compareTo(BigInteger.ONE) <= 0
        || !accountGeneration.toString().equals(generation)
        || streamKey.length() > 2048
        || !streamKey.startsWith("account:auth-authority:v1:account/")
        || streamKey.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid();
    }
    try {
      AccountJwtExactValues.positiveDecimalCounter(sequence);
      requireUuid(streamKey.substring("account:auth-authority:v1:account/".length()));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static void validateTenantBillingCutoff(Object value) {
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
          requirePositiveCounter(cutoff.get("tenantAuthorityGeneration"));
          requirePositiveCounter(cutoff.get("tenantBillingSequence"));
          requirePositiveCounter(cutoff.get("outboxSequence"));
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

  static AccountAsymmetricJwtVerifier.VerificationException invalid() {
    return new AccountAsymmetricJwtVerifier.VerificationException();
  }

  private static Set<String> union(Set<String> required, Set<String> optional) {
    Set<String> result = new java.util.HashSet<>(Objects.requireNonNull(required));
    result.addAll(Objects.requireNonNull(optional));
    return result;
  }

  private static void requireAuthorityTupleByteBound(
      Map<String, Object> tuple, long maximumAuthorityTupleBytes) {
    byte[] canonicalBytes;
    try {
      canonicalBytes = AccountJwtExactValues.losslessCanonicalBytes(tuple);
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
    if (maximumAuthorityTupleBytes <= 0L || canonicalBytes.length > maximumAuthorityTupleBytes) {
      throw invalid();
    }
  }
}
