package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SessionContextTenantAccessTest {
  private static final String ACCOUNT_ID = "1111111a-1111-4111-8111-111111111111";
  private static final String OTHER_ACCOUNT_ID = "22222222-2222-4222-8222-222222222222";

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void allowsGlobalAdminAcrossTenants() {
    SessionContext.setContext(
        ACCOUNT_ID, List.of("platformAdmin"), Map.of("7", List.of("tenantAdmin")));

    assertTrue(SessionContext.hasTenantAccess(42L));
    assertDoesNotThrow(() -> SessionContext.requireTenantAccess(42L));
  }

  @Test
  void allowsMatchingScopedTenantOnly() {
    SessionContext.setContext(ACCOUNT_ID, List.of(), Map.of("7", List.of("moderator")));

    assertTrue(SessionContext.hasTenantAccess(7L));
    assertDoesNotThrow(() -> SessionContext.requireTenantAccess(7L));
    assertFalse(SessionContext.hasTenantAccess(8L));
    assertThrows(ResponseStatusException.class, () -> SessionContext.requireTenantAccess(8L));
  }

  @Test
  void allowsCurrentAccountWithoutTenantRole() {
    SessionContext.setContext(ACCOUNT_ID, List.of(), Map.of());

    assertTrue(SessionContext.hasAccountAccess(7L, ACCOUNT_ID));
    assertTrue(SessionContext.isCurrentAccount(ACCOUNT_ID));
    assertDoesNotThrow(() -> SessionContext.requireAccountAccess(7L, ACCOUNT_ID));
    assertFalse(SessionContext.hasAccountAccess(7L, OTHER_ACCOUNT_ID));
    assertFalse(SessionContext.isCurrentAccount(OTHER_ACCOUNT_ID));
    assertThrows(
        ResponseStatusException.class,
        () -> SessionContext.requireAccountAccess(7L, OTHER_ACCOUNT_ID));
  }

  @Test
  void currentAccountAccessRejectsNumericAndNilAccountIds() {
    SessionContext.setContext(ACCOUNT_ID, List.of(), Map.of());

    String nilAccountId = "00000000-0000-0000-0000-000000000000";
    assertFalse(SessionContext.isCurrentAccount("42"));
    assertFalse(SessionContext.hasAccountAccess(7L, "42"));
    assertFalse(SessionContext.isCurrentAccount(nilAccountId));
    assertThrows(
        ResponseStatusException.class, () -> SessionContext.requireAccountAccess(7L, "42"));
  }

  @Test
  void currentAccountAccessRejectsNumericCurrentAccountClaim() {
    SessionContext.setContext("42", List.of(), Map.of());

    assertFalse(SessionContext.isCurrentAccount(ACCOUNT_ID));
    assertFalse(SessionContext.hasAccountAccess(7L, ACCOUNT_ID));
    assertThrows(
        ResponseStatusException.class, () -> SessionContext.requireAccountAccess(7L, ACCOUNT_ID));
  }

  @Test
  void currentAccountIdOrNullReturnsExactCanonicalUuid() {
    SessionContext.setContext(ACCOUNT_ID, List.of(), Map.of());

    assertEquals(ACCOUNT_ID, SessionContext.currentAccountIdOrNull());
  }

  @Test
  void currentAccountIdOrNullReturnsNullWhenClaimMissing() {
    SessionContext.clear();
    assertNull(SessionContext.currentAccountIdOrNull());

    SessionContext.setContext("   ", List.of(), Map.of());
    assertNull(SessionContext.currentAccountIdOrNull());
  }

  @Test
  void currentAccountIdOrNullRejectsMalformedNumericNilAndNoncanonicalClaims() {
    SessionContext.setContext("not-a-uuid", List.of(), Map.of());
    assertThrows(IllegalArgumentException.class, SessionContext::currentAccountIdOrNull);

    SessionContext.setContext("42", List.of(), Map.of());
    assertThrows(IllegalArgumentException.class, SessionContext::currentAccountIdOrNull);

    SessionContext.setContext("00000000-0000-0000-0000-000000000000", List.of(), Map.of());
    assertThrows(IllegalArgumentException.class, SessionContext::currentAccountIdOrNull);

    SessionContext.setContext(ACCOUNT_ID.toUpperCase(java.util.Locale.ROOT), List.of(), Map.of());
    assertThrows(IllegalArgumentException.class, SessionContext::currentAccountIdOrNull);
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
    SessionContext.setContext("not-a-uuid", List.of(), Map.of());

    assertTrue(SessionContext.hasAuthenticatedCallerContext());
  }

  @Test
  void hasAuthenticatedCallerContextTreatsRoleOnlyClaimsAsPresent() {
    SessionContext.setContext(null, List.of(), Map.of("7", List.of("tenantAdmin")));

    assertTrue(SessionContext.hasAuthenticatedCallerContext());
  }
}
