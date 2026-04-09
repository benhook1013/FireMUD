package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdminRoleGuardTest {

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void deniesWithoutAdminRole() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    assertThrows(AdminAuthorizationException.class, AdminRoleGuard::requireAdminRole);
  }

  @Test
  void allowsWithAdminRole() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    assertDoesNotThrow(AdminRoleGuard::requireAdminRole);
  }
}
