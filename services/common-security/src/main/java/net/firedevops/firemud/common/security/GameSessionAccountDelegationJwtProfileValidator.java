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
    requireExactAuthorityCutoffStreams(claims, null);
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
    String accountId = AccountJwtProfileClaimSupport.requireUuid(claims.get("accountId"));
    String tenantId = AccountJwtProfileClaimSupport.requireUuid(claims.get("tenantId"));
    GameSessionAccountDelegationProfile.requirePublicTenantBoundAuthority(
        tenantId,
        AccountJwtProfileClaimSupport.requireObjectMap(claims.get("authorityTuple")),
        AccountJwtProfileClaimSupport.requireObjectMap(claims.get("membershipVersion")));
    requireExactAuthorityCutoffStreams(claims, accountId, tenantId);
  }

  private static void requireExactAuthorityCutoffStreams(
      Map<String, Object> claims, String tenantId) {
    requireExactAuthorityCutoffStreams(
        claims, AccountJwtProfileClaimSupport.requireUuid(claims.get("accountId")), tenantId);
  }

  private static void requireExactAuthorityCutoffStreams(
      Map<String, Object> claims, String accountId, String tenantId) {
    Map<String, Object> tuple =
        AccountJwtProfileClaimSupport.requireObjectMap(claims.get("authorityTuple"));
    if (tuple.containsKey("accountSecurityCutoff")) {
      Map<String, Object> cutoff =
          AccountJwtProfileClaimSupport.requireObjectMap(tuple.get("accountSecurityCutoff"));
      AccountJwtProfileClaimSupport.requireExactAccountAuthorityStream(
          cutoff.get("outboxStreamKey"), accountId);
    }
    if (tenantId != null && tuple.containsKey("tenantBillingCutoff")) {
      Map<String, Object> cutoffs =
          AccountJwtProfileClaimSupport.requireObjectMap(tuple.get("tenantBillingCutoff"));
      if (!cutoffs.keySet().equals(Set.of(tenantId))) {
        throw AccountJwtProfileClaimSupport.invalid();
      }
      Map<String, Object> cutoff =
          AccountJwtProfileClaimSupport.requireObjectMap(cutoffs.get(tenantId));
      AccountJwtProfileClaimSupport.requireExactTenantAuthorityStream(
          cutoff.get("outboxStreamKey"), tenantId);
    }
  }
}
