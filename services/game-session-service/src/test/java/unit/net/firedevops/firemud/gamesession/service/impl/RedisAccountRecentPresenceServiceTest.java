package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
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
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

class RedisAccountRecentPresenceServiceTest {
  private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000123";

  @Test
  void recordDisconnectPersistsRoutingBundleFromLivePresence() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());

    SessionContext context =
        new SessionContext(
            41L,
            22L,
            ACCOUNT_ID,
            "demo@example.com",
            7001L,
            "Emberline",
            99L,
            "R-1",
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
            ACCOUNT_ID,
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

    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            sessionRoutingNormalizationService,
            gameplayPresenceService,
            presenceProperties,
            () -> 1_700_000_000_000L);

    service.recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
    verify(valueOperations)
        .set(
            eq("accountrecentpresence:22:" + ACCOUNT_ID),
            stateCaptor.capture(),
            eq(Duration.ofMinutes(5)));
    verify(redisTemplate).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate).multi();
    verify(redisTemplate).exec();

    AccountRecentPresenceState state =
        assertInstanceOf(AccountRecentPresenceState.class, stateCaptor.getValue());
    assertEquals(22L, state.tenantId());
    assertEquals(ACCOUNT_ID, state.accountId());
    assertEquals(101L, state.gameInstanceId());
    assertEquals("demo", state.worldSlug());
    assertEquals("production", state.realmSlug());
    assertEquals(17L, state.pointerVersion());
    assertEquals(1_700_000_000_000L, state.lastSeenAtEpochMs());
    assertEquals(AccountRecentPresenceDisposition.TRANSPORT_LOSS, state.disposition());
  }

  @Test
  void recordDisconnectIgnoresLivePresenceWhenNormalizationClearsGameplayBinding() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());

    SessionContext cleared =
        new SessionContext(
            41L,
            22L,
            ACCOUNT_ID,
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
            ACCOUNT_ID,
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

    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            sessionRoutingNormalizationService,
            gameplayPresenceService,
            presenceProperties,
            () -> 1_700_000_000_000L);

    service.recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
    verify(valueOperations)
        .set(
            eq("accountrecentpresence:22:" + ACCOUNT_ID),
            stateCaptor.capture(),
            eq(Duration.ofMinutes(5)));

    AccountRecentPresenceState state =
        assertInstanceOf(AccountRecentPresenceState.class, stateCaptor.getValue());
    assertNull(state.gameInstanceId());
    assertNull(state.worldSlug());
    assertNull(state.realmSlug());
    assertNull(state.pointerVersion());
  }

  @Test
  void recordDisconnectIgnoresLivePresenceWhenContextOnlyRetainsPartialGameplayShell() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());

    SessionContext partial =
        new SessionContext(
            41L,
            22L,
            ACCOUNT_ID,
            "demo@example.com",
            0L,
            null,
            99L,
            null,
            "jwt",
            "en-NZ",
            99L,
            "fallback-world",
            "fallback-realm",
            3L,
            "SHARED");
    GameplayPresence livePresence =
        new GameplayPresence(
            41L,
            22L,
            101L,
            "ISOLATED",
            "demo",
            "production",
            17L,
            ACCOUNT_ID,
            7001L,
            "Emberline",
            GameplayPresenceRole.PLAYER,
            1000L,
            null,
            1200L,
            1300L);

    when(sessionRoutingNormalizationService.resolveProjectedSessionContext("41"))
        .thenReturn(Optional.of(partial));
    when(gameplayPresenceService.findConnectedBySessionId(41L))
        .thenReturn(Optional.of(livePresence));

    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            sessionRoutingNormalizationService,
            gameplayPresenceService,
            presenceProperties,
            () -> 1_700_000_000_000L);

    service.recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
    verify(valueOperations)
        .set(
            eq("accountrecentpresence:22:" + ACCOUNT_ID),
            stateCaptor.capture(),
            eq(Duration.ofMinutes(5)));

    AccountRecentPresenceState state =
        assertInstanceOf(AccountRecentPresenceState.class, stateCaptor.getValue());
    assertEquals(99L, state.gameInstanceId());
    assertEquals("fallback-world", state.worldSlug());
    assertEquals("fallback-realm", state.realmSlug());
    assertEquals(3L, state.pointerVersion());
  }

  @Test
  void recordDisconnectUsesContextRoutingWhenLivePresenceRoutingFieldsArePartial() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());

    SessionContext context =
        new SessionContext(
            41L,
            22L,
            ACCOUNT_ID,
            "demo@example.com",
            7001L,
            "Emberline",
            99L,
            "R-1",
            "jwt",
            "en-NZ",
            99L,
            "fallback-world",
            "fallback-realm",
            3L,
            "ISOLATED");
    GameplayPresence livePresence =
        new GameplayPresence(
            41L,
            22L,
            101L,
            "ISOLATED",
            "demo",
            null,
            17L,
            ACCOUNT_ID,
            7001L,
            "Emberline",
            GameplayPresenceRole.PLAYER,
            1000L,
            null,
            1200L,
            1300L);

    when(sessionRoutingNormalizationService.resolveProjectedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(gameplayPresenceService.findConnectedBySessionId(41L))
        .thenReturn(Optional.of(livePresence));

    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            sessionRoutingNormalizationService,
            gameplayPresenceService,
            presenceProperties,
            () -> 1_700_000_000_000L);

    service.recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    ArgumentCaptor<Object> stateCaptor = ArgumentCaptor.forClass(Object.class);
    verify(valueOperations)
        .set(
            eq("accountrecentpresence:22:" + ACCOUNT_ID),
            stateCaptor.capture(),
            eq(Duration.ofMinutes(5)));

    AccountRecentPresenceState state =
        assertInstanceOf(AccountRecentPresenceState.class, stateCaptor.getValue());
    assertEquals(99L, state.gameInstanceId());
    assertEquals("ISOLATED", state.playableStateScope());
    assertEquals("fallback-world", state.worldSlug());
    assertEquals("fallback-realm", state.realmSlug());
    assertEquals(3L, state.pointerVersion());
  }

  @Test
  void unreadableRecentPresenceFailsClosedWithoutOverwritingRetainedEvidence() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID))
        .thenThrow(new SerializationException("old AccountRecentPresenceState record shape"));

    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());
    RedisAccountRecentPresenceService service =
        new RedisAccountRecentPresenceService(
            redisTemplate,
            Mockito.mock(SessionRoutingNormalizationService.class),
            gameplayPresenceService,
            presenceProperties,
            () -> 1_700_000_000_000L);
    SessionContext context =
        new SessionContext(41L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, null, "jwt");

    assertEquals(Map.of(), service.findByAccountIds(22L, List.of(ACCOUNT_ID)));
    service.recordConnected(context);

    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    verify(redisTemplate, never()).multi();
    verify(redisTemplate, never()).exec();
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void findByAccountIdsDoesNotExposeMalformedRetainedAccountIdentity() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID))
        .thenReturn(
            new AccountRecentPresenceState(
                22L, "123", 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS));

    RedisAccountRecentPresenceService service =
        newService(redisTemplate, Mockito.mock(GameplayPresenceService.class));

    assertEquals(Map.of(), service.findByAccountIds(22L, List.of(ACCOUNT_ID)));
    verify(redisTemplate, never()).delete(anyString());
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
  }

  @Test
  void findByAccountIdsReturnsExactCanonicalAccountState() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    AccountRecentPresenceState state =
        new AccountRecentPresenceState(
            22L, ACCOUNT_ID, 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID)).thenReturn(state);
    RedisAccountRecentPresenceService service =
        newService(redisTemplate, Mockito.mock(GameplayPresenceService.class));

    assertEquals(Map.of(ACCOUNT_ID, state), service.findByAccountIds(22L, List.of(ACCOUNT_ID)));
  }

  @Test
  void recordConnectedPreservesReadableRetainedStateWithMalformedAccountIdentity() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID))
        .thenReturn(
            new AccountRecentPresenceState(
                22L, "123", 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS));
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());

    newService(redisTemplate, gameplayPresenceService).recordConnected(testContext());

    verify(redisTemplate).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate).unwatch();
    verify(redisTemplate, never()).multi();
    verify(redisTemplate, never()).exec();
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void malformedCallerAccountIdsDoNotReadOrWriteRecentPresence() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    RedisAccountRecentPresenceService service = newService(redisTemplate, gameplayPresenceService);

    for (String accountId :
        List.of(
            "123",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-4000-8000-00000000000A",
            " " + ACCOUNT_ID,
            ACCOUNT_ID + " ")) {
      assertEquals(Map.of(), service.findByAccountIds(22L, List.of(accountId)));
      service.recordConnected(
          new SessionContext(41L, 22L, accountId, "demo@example.com", 0L, null, 0L, null, "jwt"));
    }

    verify(valueOperations, never()).get(anyString());
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    verify(redisTemplate, never()).watch(anyString());
    verifyNoInteractions(gameplayPresenceService);
  }

  @Test
  void recordConnectedRetriesAfterWatchedValueChanges() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    AccountRecentPresenceState retained =
        new AccountRecentPresenceState(
            22L, ACCOUNT_ID, 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    AccountRecentPresenceState concurrentReplacement =
        new AccountRecentPresenceState(
            22L, ACCOUNT_ID, 1_699_999_999_500L, AccountRecentPresenceDisposition.LOGOUT);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID))
        .thenReturn(retained, concurrentReplacement);
    when(redisTemplate.exec()).thenReturn(null, List.of("OK"));

    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    RedisAccountRecentPresenceService service = newService(redisTemplate, gameplayPresenceService);

    service.recordConnected(testContext());

    verify(redisTemplate, times(2)).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate, times(2)).multi();
    verify(redisTemplate, times(2)).exec();
    verify(valueOperations, times(2)).get("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(valueOperations, times(2))
        .set(eq("accountrecentpresence:22:" + ACCOUNT_ID), any(), eq(Duration.ofMinutes(5)));
  }

  @Test
  void recordConnectedStopsAfterEightWatchedConflictsWithoutCommittingTheProjection() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    AccountRecentPresenceState retained =
        new AccountRecentPresenceState(
            22L, ACCOUNT_ID, 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID)).thenReturn(retained);
    when(redisTemplate.exec()).thenReturn(null);

    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    RedisAccountRecentPresenceService service = newService(redisTemplate, gameplayPresenceService);

    service.recordConnected(testContext());

    verify(redisTemplate, times(8)).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate, times(8)).multi();
    verify(redisTemplate, times(8)).exec();
    verify(valueOperations, times(8)).get("accountrecentpresence:22:" + ACCOUNT_ID);
    // Each null EXEC represents a WATCH conflict, so none of the queued replacement writes
    // committed.
    verify(valueOperations, times(8))
        .set(eq("accountrecentpresence:22:" + ACCOUNT_ID), any(), eq(Duration.ofMinutes(5)));
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void recordActivityStopsAfterEightWatchedConflictsWithoutCommittingTheProjection() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    AccountRecentPresenceState retained =
        new AccountRecentPresenceState(
            22L, ACCOUNT_ID, 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID)).thenReturn(retained);
    when(redisTemplate.exec()).thenReturn(null);

    SessionRoutingNormalizationService sessionRoutingNormalizationService =
        Mockito.mock(SessionRoutingNormalizationService.class);
    when(sessionRoutingNormalizationService.resolveProjectedSessionContext("41"))
        .thenReturn(Optional.of(testContext()));
    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    RedisAccountRecentPresenceService service =
        newService(redisTemplate, gameplayPresenceService, sessionRoutingNormalizationService);

    service.recordActivity(41L);

    verify(redisTemplate, times(8)).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate, times(8)).multi();
    verify(redisTemplate, times(8)).exec();
    verify(valueOperations, times(8)).get("accountrecentpresence:22:" + ACCOUNT_ID);
    // Each null EXEC represents a WATCH conflict, so none of the queued replacement writes
    // committed.
    verify(valueOperations, times(8))
        .set(eq("accountrecentpresence:22:" + ACCOUNT_ID), any(), eq(Duration.ofMinutes(5)));
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void recordConnectedDoesNotRetryWriteWhenConflictRevealsUnreadableReplacement() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    AccountRecentPresenceState retained =
        new AccountRecentPresenceState(
            22L, ACCOUNT_ID, 1_699_999_999_000L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID))
        .thenReturn(retained)
        .thenThrow(new SerializationException("concurrent replacement has old record shape"));
    when(redisTemplate.exec()).thenReturn(null);

    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    RedisAccountRecentPresenceService service = newService(redisTemplate, gameplayPresenceService);

    service.recordConnected(testContext());

    verify(redisTemplate, times(2)).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    verify(redisTemplate).unwatch();
    // The first queued SET was rejected by EXEC; the unreadable replacement prevents a retry SET.
    verify(valueOperations)
        .set(eq("accountrecentpresence:22:" + ACCOUNT_ID), any(), eq(Duration.ofMinutes(5)));
  }

  @Test
  void recordConnectedPreservesTypeMismatchedRetainedValue() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID)).thenReturn("legacy value");

    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    RedisAccountRecentPresenceService service = newService(redisTemplate, gameplayPresenceService);

    service.recordConnected(testContext());

    verify(redisTemplate).watch("accountrecentpresence:22:" + ACCOUNT_ID);
    verify(redisTemplate).unwatch();
    verify(redisTemplate, never()).multi();
    verify(redisTemplate, never()).exec();
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void recordConnectedPropagatesRedisReadOutage() {
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    stubTransactionExecution(redisTemplate);
    when(valueOperations.get("accountrecentpresence:22:" + ACCOUNT_ID))
        .thenThrow(new IllegalStateException("Redis unavailable"));

    GameplayPresenceService gameplayPresenceService = Mockito.mock(GameplayPresenceService.class);
    when(gameplayPresenceService.findConnectedBySessionId(41L)).thenReturn(Optional.empty());
    RedisAccountRecentPresenceService service = newService(redisTemplate, gameplayPresenceService);

    assertThrows(IllegalStateException.class, () -> service.recordConnected(testContext()));
    verify(redisTemplate, never()).multi();
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
  }

  private static RedisAccountRecentPresenceService newService(
      RedisTemplate<String, Object> redisTemplate,
      GameplayPresenceService gameplayPresenceService) {
    return newService(
        redisTemplate,
        gameplayPresenceService,
        Mockito.mock(SessionRoutingNormalizationService.class));
  }

  private static RedisAccountRecentPresenceService newService(
      RedisTemplate<String, Object> redisTemplate,
      GameplayPresenceService gameplayPresenceService,
      SessionRoutingNormalizationService sessionRoutingNormalizationService) {
    PresenceProperties presenceProperties = new PresenceProperties();
    presenceProperties.setRecentPresenceTtlMs(Duration.ofMinutes(5).toMillis());
    return new RedisAccountRecentPresenceService(
        redisTemplate,
        sessionRoutingNormalizationService,
        gameplayPresenceService,
        presenceProperties,
        () -> 1_700_000_000_000L);
  }

  private static SessionContext testContext() {
    return new SessionContext(41L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, null, "jwt");
  }

  @SuppressWarnings("unchecked")
  private static void stubTransactionExecution(RedisTemplate<String, Object> redisTemplate) {
    when(redisTemplate.execute(Mockito.any(SessionCallback.class)))
        .thenAnswer(
            invocation -> {
              SessionCallback<?> callback = invocation.getArgument(0);
              return callback.execute((RedisOperations<String, Object>) redisTemplate);
            });
    when(redisTemplate.exec()).thenReturn(List.of("OK"));
  }
}
