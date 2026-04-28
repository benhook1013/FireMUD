package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcClientAuthTest {
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void forwardsCurrentSessionClaimsWhenPresent() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of("7", List.of("tenantAdmin")));

    var claims =
        jwtUtil
            .parseToken(
                GrpcClientAuth.createBearerToken(
                    jwtUtil,
                    new RuntimeIdentity(
                        "game-session-service",
                        "gs-1",
                        "localhost",
                        Instant.now(),
                        "1.0.0",
                        "abc123",
                        "local")))
            .getPayload();

    assertEquals("42", claims.getSubject());
    assertEquals("42", claims.get("accountId", String.class));
    assertEquals(List.of("platformAdmin"), claims.get("globalRoles", List.class));
    assertFalse(Boolean.TRUE.equals(claims.get("internalService", Boolean.class)));
  }

  @Test
  void mintsExplicitInternalServiceIdentityWithoutAdminFallback() {
    var claims =
        jwtUtil
            .parseToken(
                GrpcClientAuth.createBearerToken(
                    jwtUtil,
                    new RuntimeIdentity(
                        "world-management-service",
                        "wm-1",
                        "localhost",
                        Instant.now(),
                        "1.0.0",
                        "def456",
                        "local")))
            .getPayload();

    assertEquals("service:world-management-service", claims.getSubject());
    assertNull(claims.get("accountId", String.class));
    assertTrue(Boolean.TRUE.equals(claims.get("internalService", Boolean.class)));
    assertEquals("world-management-service", claims.get("serviceName", String.class));
    assertEquals("wm-1", claims.get("serviceInstanceId", String.class));
    assertEquals(List.of(), claims.get("globalRoles", List.class));
  }

  @Test
  void explicitInternalBearerIgnoresCurrentSessionClaims() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of("7", List.of("tenantAdmin")));

    var claims =
        jwtUtil
            .parseToken(
                GrpcClientAuth.createInternalBearerToken(
                    jwtUtil,
                    new RuntimeIdentity(
                        "game-logic-service",
                        "gl-1",
                        "localhost",
                        Instant.now(),
                        "1.0.0",
                        "ghi789",
                        "local")))
            .getPayload();

    assertEquals("service:game-logic-service", claims.getSubject());
    assertNull(claims.get("accountId", String.class));
    assertTrue(Boolean.TRUE.equals(claims.get("internalService", Boolean.class)));
    assertEquals("game-logic-service", claims.get("serviceName", String.class));
    assertEquals("gl-1", claims.get("serviceInstanceId", String.class));
    assertEquals(List.of(), claims.get("globalRoles", List.class));
  }
}
