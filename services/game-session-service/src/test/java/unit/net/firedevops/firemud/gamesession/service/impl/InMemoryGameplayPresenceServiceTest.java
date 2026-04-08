package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class InMemoryGameplayPresenceServiceTest {
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);

  @Test
  void classifiesGodsFromJwtAndSortsGodsFirst() {
    InMemoryGameplayPresenceService service = new InMemoryGameplayPresenceService(jwtUtil);
    String godJwt =
        jwtUtil.generateToken(
            "1",
            Map.of(
                "accountId",
                "1",
                "globalRoles",
                List.of("platformAdmin"),
                "scopedRoles",
                Map.of()));
    String playerJwt =
        jwtUtil.generateToken(
            "2", Map.of("accountId", "2", "globalRoles", List.of(), "scopedRoles", Map.of()));

    service.registerConnected(
        new SessionContext(1L, 22L, 1L, "god@example.com", 101L, "Aster", 7L, "R-1", godJwt));
    service.registerConnected(
        new SessionContext(2L, 22L, 2L, "player@example.com", 102L, "Ben", 7L, "R-1", playerJwt));

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(2, result.size());
    assertEquals(GameplayPresenceRole.GOD, result.get(0).role());
    assertEquals("Aster", result.get(0).characterName());
    assertEquals(GameplayPresenceRole.PLAYER, result.get(1).role());
    assertEquals("Ben", result.get(1).characterName());
  }

  @Test
  void removeBySessionIdDropsPresence() {
    InMemoryGameplayPresenceService service = new InMemoryGameplayPresenceService(jwtUtil);
    service.registerConnected(
        new SessionContext(2L, 22L, 2L, "player@example.com", 102L, "Ben", 7L, "R-1", null));

    service.removeBySessionId(2L);

    assertEquals(List.of(), service.listConnectedByGameInstance(22L, 7L));
  }
}
