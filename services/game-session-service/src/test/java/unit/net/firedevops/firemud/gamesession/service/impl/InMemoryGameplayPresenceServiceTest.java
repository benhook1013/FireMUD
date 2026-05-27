package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
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

    assertEquals(true, service.findConnectedBySessionId(2L).isPresent());
    service.removeBySessionId(2L);

    assertEquals(false, service.findConnectedBySessionId(2L).isPresent());
    assertEquals(List.of(), service.listConnectedByGameInstance(22L, 7L));
  }

  @Test
  void recordCommandActivityUpdatesLastAcceptedAndMeaningfulTimestampsSeparately() {
    AtomicLong now = new AtomicLong(100L);
    InMemoryGameplayPresenceService service =
        new InMemoryGameplayPresenceService(jwtUtil, now::get);
    service.registerConnected(
        new SessionContext(2L, 22L, 2L, "player@example.com", 102L, "Ben", 7L, "R-1", null));

    GameplayPresence initial = service.listConnectedByGameInstance(22L, 7L).get(0);
    assertEquals(100L, initial.connectedAtEpochMs());
    assertNull(initial.lastAcceptedCommandAtEpochMs());
    assertNull(initial.lastMeaningfulActivityAtEpochMs());

    now.set(125L);
    service.recordCommandActivity(2L, false);
    GameplayPresence afterMeta = service.listConnectedByGameInstance(22L, 7L).get(0);
    assertEquals(Long.valueOf(125L), afterMeta.lastAcceptedCommandAtEpochMs());
    assertNull(afterMeta.lastMeaningfulActivityAtEpochMs());

    now.set(150L);
    service.recordCommandActivity(2L, true);
    GameplayPresence afterGameplay = service.listConnectedByGameInstance(22L, 7L).get(0);
    assertEquals(Long.valueOf(150L), afterGameplay.lastAcceptedCommandAtEpochMs());
    assertEquals(Long.valueOf(150L), afterGameplay.lastMeaningfulActivityAtEpochMs());
  }

  @Test
  void setExplicitAfkTracksAndClearsExplicitAfkTimestamp() {
    AtomicLong now = new AtomicLong(100L);
    InMemoryGameplayPresenceService service =
        new InMemoryGameplayPresenceService(jwtUtil, now::get);
    service.registerConnected(
        new SessionContext(2L, 22L, 2L, "player@example.com", 102L, "Ben", 7L, "R-1", null));

    now.set(120L);
    service.setExplicitAfk(2L, true);
    GameplayPresence afk = service.listConnectedByGameInstance(22L, 7L).get(0);
    assertEquals(Long.valueOf(120L), afk.explicitAfkSinceEpochMs());

    now.set(130L);
    service.setExplicitAfk(2L, false);
    GameplayPresence cleared = service.listConnectedByGameInstance(22L, 7L).get(0);
    assertNull(cleared.explicitAfkSinceEpochMs());
  }

  @Test
  void listConnectedByAccountIdsReturnsAllSessionsForAccount() {
    AtomicLong now = new AtomicLong(100L);
    InMemoryGameplayPresenceService service =
        new InMemoryGameplayPresenceService(jwtUtil, now::get);
    service.registerConnected(
        new SessionContext(2L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", null));
    now.set(110L);
    service.registerConnected(
        new SessionContext(3L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", null));
    now.set(140L);
    service.recordCommandActivity(3L, true);

    var result = service.listConnectedByAccountIds(22L, new LinkedHashSet<>(List.of(102L)));

    assertEquals(1, result.size());
    assertEquals(2, result.get(102L).size());
    assertEquals(
        List.of(2L, 3L),
        result.get(102L).stream().map(GameplayPresence::sessionId).sorted().toList());
  }
}
