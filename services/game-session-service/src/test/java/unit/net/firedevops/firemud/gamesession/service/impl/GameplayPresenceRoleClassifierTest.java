package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class GameplayPresenceRoleClassifierTest {
  private static final JwtUtil JWT_UTIL = new JwtUtil("mysecretkey123456789012345678901", 30_000L);
  private static final String ACCOUNT_UUID = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";

  @Test
  void classifyRoleReturnsPlayerWhenJwtParserThrowsIllegalArgumentException() {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    Logger logger = mock(Logger.class);
    when(jwtUtil.parseToken("boom-token")).thenThrow(new IllegalArgumentException("bad jwt"));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, "102", "player@example.com", 202L, "Ben", 7L, "R-1", "boom-token"),
            jwtUtil,
            logger);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleReturnsModeratorForScopedModeratorRole() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID,
            java.util.Map.of(
                "accountId",
                ACCOUNT_UUID,
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.MODERATOR, role);
  }

  @Test
  void classifyRoleIgnoresGlobalGodRole() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID,
            java.util.Map.of("accountId", ACCOUNT_UUID, "globalRoles", java.util.List.of("god")));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleReturnsAdminForTenantAdminRole() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID,
            java.util.Map.of(
                "accountId",
                ACCOUNT_UUID,
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("tenantAdmin"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.ADMIN, role);
  }

  @Test
  void classifyRoleDoesNotLetGlobalRolesOverrideTheTenantGrant() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID,
            java.util.Map.of(
                "accountId",
                ACCOUNT_UUID,
                "globalRoles",
                java.util.List.of("moderator", "god"),
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("tenantAdmin"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.ADMIN, role);
  }

  @Test
  void classifyRoleIgnoresGlobalOperationsAndOtherTenants() {
    for (String globalRole :
        java.util.List.of("platformAdmin", "support", "billingAdmin", "moderator", "tenantAdmin")) {
      String jwt =
          JWT_UTIL.generateToken(
              ACCOUNT_UUID,
              java.util.Map.of(
                  "accountId",
                  ACCOUNT_UUID,
                  "globalRoles",
                  java.util.List.of(globalRole),
                  "scopedRoles",
                  java.util.Map.of("23", java.util.List.of("god"))));
      assertEquals(
          GameplayPresenceRole.PLAYER,
          GameplayPresenceRoleClassifier.classifyRole(
              new SessionContext(
                  1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
              JWT_UTIL,
              mock(Logger.class)));
    }
  }

  @Test
  void classifyRoleUsesTheHighestExplicitCurrentTenantGrant() {
    String jwt =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID,
            java.util.Map.of(
                "accountId",
                ACCOUNT_UUID,
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator", "tenantAdmin", "GOD"))));
    assertEquals(
        GameplayPresenceRole.GOD,
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            mock(Logger.class)));
  }

  @Test
  void classifyRoleReturnsPlayerWhenScopedRolesClaimIsMalformed() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID, java.util.Map.of("accountId", ACCOUNT_UUID, "scopedRoles", "bad"));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, ACCOUNT_UUID, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }
}
