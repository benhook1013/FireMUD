package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionClaimsTest {

  @Test
  void privilegedRoleCheckStillRecognizesGlobalControlPlaneRoles() {
    SessionClaims claims =
        new SessionClaims("11", List.of("platformAdmin"), Map.of(), false, null, null);

    assertTrue(claims.hasPrivilegedRole());
  }

  @Test
  void privilegedRoleCheckStillRecognizesTenantScopedControlPlaneRoles() {
    SessionClaims claims =
        new SessionClaims(
            "11", List.of(), Map.of("7", List.of("tenantAdmin", "moderator")), false, null, null);

    assertTrue(claims.hasPrivilegedRole());
  }
}
