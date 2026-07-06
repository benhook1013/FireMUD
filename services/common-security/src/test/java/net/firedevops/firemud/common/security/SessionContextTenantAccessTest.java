package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SessionContextTenantAccessTest {

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void allowsGlobalAdminAcrossTenants() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of("7", List.of("tenantAdmin")));

    assertTrue(SessionContext.hasTenantAccess(42L));
    assertDoesNotThrow(() -> SessionContext.requireTenantAccess(42L));
  }

  @Test
  void allowsMatchingScopedTenantOnly() {
    SessionContext.setContext("1", List.of(), Map.of("7", List.of("moderator")));

    assertTrue(SessionContext.hasTenantAccess(7L));
    assertDoesNotThrow(() -> SessionContext.requireTenantAccess(7L));
    assertFalse(SessionContext.hasTenantAccess(8L));
    assertThrows(ResponseStatusException.class, () -> SessionContext.requireTenantAccess(8L));
  }

  @Test
  void allowsCurrentAccountWithoutTenantRole() {
    SessionContext.setContext("42", List.of(), Map.of());

    assertTrue(SessionContext.hasAccountAccess(7L, 42L));
    assertDoesNotThrow(() -> SessionContext.requireAccountAccess(7L, 42L));
    assertFalse(SessionContext.hasAccountAccess(7L, 43L));
    assertThrows(ResponseStatusException.class, () -> SessionContext.requireAccountAccess(7L, 43L));
  }

  @Test
  void hasAuthenticatedCallerContextReturnsFalseWhenClaimsAreAbsent() {
    SessionContext.clear();

    assertFalse(SessionContext.hasAuthenticatedCallerContext());
  }

  @Test
  void hasAuthenticatedCallerContextIgnoresBlankAccountWithoutRoles() {
    SessionContext.setContext("   ", List.of(), Map.of());

    assertFalse(SessionContext.hasAuthenticatedCallerContext());
  }

  @Test
  void hasAuthenticatedCallerContextTreatsMalformedAccountClaimAsPresent() {
    SessionContext.setContext("not-a-long", List.of(), Map.of());

    assertTrue(SessionContext.hasAuthenticatedCallerContext());
  }

  @Test
  void hasAuthenticatedCallerContextTreatsRoleOnlyClaimsAsPresent() {
    SessionContext.setContext(null, List.of(), Map.of("7", List.of("tenantAdmin")));

    assertTrue(SessionContext.hasAuthenticatedCallerContext());
  }
}
