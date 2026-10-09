package net.firedevops.firemud.common.security;

import java.util.Map;
import java.util.Set;

/** Validates the canonical, tenant-free player-bootstrap JWT claim shape. */
public final class PlayerBootstrapJwtProfileValidator {
  public static final String PROFILE = "player-bootstrap";
  public static final String AUDIENCE = "player-bootstrap";
  public static final String TOKEN_TYPE = "player-bootstrap";
  private static final String ISSUER = "firemud-account-service";
  public static final Set<String> REQUIRED_CLAIMS;
  public static final Set<String> OPTIONAL_CLAIMS = Set.of("globalRoles");

  static {
    Set<String> required =
        new java.util.HashSet<>(AccountJwtProfileClaimSupport.REQUIRED_BASE_CLAIMS);
    required.add("scopedRoles");
    REQUIRED_CLAIMS = Set.copyOf(required);
  }

  private PlayerBootstrapJwtProfileValidator() {}

  /** Checks profile shape only; it does not validate current membership or authorize a route. */
  public static void validateClaims(Map<String, Object> claims) {
    AccountJwtProfileClaimSupport.requireClaimFields(claims, REQUIRED_CLAIMS, OPTIONAL_CLAIMS);
    AccountJwtProfileClaimSupport.requireCommonIdentity(claims, ISSUER, AUDIENCE);
    AccountJwtProfileClaimSupport.requireAuthorityTuple(
        claims.get("authorityTuple"), false, true, false);
    if (!AccountJwtProfileClaimSupport.requireVersionMap(claims.get("membershipVersion")).isEmpty()
        || !AccountJwtProfileClaimSupport.requireObjectMap(claims.get("scopedRoles")).isEmpty()) {
      throw AccountJwtProfileClaimSupport.invalid();
    }
    if (claims.containsKey("globalRoles")) {
      AccountJwtProfileClaimSupport.requireRoleList(claims.get("globalRoles"));
    }
  }
}

