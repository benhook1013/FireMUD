package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionClaimsTest {

  @Test
  void hasGameplayElevatedRoleRecognizesOnlyRequestedTenantScopedRoles() {
    SessionClaims globalGod =
        new SessionClaims("11", List.of("god"), Map.of("7", List.of("player")), false, null, null);
    SessionClaims scopedModerator =
        new SessionClaims("11", List.of(), Map.of("7", List.of("moderator")), false, null, null);

    assertFalse(globalGod.hasGameplayElevatedRole("7"));
    assertTrue(scopedModerator.hasGameplayElevatedRole("7"));
  }

  @Test
  void hasGameplayElevatedRoleIgnoresUnrelatedTenantScopes() {
    SessionClaims claims =
        new SessionClaims("11", List.of(), Map.of("8", List.of("tenantAdmin")), false, null, null);

    assertFalse(claims.hasGameplayElevatedRole("7"));
  }

  @Test
  void hasGameplayRoleChecksRequestedTenantScopeOnly() {
    SessionClaims claims =
        new SessionClaims(
            "11",
            List.of("platformAdmin"),
            Map.of("7", List.of("moderator"), "8", List.of("god")),
            false,
            null,
            null);

    assertFalse(claims.hasGameplayRole("7", "platformAdmin"));
    assertTrue(claims.hasGameplayRole("7", "moderator"));
    assertFalse(claims.hasGameplayRole("7", "god"));
  }

  @Test
  void controlPlanePrivilegedRoleCheckStillRecognizesGlobalRoles() {
    SessionClaims claims =
        new SessionClaims("11", List.of("platformAdmin"), Map.of(), false, null, null);

    assertTrue(claims.hasPrivilegedRole());
  }
}
