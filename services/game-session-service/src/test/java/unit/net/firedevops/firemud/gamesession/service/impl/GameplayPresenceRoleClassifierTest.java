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
}
