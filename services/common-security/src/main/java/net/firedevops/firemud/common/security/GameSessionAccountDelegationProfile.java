package net.firedevops.firemud.common.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bounded initial non-tenant and distinct public-production tenant-bound Account delegation forms.
 *
 * <p>This declares claim shape only. It does not authenticate Game Session, verify a signature,
 * prove signer promotion, establish current Account evidence, or authorize a call. It is separate
 * from Social & Friends delegations and from generic backend JWT conventions.
 */
public final class GameSessionAccountDelegationProfile {
  public static final String PROFILE = "game-session-account-delegation";
  public static final String TYPE = "private_player_delegation";
  public static final String ISSUER = "firemud-account-service";
  public static final String AUDIENCE = "account-service";
  public static final String TOKEN_TYPE_CLAIM = "tokenType";
  public static final String TOKEN_PROFILE_CLAIM = "tokenProfile";
  public static final long MAX_TOKEN_LIFETIME_SECONDS = 300L;
  public static final int MAX_COMPACT_JWT_BYTES = 16 * 1024;
  public static final int MAX_AUTHORITY_TUPLE_BYTES = 4 * 1024;
  public static final int MAX_REGISTRY_RECORD_BYTES = 16 * 1024;
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

  private GameSessionAccountDelegationProfile() {}

  /** Checks the supported public-production tenant-bound form, never an initial LOGIN tuple. */
  public static void requirePublicTenantBoundAuthority(
      String tenantId, Map<String, Object> tuple, Map<String, ?> membershipVersion) {
    AccountJwtProfileClaimSupport.requireUuid(tenantId);
    AccountJwtProfileClaimSupport.requireDelegationAuthorityTuple(tuple, true, false, true);
    for (String field :
        java.util.List.of("tenantAuthorityGeneration", "membershipAuthorityGeneration")) {
      if (!AccountJwtProfileClaimSupport.requireDelegationVersionMap(tuple.get(field))
          .keySet()
          .equals(java.util.Set.of(tenantId))) throw AccountJwtProfileClaimSupport.invalid();
    }
    if (!AccountJwtProfileClaimSupport.requireDelegationVersionMap(membershipVersion)
            .keySet()
            .equals(java.util.Set.of(tenantId))
        || !java.util.List.of().equals(tuple.get("privateRealmGrantVersions"))) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
    if (tuple.containsKey("tenantBillingCutoff")) {
      Map<String, Object> cutoff =
          AccountJwtProfileClaimSupport.requireObjectMap(tuple.get("tenantBillingCutoff"));
      if (!cutoff.keySet().equals(java.util.Set.of(tenantId)))
        throw AccountJwtProfileClaimSupport.invalid();
      var value = AccountJwtProfileClaimSupport.requireObjectMap(cutoff.get(tenantId));
      var generation =
          AccountJwtProfileClaimSupport.requireObjectMap(tuple.get("tenantAuthorityGeneration"));
      if (!java.util.Objects.equals(
          generation.get(tenantId), value.get("tenantAuthorityGeneration"))) {
        throw AccountJwtProfileClaimSupport.invalid();
      }
    }
  }

  /** Returns the exact required unscoped authority tuple for an initial credential login. */
  public static Map<String, Object> authorityTuple(
      long issuerAuthGeneration, long accountAuthorityGeneration) {
    if (accountAuthorityGeneration != 1L) {
      throw new IllegalArgumentException(
          "Advanced Account authority requires its exact current security cutoff");
    }
    return authorityTuple(issuerAuthGeneration, accountAuthorityGeneration, Optional.empty());
  }

  /** Returns the exact tuple, carrying the Account cutoff only when source evidence proves it. */
  public static Map<String, Object> authorityTuple(
      long issuerAuthGeneration,
      long accountAuthorityGeneration,
      Optional<AccountSecurityCutoff> accountSecurityCutoff) {
    if (issuerAuthGeneration <= 0L || accountAuthorityGeneration <= 0L) {
      throw new IllegalArgumentException("Account delegation generations must be positive");
    }
    Objects.requireNonNull(accountSecurityCutoff, "Account cutoff applicability is required");
    if ((accountAuthorityGeneration == 1L) != accountSecurityCutoff.isEmpty()) {
      throw new IllegalArgumentException(
          "Account security cutoff presence does not match the source generation");
    }
    accountSecurityCutoff.ifPresent(
        cutoff -> {
          if (!Long.toString(accountAuthorityGeneration)
              .equals(cutoff.accountAuthorityGeneration())) {
            throw new IllegalArgumentException("Account security cutoff generation is stale");
          }
        });
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", Long.toString(issuerAuthGeneration));
    tuple.put("accountAuthorityGeneration", Long.toString(accountAuthorityGeneration));
    tuple.put("tenantAuthorityGeneration", Map.of());
    tuple.put("membershipAuthorityGeneration", Map.of());
    tuple.put("privateRealmGrantVersions", java.util.List.of());
    accountSecurityCutoff.ifPresent(cutoff -> tuple.put("accountSecurityCutoff", cutoff.toMap()));
    return Map.copyOf(tuple);
  }

  /** Exact optional Account cutoff value carried by this profile's authority tuple. */
  public record AccountSecurityCutoff(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {
    public AccountSecurityCutoff {
      requirePositiveDecimal(accountAuthorityGeneration, "cutoff generation");
      requirePositiveDecimal(outboxSequence, "cutoff outbox sequence");
      requireAccountAuthorityStream(outboxStreamKey);
      for (int index = 0; index < outboxStreamKey.length(); index++) {
        if (Character.isISOControl(outboxStreamKey.charAt(index))) {
          throw new IllegalArgumentException("Account security cutoff stream is malformed");
        }
      }
    }

    private static void requireAccountAuthorityStream(String value) {
      String prefix = "account:auth-authority:v1:account/";
      if (value == null || value.length() > 2048 || !value.startsWith(prefix)) {
        throw new IllegalArgumentException("Account security cutoff stream is malformed");
      }
      String accountId = value.substring(prefix.length());
      UUID parsed;
      try {
        parsed = UUID.fromString(accountId);
      } catch (IllegalArgumentException failure) {
        throw new IllegalArgumentException("Account security cutoff stream is malformed");
      }
      if (!parsed.toString().equals(accountId) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Account security cutoff stream is malformed");
      }
    }

    public Map<String, Object> toMap() {
      return Map.of(
          "accountAuthorityGeneration", accountAuthorityGeneration,
          "outboxStreamKey", outboxStreamKey,
          "outboxSequence", outboxSequence);
    }

    private static void requirePositiveDecimal(String value, String field) {
      if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
        throw new IllegalArgumentException("Account security cutoff " + field + " is malformed");
      }
      try {
        Long.parseLong(value);
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
            "Account security cutoff " + field + " exceeds the supported range");
      }
    }
  }
}
