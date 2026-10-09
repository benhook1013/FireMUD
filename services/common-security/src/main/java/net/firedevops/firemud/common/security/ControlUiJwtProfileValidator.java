package net.firedevops.firemud.common.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the canonical claim shape of the registry-backed control-ui JWT profile. */
public final class ControlUiJwtProfileValidator {
  public static final String PROFILE = "control-ui";
  public static final String AUDIENCE = "control-ui";
  public static final String TOKEN_TYPE = "control-ui";
  private static final String ISSUER = "firemud-account-service";
  public static final Set<String> REQUIRED_CLAIMS;
  public static final Set<String> OPTIONAL_CLAIMS = Set.of("globalRoles");

  static {
    Set<String> required =
        new java.util.HashSet<>(AccountJwtProfileClaimSupport.REQUIRED_BASE_CLAIMS);
    required.add("scopedRoles");
    REQUIRED_CLAIMS = Set.copyOf(required);
  }

  private ControlUiJwtProfileValidator() {}

  /**
   * Checks claim shape only; it does not validate a registry record or current Account authority.
   *
   * @param claims immutable verified claims
   * @param maxControlUiTenantScopes explicit finite profile cap for every applicable tenant map
   */
  public static void validateClaims(Map<String, Object> claims, int maxControlUiTenantScopes) {
    if (maxControlUiTenantScopes <= 0
        || maxControlUiTenantScopes
            > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
    AccountJwtProfileClaimSupport.requireClaimFields(claims, REQUIRED_CLAIMS, OPTIONAL_CLAIMS);
    AccountJwtProfileClaimSupport.requireCommonIdentity(claims, ISSUER, AUDIENCE);

    Map<String, Object> tuple =
        AccountJwtProfileClaimSupport.requireAuthorityTuple(
            claims.get("authorityTuple"), true, false, false);
    if (!(tuple.get("privateRealmGrantVersions") instanceof List<?> grants) || !grants.isEmpty()) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
    Map<String, Long> tenantGenerations =
        AccountJwtProfileClaimSupport.requireGenerationMap(tuple.get("tenantAuthorityGeneration"));
    Map<String, Long> membershipGenerations =
        AccountJwtProfileClaimSupport.requireGenerationMap(
            tuple.get("membershipAuthorityGeneration"));
    Map<String, Long> membershipVersions =
        AccountJwtProfileClaimSupport.requireVersionMap(claims.get("membershipVersion"));
    String accountId = (String) claims.get("accountId");
    // The canonical profile forbids an accountId as a tenant-map key; this is shape rejection,
    // not evidence about tenant ownership or an authorization predicate.
    if (!membershipGenerations.keySet().equals(membershipVersions.keySet())
        || tenantGenerations.containsKey(accountId)
        || membershipGenerations.containsKey(accountId)
        || membershipVersions.containsKey(accountId)) {
      throw AccountJwtProfileClaimSupport.invalid();
    }

    Map<String, Object> scopedRoles =
        AccountJwtProfileClaimSupport.requireObjectMap(claims.get("scopedRoles"));
    scopedRoles.forEach(
        (tenantId, roles) -> {
          AccountJwtProfileClaimSupport.requireUuid(tenantId);
          AccountJwtProfileClaimSupport.requireRoleList(roles);
          if (((List<?>) roles).isEmpty() || tenantId.equals(claims.get("accountId"))) {
            throw AccountJwtProfileClaimSupport.invalid();
          }
        });
    if (!scopedRoles.keySet().containsAll(tenantGenerations.keySet())) {
      throw AccountJwtProfileClaimSupport.invalid();
    }

    requireAtMost(scopedRoles.size(), maxControlUiTenantScopes);
    requireAtMost(tenantGenerations.size(), maxControlUiTenantScopes);
    requireAtMost(membershipGenerations.size(), maxControlUiTenantScopes);
    requireAtMost(membershipVersions.size(), maxControlUiTenantScopes);

    Object billingCutoff = tuple.get("tenantBillingCutoff");
    if (billingCutoff != null) {
      Map<String, Object> billingCutoffs =
          AccountJwtProfileClaimSupport.requireObjectMap(billingCutoff);
      requireAtMost(billingCutoffs.size(), maxControlUiTenantScopes);
      if (!scopedRoles.keySet().containsAll(billingCutoffs.keySet())) {
        throw AccountJwtProfileClaimSupport.invalid();
      }
    }
    if (claims.containsKey("globalRoles")) {
      AccountJwtProfileClaimSupport.requireRoleList(claims.get("globalRoles"));
    }
  }

  private static void requireAtMost(int entries, int maximum) {
    if (entries > maximum) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
  }
}

