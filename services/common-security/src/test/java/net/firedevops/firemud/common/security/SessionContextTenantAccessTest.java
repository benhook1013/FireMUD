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
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of("7", List.of("admin")));

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
}
