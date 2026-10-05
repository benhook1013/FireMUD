package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

@SuppressWarnings("unchecked")
class RedisGameplayPresenceServiceTest {
  private static final Duration TTL = Duration.ofMillis(1000L);
  private static final String ACCOUNT_PLAYER = "6b56d98a-f6cd-4e39-9db9-111b9f8c6aa1";
  private static final String ACCOUNT_GOD = "4f41b943-39b1-4b0f-80f6-fc9229df0712";

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
    service = new RedisGameplayPresenceService(redisTemplate, TTL.toMillis());
  }

  @Test
  void registerConnectedStoresPresenceAndIndexesByGameInstance() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_PLAYER, "player@example.com", 202L, "Ben", 7L, "R-1", null);

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
    verify(setOperations).add("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions", "1");
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
    verify(redisTemplate)
        .expire("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions", TTL);
  }

  @Test
  void registerConnectedClassifiesInvalidJwtAsPlayer() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_PLAYER, "player@example.com", 202L, "Ben", 7L, "R-1", "not-a-jwt");

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
  void registerConnectedDoesNotElevateTenantRoleClaimsWithoutGrantEvidence() {
    String tenantAdminJwt =
        jwtUtil.generateToken(
            ACCOUNT_GOD,
            Map.of("accountId", ACCOUNT_GOD, "scopedRoles", Map.of("22", List.of("god"))));
    SessionContext godContext =
        new SessionContext(
            1L, 22L, ACCOUNT_GOD, "god@example.com", 101L, "Aster", 7L, "R-1", tenantAdminJwt);
    service.registerConnected(godContext);

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
  void listConnectedByGameInstanceDemotesCachedElevationAndPrunesMissingSessions() {
    when(setOperations.members("gameplaypresence:22:7:sessions"))
        .thenReturn(new LinkedHashSet<>(List.of("1", "2")));
    when(valueOperations.get("gameplaypresence:session:1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                1L,
                22L,
                7L,
                "ISOLATED",
                "demo",
                "production",
                17L,
                ACCOUNT_GOD,
                101L,
                "Aster",
                GameplayPresenceRole.GOD,
                70L,
                50L,
                60L,
                65L));
    when(valueOperations.get("gameplaypresence:session:2")).thenReturn(null);

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(1, result.size());
    assertEquals(GameplayPresenceRole.PLAYER, result.get(0).role());
    assertEquals("Aster", result.get(0).characterName());
    assertEquals("ISOLATED", result.get(0).playableStateScope());
    assertEquals(17L, result.get(0).pointerVersion());
    assertEquals(50L, result.get(0).explicitAfkSinceEpochMs());
    assertEquals(60L, result.get(0).lastAcceptedCommandAtEpochMs());
    assertEquals(65L, result.get(0).lastMeaningfulActivityAtEpochMs());
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "2");
    Mockito.verify(valueOperations, Mockito.never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
    Mockito.verify(redisTemplate, Mockito.never())
        .expire(Mockito.anyString(), Mockito.any(Duration.class));
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
                ACCOUNT_GOD,
                101L,
                "Aster",
                GameplayPresenceRole.PLAYER,
                70L,
                null,
                null,
                null));

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(1, result.size());
    assertEquals(1L, result.get(0).sessionId());
    verify(setOperations).remove("gameplaypresence:22:7:sessions", "not-a-session");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void listConnectedByGameInstancePreservesIndexForUnreadablePresence(
      boolean serializationFailure) {
    String indexKey = "gameplaypresence:22:7:sessions";
    when(setOperations.members(indexKey)).thenReturn(new LinkedHashSet<>(List.of("5")));
    when(valueOperations.get("gameplaypresence:session:5"))
        .thenThrow(unreadablePresenceFailure(serializationFailure));

    var result = service.listConnectedByGameInstance(22L, 7L);

    assertEquals(List.of(), result);
    verify(setOperations, Mockito.never()).remove(indexKey, "5");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void listConnectedByAccountIdsPreservesIndexForUnreadablePresence(boolean serializationFailure) {
    String indexKey = "gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions";
    when(setOperations.members(indexKey)).thenReturn(new LinkedHashSet<>(List.of("5")));
    when(valueOperations.get("gameplaypresence:session:5"))
        .thenThrow(unreadablePresenceFailure(serializationFailure));

    var result = service.listConnectedByAccountIds(22L, List.of(ACCOUNT_PLAYER));

    assertEquals(Map.of(), result);
    verify(setOperations, Mockito.never()).remove(indexKey, "5");
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
                ACCOUNT_PLAYER,
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
    verify(setOperations)
        .remove("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions", "3");
  }

  @Test
  void removeBySessionIdPreservesUnreadableRetainedPresence() {
    String key = "gameplaypresence:session:5";
    when(valueOperations.get(key)).thenThrow(new SerializationException("legacy record"));

    assertThrows(SerializationException.class, () -> service.removeBySessionId(5L));

    verifyNoPresenceMutations(key);
  }

  @Test
  void removeBySessionIdPreservesWrongTypeRetainedPresence() {
    String key = "gameplaypresence:session:5";
    when(valueOperations.get(key)).thenReturn(42L);

    assertThrows(ClassCastException.class, () -> service.removeBySessionId(5L));

    verifyNoPresenceMutations(key);
  }

  @Test
  void registerConnectedDoesNotOverwriteUnreadableRetainedPresence() {
    String key = "gameplaypresence:session:1";
    when(valueOperations.get(key)).thenThrow(new SerializationException("legacy record"));
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_PLAYER, "player@example.com", 202L, "Ben", 7L, "R-1", null);

    assertThrows(SerializationException.class, () -> service.registerConnected(context));

    verifyNoPresenceMutations(key);
  }

  @Test
  void registerConnectedDoesNotOverwriteWrongTypeRetainedPresence() {
    String key = "gameplaypresence:session:1";
    when(valueOperations.get(key)).thenReturn(42L);
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_PLAYER, "player@example.com", 202L, "Ben", 7L, "R-1", null);

    assertThrows(ClassCastException.class, () -> service.registerConnected(context));

    verifyNoPresenceMutations(key);
  }

  @Test
  void findConnectedBySessionIdDemotesCachedElevationWithoutWritingOrRefreshingTtl() {
    when(valueOperations.get("gameplaypresence:session:4"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                4L,
                22L,
                7L,
                "ISOLATED",
                "demo",
                "production",
                17L,
                ACCOUNT_PLAYER,
                202L,
                "Ben",
                GameplayPresenceRole.ADMIN,
                70L,
                50L,
                60L,
                65L));

    var presence = service.findConnectedBySessionId(4L);

    assertTrue(presence.isPresent());
    assertEquals(4L, presence.get().sessionId());
    assertEquals(GameplayPresenceRole.PLAYER, presence.get().role());
    assertEquals("ISOLATED", presence.get().playableStateScope());
    assertEquals(17L, presence.get().pointerVersion());
    assertEquals(70L, presence.get().connectedAtEpochMs());
    assertEquals(50L, presence.get().explicitAfkSinceEpochMs());
    assertEquals(60L, presence.get().lastAcceptedCommandAtEpochMs());
    assertEquals(65L, presence.get().lastMeaningfulActivityAtEpochMs());
    Mockito.verify(valueOperations, Mockito.never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
    Mockito.verify(redisTemplate, Mockito.never())
        .expire(Mockito.anyString(), Mockito.any(Duration.class));
  }

  @Test
  void unreadableLegacyPresenceFailsClosedWithoutDeletingRetainedValue() {
    String key = "gameplaypresence:session:5";
    when(valueOperations.get(key)).thenThrow(new SerializationException("legacy record"));

    var presence = service.findConnectedBySessionId(5L);

    assertFalse(presence.isPresent());
    Mockito.verify(redisTemplate, Mockito.never()).delete(key);
  }

  private void verifyNoPresenceMutations(String presenceKey) {
    verify(redisTemplate, Mockito.never()).delete(presenceKey);
    verify(redisTemplate, Mockito.never()).expire(Mockito.anyString(), Mockito.any(Duration.class));
    verify(valueOperations, Mockito.never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
    verify(setOperations, Mockito.never()).add(Mockito.anyString(), Mockito.any());
    verify(setOperations, Mockito.never()).remove(Mockito.anyString(), Mockito.any());
  }

  private static RuntimeException unreadablePresenceFailure(boolean serializationFailure) {
    return serializationFailure
        ? new SerializationException("legacy record")
        : new ClassCastException("legacy record");
  }

  @Test
  void recordCommandActivityRefreshesPresenceAndMeaningfulTimestampOnlyWhenRequested() {
    AtomicLong now = new AtomicLong(100L);
    service = new RedisGameplayPresenceService(redisTemplate, TTL.toMillis(), now::get);
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L,
                22L,
                7L,
                "demo",
                "production",
                ACCOUNT_PLAYER,
                202L,
                "Ben",
                GameplayPresenceRole.ADMIN,
                80L,
                95L,
                99L,
                90L));

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
                            == 90L
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                                .role()
                            == GameplayPresenceRole.PLAYER),
            org.mockito.Mockito.eq(TTL));
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
    verify(redisTemplate)
        .expire("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions", TTL);
  }

  @Test
  void setExplicitAfkRefreshesPresenceRecord() {
    AtomicLong now = new AtomicLong(100L);
    service = new RedisGameplayPresenceService(redisTemplate, TTL.toMillis(), now::get);
    when(valueOperations.get("gameplaypresence:session:3"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.GameplayPresence(
                3L,
                22L,
                7L,
                "demo",
                "production",
                ACCOUNT_PLAYER,
                202L,
                "Ben",
                GameplayPresenceRole.MODERATOR,
                80L,
                85L,
                90L,
                95L));

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
                                    .explicitAfkSinceEpochMs())
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                                .lastAcceptedCommandAtEpochMs()
                            == 90L
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                                .lastMeaningfulActivityAtEpochMs()
                            == 95L
                        && ((net.firedevops.firemud.gamesession.service.GameplayPresence) value)
                                .role()
                            == GameplayPresenceRole.PLAYER),
            org.mockito.Mockito.eq(TTL));
    verify(redisTemplate).expire("gameplaypresence:22:7:sessions", TTL);
    verify(redisTemplate)
        .expire("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions", TTL);
  }

  @Test
  void listConnectedByAccountIdsUsesAccountIndexAndReturnsAllMatches() {
    when(setOperations.members("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions"))
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
                ACCOUNT_PLAYER,
                202L,
                "Ben",
                GameplayPresenceRole.ADMIN,
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
                ACCOUNT_PLAYER,
                202L,
                "Ben",
                GameplayPresenceRole.GOD,
                90L,
                null,
                110L,
                120L));

    var result = service.listConnectedByAccountIds(22L, List.of(ACCOUNT_PLAYER));

    assertEquals(1, result.size());
    assertEquals(2, result.get(ACCOUNT_PLAYER).size());
    assertEquals(4L, result.get(ACCOUNT_PLAYER).get(0).sessionId());
    assertEquals(3L, result.get(ACCOUNT_PLAYER).get(1).sessionId());
    assertTrue(
        result.get(ACCOUNT_PLAYER).stream()
            .allMatch(presence -> presence.role() == GameplayPresenceRole.PLAYER));
    Mockito.verify(valueOperations, Mockito.never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
    Mockito.verify(redisTemplate, Mockito.never())
        .expire(Mockito.anyString(), Mockito.any(Duration.class));
  }

  @Test
  void listConnectedByAccountIdsPrunesMalformedSessionIndexEntry() {
    when(setOperations.members("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions"))
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
                ACCOUNT_PLAYER,
                202L,
                "Ben",
                GameplayPresenceRole.PLAYER,
                90L,
                null,
                110L,
                120L));

    var result = service.listConnectedByAccountIds(22L, List.of(ACCOUNT_PLAYER));

    assertEquals(1, result.size());
    assertEquals(1, result.get(ACCOUNT_PLAYER).size());
    assertEquals(4L, result.get(ACCOUNT_PLAYER).get(0).sessionId());
    verify(setOperations)
        .remove("gameplaypresence:22:account:" + ACCOUNT_PLAYER + ":sessions", "bad-session");
  }
}
