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

  @Test
  void classifyRoleReturnsPlayerWhenJwtParserThrowsIllegalArgumentException() {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    Logger logger = mock(Logger.class);
    when(jwtUtil.parseToken("boom-token")).thenThrow(new IllegalArgumentException("bad jwt"));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", "boom-token"),
            jwtUtil,
            logger);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleReturnsGodForScopedModeratorRole() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.GOD, role);
  }

  @Test
  void classifyRoleReturnsGodForGlobalGodRole() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken(
            "202", java.util.Map.of("accountId", "202", "globalRoles", java.util.List.of("god")));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.GOD, role);
  }

  @Test
  void classifyRoleReturnsPlayerWhenScopedRolesClaimIsMalformed() {
    Logger logger = mock(Logger.class);
    String jwt =
        JWT_UTIL.generateToken("202", java.util.Map.of("accountId", "202", "scopedRoles", "bad"));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            logger);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }
}
