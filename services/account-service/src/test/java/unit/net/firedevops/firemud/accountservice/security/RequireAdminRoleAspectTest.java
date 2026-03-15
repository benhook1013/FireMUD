package net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.RequireAdminRoleAspect;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RequireAdminRoleAspectTest {
  private final RequireAdminRoleAspect aspect = new RequireAdminRoleAspect();

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void failsWithoutAdminRole() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    assertThrows(StatusRuntimeException.class, aspect::checkRole);
  }

  @Test
  void succeedsWithAdminRole() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    assertDoesNotThrow(() -> aspect.checkRole());
  }
}
