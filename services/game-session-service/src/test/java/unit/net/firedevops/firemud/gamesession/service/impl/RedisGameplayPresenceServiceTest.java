package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class RedisGameplayPresenceServiceTest {
  private static final Duration TTL = Duration.ofMillis(1000L);

  private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
  private final ValueOperations<String, Object> valueOperations =
      Mockito.mock(ValueOperations.class);
  private final SetOperations<String, Object> setOperations = Mockito.mock(SetOperations.class);
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);
  private RedisGameplayPresenceService service;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(redisTemplate.opsForSet()).thenReturn(setOperations);
    service = new RedisGameplayPresenceService(redisTemplate, jwtUtil, TTL.toMillis());
  }

  @Test
  void registerConnectedStoresPresenceAndIndexesByGameInstance() {
    SessionContext context =
        new SessionContext(1L, 22L, 2L, "player@example.com", 102L, "Ben", 7L, "R-1", null);

    service.registerConnected(context);

    verify(valueOperations)
        .set(
            org.mockito.Mockito.eq("gameplaypresence:session:1"),
            argThat(
                value ->
                    value instanceof net.firedevops.firemud.gamesession.service.GameplayPresence
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                            .characterName()
                            .equals("Ben")),
            org.mockito.Mockito.eq(TTL));
    verify(setOperations).add("gameplaypresence:22:7:sessions", "1");
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
  }

  @Test
  void listConnectedByGameInstanceSortsGodsFirstAndPrunesMissingSessions() {
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
    SessionContext godContext =
        new SessionContext(1L, 22L, 1L, "god@example.com", 101L, "Aster", 7L, "R-1", godJwt);
    service.registerConnected(godContext);

    when(setOperations.members("gameplaypresence:22:7:sessions"))
        .thenReturn(new LinkedHashSet<>(List.of("1", "2")));
    when(valueOperations.get("gameplaypresence:session:1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                1L, 22L, 7L, 1L, 101L, "Aster", GameplayPresenceRole.GOD));
    when(valueOperations.get("gameplaypresence:session:2")).thenReturn(null);

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(1, result.size());
    assertEquals(GameplayPresenceRole.GOD, result.get(0).role());
    assertEquals("Aster", result.get(0).characterName());
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "2");
  }

  @Test
  void removeBySessionIdRemovesValueAndSetMembership() {
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L, 22L, 7L, 2L, 102L, "Ben", GameplayPresenceRole.PLAYER));

    service.removeBySessionId(3L);

    verify(redisTemplate).delete("gameplaypresence:session:3");
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "3");
  }
}
