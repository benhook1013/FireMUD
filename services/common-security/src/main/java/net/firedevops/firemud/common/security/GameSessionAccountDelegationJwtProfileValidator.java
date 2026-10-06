package net.firedevops.firemud.common.security;

import java.util.Map;
import java.util.Set;

/** Validates distinct initial and exact-one-tenant public-production delegation claim forms. */
public final class GameSessionAccountDelegationJwtProfileValidator {
  public static final Set<String> REQUIRED_CLAIMS =
      AccountJwtProfileClaimSupport.REQUIRED_BASE_CLAIMS;
  public static final Set<String> OPTIONAL_CLAIMS =
      Set.of("globalRoles", "scopedRoles", "tenantId");
  private static final Set<String> INITIAL_OPTIONAL_CLAIMS = Set.of("globalRoles", "scopedRoles");

  private GameSessionAccountDelegationJwtProfileValidator() {}

  /** Checks profile shape only; Account registry/currentness evidence is a separate predicate. */
  public static void validateClaims(Map<String, Object> claims) {
    if (claims == null) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
    if (claims.containsKey("tenantId")) {
      validatePublicTenantBoundClaims(claims);
      return;
    }
    validateInitialClaims(claims);
  }

  /** The initial form remains explicitly unscoped; a bound JWT cannot authenticate LOGIN. */
  public static void validateInitialClaims(Map<String, Object> claims) {
    AccountJwtProfileClaimSupport.requireClaimFields(
        claims, AccountJwtProfileClaimSupport.REQUIRED_BASE_CLAIMS, INITIAL_OPTIONAL_CLAIMS);
    AccountJwtProfileClaimSupport.requireDelegationCommonIdentity(
        claims,
        GameSessionAccountDelegationProfile.ISSUER,
        GameSessionAccountDelegationProfile.AUDIENCE);
    AccountJwtProfileClaimSupport.requireDelegationAuthorityTuple(
        claims.get("authorityTuple"), false, true, true);
    if (!AccountJwtProfileClaimSupport.requireDelegationVersionMap(claims.get("membershipVersion"))
        .isEmpty()) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
    if (claims.containsKey("globalRoles")) {
      AccountJwtProfileClaimSupport.requireRoleList(claims.get("globalRoles"));
    }
    if (claims.containsKey("scopedRoles")
        && !AccountJwtProfileClaimSupport.requireObjectMap(claims.get("scopedRoles")).isEmpty()) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
  }

  /** Separate exact-one-tenant public-production form; no roles or non-public grants. */
  public static void validatePublicTenantBoundClaims(Map<String, Object> claims) {
    var required = new java.util.HashSet<>(REQUIRED_CLAIMS);
    required.add("tenantId");
    AccountJwtProfileClaimSupport.requireClaimFields(claims, required, Set.of());
    AccountJwtProfileClaimSupport.requireDelegationCommonIdentity(
        claims,
        GameSessionAccountDelegationProfile.ISSUER,
        GameSessionAccountDelegationProfile.AUDIENCE);
    GameSessionAccountDelegationProfile.requirePublicTenantBoundAuthority(
        AccountJwtProfileClaimSupport.requireUuid(claims.get("tenantId")),
        AccountJwtProfileClaimSupport.requireObjectMap(claims.get("authorityTuple")),
        AccountJwtProfileClaimSupport.requireObjectMap(claims.get("membershipVersion")));
  }
}
