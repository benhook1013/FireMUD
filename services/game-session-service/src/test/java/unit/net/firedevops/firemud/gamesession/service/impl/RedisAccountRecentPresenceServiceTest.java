package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisAccountRecentPresenceServiceTest {

  @Test
  void recordDisconnectPersistsRoutingBundleFromLivePresence() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver =
        Mockito.mock(AccountPresenceVisibilityPolicyResolver.class);
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());

    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            99L,
            "room-1",
            "jwt",
            "en-NZ",
            99L,
            "fallback-world",
            "fallback-realm",
            3L,
            "ISOLATED:7001");
    GameplayPresence presence =
        new GameplayPresence(
            41L,
            22L,
            101L,
            "ISOLATED",
            "demo",
            "production",
            17L,
            123L,
            7001L,
            "Emberline",
            GameplayPresenceRole.PLAYER,
            1000L,
            null,
            1200L,
            1300L);

    when(sessionRoutingNormalizationService.resolveProjectedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.of(presence));
    when(visibilityPolicyResolver.resolve(22L, 123L, GameplayPresenceRole.PLAYER))
        .thenReturn(AccountPresenceVisibilityPolicy.FRIENDS_ONLY);

    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            sessionRoutingNormalizationService,
            gameplayPresenceService,
            visibilityPolicyResolver,
            presenceProperties,
            () -> 1_700_000_000_000L);

    service.recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
    verify(valueOperations)
        .set(eq("accountrecentpresence:22:123"), stateCaptor.capture(), eq(Duration.ofMinutes(5)));

    AccountRecentPresenceState state =
        assertInstanceOf(AccountRecentPresenceState.class, stateCaptor.getValue());
    assertEquals(22L, state.tenantId());
    assertEquals(123L, state.accountId());
    assertEquals(101L, state.gameInstanceId());
    assertEquals("demo", state.worldSlug());
    assertEquals("production", state.realmSlug());
    assertEquals(17L, state.pointerVersion());
    assertEquals(1_700_000_000_000L, state.lastSeenAtEpochMs());
    assertEquals(AccountRecentPresenceDisposition.TRANSPORT_LOSS, state.disposition());
    assertEquals(AccountPresenceVisibilityPolicy.FRIENDS_ONLY, state.visibilityPolicy());
  }

  @Test
  void recordDisconnectIgnoresLivePresenceWhenNormalizationClearsGameplayBinding() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver =
        Mockito.mock(AccountPresenceVisibilityPolicyResolver.class);
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());

    SessionContext cleared =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            0L,
            null,
            0L,
            null,
            "jwt",
            "en-NZ",
            99L,
            null,
            null,
            0L,
            null);
    GameplayPresence stalePresence =
        new GameplayPresence(
            41L,
            22L,
            101L,
            "ISOLATED",
            "demo",
            "production",
            17L,
            123L,
            7001L,
            "Emberline",
            GameplayPresenceRole.PLAYER,
            1000L,
            null,
            1200L,
            1300L);

    when(sessionRoutingNormalizationService.resolveProjectedSessionContext("41"))
        .thenReturn(Optional.of(cleared));
    when(gameplayPresenceService.findConnectedBySessionId(41L))
        .thenReturn(Optional.of(stalePresence));
    when(visibilityPolicyResolver.resolve(22L, 123L, GameplayPresenceRole.PLAYER))
        .thenReturn(AccountPresenceVisibilityPolicy.FRIENDS_ONLY);

    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            sessionRoutingNormalizationService,
            gameplayPresenceService,
            visibilityPolicyResolver,
            presenceProperties,
            () -> 1_700_000_000_000L);

    service.recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
    verify(valueOperations)
        .set(eq("accountrecentpresence:22:123"), stateCaptor.capture(), eq(Duration.ofMinutes(5)));

    AccountRecentPresenceState state =
        assertInstanceOf(AccountRecentPresenceState.class, stateCaptor.getValue());
    assertNull(state.gameInstanceId());
    assertNull(state.worldSlug());
    assertNull(state.realmSlug());
    assertNull(state.pointerVersion());
  }
}
