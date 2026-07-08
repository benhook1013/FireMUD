package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
        new SessionContext(1L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", null);

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
    verify(setOperations).add("gameplaypresence:22:account:102:sessions", "1");
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
    verify(redisTemplate).expire("gameplaypresence:22:account:102:sessions", TTL);
  }

  @Test
  void registerConnectedClassifiesInvalidJwtAsPlayer() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", "not-a-jwt");

    service.registerConnected(context);

    verify(valueOperations)
        .set(
            org.mockito.Mockito.eq("gameplaypresence:session:1"),
            argThat(
                value ->
                    value instanceof net.firedevops.firemud.gamesession.service.GameplayPresence
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                                .role()
                            == GameplayPresenceRole.PLAYER),
            org.mockito.Mockito.eq(TTL));
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
                1L,
                22L,
                7L,
                "demo",
                "production",
                1L,
                101L,
                "Aster",
                GameplayPresenceRole.GOD,
                70L,
                null,
                null,
                null));
    when(valueOperations.get("gameplaypresence:session:2")).thenReturn(null);

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(1, result.size());
    assertEquals(GameplayPresenceRole.GOD, result.get(0).role());
    assertEquals("Aster", result.get(0).characterName());
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "2");
  }

  @Test
  void listConnectedByGameInstancePrunesMalformedSessionIndexEntry() {
    when(setOperations.members("gameplaypresence:22:7:sessions"))
        .thenReturn(new LinkedHashSet<>(List.of("not-a-session", "1")));
    when(valueOperations.get("gameplaypresence:session:1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                1L,
                22L,
                7L,
                "demo",
                "production",
                1L,
                101L,
                "Aster",
                GameplayPresenceRole.GOD,
                70L,
                null,
                null,
                null));

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(1, result.size());
    assertEquals(1L, result.get(0).sessionId());
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "not-a-session");
  }

  @Test
  void removeBySessionIdRemovesValueAndSetMembership() {
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L,
                22L,
                7L,
                "demo",
                "production",
                102L,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                70L,
                null,
                null,
                null));

    service.removeBySessionId(3L);

    verify(redisTemplate).delete("gameplaypresence:session:3");
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "3");
    verify(setOperations).remove("gameplaypresence:22:account:102:sessions", "3");
  }

  @Test
  void findConnectedBySessionIdReadsPresenceRecordDirectly() {
    when(valueOperations.get("gameplaypresence:session:4"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                4L,
                22L,
                7L,
                "demo",
                "production",
                2L,
                102L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                70L,
                null,
                null,
                null));

    var presence = service.findConnectedBySessionId(4L);

    assertEquals(true, presence.isPresent());
    assertEquals(4L, presence.get().sessionId());
  }

  @Test
  void recordCommandActivityRefreshesPresenceAndMeaningfulTimestampOnlyWhenRequested() {
    AtomicLong now = new AtomicLong(100L);
    service = new RedisGameplayPresenceService(redisTemplate, jwtUtil, TTL.toMillis(), now::get);
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L,
                22L,
                7L,
                "demo",
                "production",
                102L,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                80L,
                null,
                null,
                null));

    now.set(125L);
    service.recordCommandActivity(3L, false);

    verify(valueOperations)
        .set(
            org.mockito.Mockito.eq("gameplaypresence:session:3"),
            argThat(
                value ->
                    value instanceof net.firedevops.firemud.gamesession.service.GameplayPresence
                        && Long.valueOf(125L)
                            .equals(
                                ((net.firedevops.firemud.gamesession.service.GameplayPresence)
                                        value)
                                    .lastAcceptedCommandAtEpochMs())
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                                .lastMeaningfulActivityAtEpochMs()
                            == null),
            org.mockito.Mockito.eq(TTL));
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
    verify(redisTemplate).expire("gameplaypresence:22:account:102:sessions", TTL);
  }

  @Test
  void setExplicitAfkRefreshesPresenceRecord() {
    AtomicLong now = new AtomicLong(100L);
    service = new RedisGameplayPresenceService(redisTemplate, jwtUtil, TTL.toMillis(), now::get);
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L,
                22L,
                7L,
                "demo",
                "production",
                102L,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                80L,
                null,
                null,
                null));

    now.set(145L);
    service.setExplicitAfk(3L, true);

    verify(valueOperations)
        .set(
            org.mockito.Mockito.eq("gameplaypresence:session:3"),
            argThat(
                value ->
                    value instanceof net.firedevops.firemud.gamesession.service.GameplayPresence
                        && Long.valueOf(145L)
                            .equals(
                                ((net.firedevops.firemud.gamesession.service.GameplayPresence)
                                        value)
                                    .explicitAfkSinceEpochMs())),
            org.mockito.Mockito.eq(TTL));
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
    verify(redisTemplate).expire("gameplaypresence:22:account:102:sessions", TTL);
  }

  @Test
  void listConnectedByAccountIdsUsesAccountIndexAndReturnsAllMatches() {
    when(setOperations.members("gameplaypresence:22:account:102:sessions"))
        .thenReturn(new LinkedHashSet<>(List.of("3", "4")));
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L,
                22L,
                7L,
                "SHARED",
                "demo",
                "production",
                17L,
                102L,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                80L,
                null,
                100L,
                null));
    when(valueOperations.get("gameplaypresence:session:4"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                4L,
                22L,
                7L,
                "SHARED",
                "demo",
                "production",
                17L,
                102L,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                90L,
                null,
                110L,
                120L));

    var result = service.listConnectedByAccountIds(22L, List.of(102L));

    assertEquals(1, result.size());
    assertEquals(2, result.get(102L).size());
    assertEquals(4L, result.get(102L).get(0).sessionId());
    assertEquals(3L, result.get(102L).get(1).sessionId());
  }

  @Test
  void listConnectedByAccountIdsPrunesMalformedSessionIndexEntry() {
    when(setOperations.members("gameplaypresence:22:account:102:sessions"))
        .thenReturn(new LinkedHashSet<>(List.of("bad-session", "4")));
    when(valueOperations.get("gameplaypresence:session:4"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                4L,
                22L,
                7L,
                "SHARED",
                "demo",
                "production",
                17L,
                102L,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                90L,
                null,
                110L,
                120L));

    var result = service.listConnectedByAccountIds(22L, List.of(102L));

    assertEquals(1, result.size());
    assertEquals(1, result.get(102L).size());
    assertEquals(4L, result.get(102L).get(0).sessionId());
    verify(setOperations).remove("gameplaypresence:22:account:102:sessions", "bad-session");
  }
}
