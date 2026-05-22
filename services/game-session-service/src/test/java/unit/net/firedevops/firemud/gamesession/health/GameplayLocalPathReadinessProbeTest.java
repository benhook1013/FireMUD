package net.firedevops.firemud.gamesession.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.health.GameplayLocalPathReadinessProbe.ProbeResult;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

class GameplayLocalPathReadinessProbeTest {
  private static final long PROBE_SESSION_ID_BASE = 9_223_372_036_854_770_000L;
  private static final long PROBE_ACCOUNT_ID = 9_223_372_036_854_770_001L;
  private static final long PROBE_CHARACTER_ID = 9_223_372_036_854_770_002L;

  @Test
  void sessionContextProbeReturnsUpWhenContextRoundTrips() {
    SessionContextService sessionContextService = mock(SessionContextService.class);
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    when(sessionContextService.findByTenantAndSessionId(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              long sessionId = invocation.getArgument(1, Long.class);
              long probeSequence = sessionId - PROBE_SESSION_ID_BASE;
              return Optional.of(
                  new SessionContext(
                      sessionId,
                      0L,
                      PROBE_ACCOUNT_ID,
                      PROBE_CHARACTER_ID,
                      0L,
                      "readiness-room-" + probeSequence,
                      "readiness-probe"));
            });

    GameplayLocalPathReadinessProbe probe =
        new GameplayLocalPathReadinessProbe(sessionContextService, redisTemplate);

    ProbeResult result = probe.probeSessionContextStore();

    assertTrue(result.ready());
    assertEquals("ROUND_TRIP_OK", result.detail());
    ArgumentCaptor<SessionContext> contextCaptor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(contextCaptor.capture());
    SessionContext savedContext = contextCaptor.getValue();
    assertTrue(savedContext.sessionId() > PROBE_SESSION_ID_BASE);
    assertTrue(savedContext.roomInstanceId().startsWith("readiness-room-"));
    verify(sessionContextService).deleteBySessionId(0L, savedContext.sessionId());
  }

  @Test
  void commandQueueProbeReturnsUpWhenQueueWriteRoundTrips() {
    SessionContextService sessionContextService = mock(SessionContextService.class);
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ListOperations<String, Object> listOperations = mock(ListOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOperations);
    when(listOperations.index(anyString(), anyLong())).thenReturn("N|READINESS_LOOK");

    GameplayLocalPathReadinessProbe probe =
        new GameplayLocalPathReadinessProbe(sessionContextService, redisTemplate);

    ProbeResult result = probe.probeCommandQueueStore();

    assertTrue(result.ready());
    assertEquals("QUEUE_WRITE_OK", result.detail());
    verify(listOperations).rightPush(anyString(), anyString());
    verify(redisTemplate).delete(anyString());
  }

  @Test
  void sessionContextProbeCleansUpEvenWhenRoundTripFails() {
    SessionContextService sessionContextService = mock(SessionContextService.class);
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    when(sessionContextService.findByTenantAndSessionId(anyLong(), anyLong()))
        .thenReturn(Optional.empty());

    GameplayLocalPathReadinessProbe probe =
        new GameplayLocalPathReadinessProbe(sessionContextService, redisTemplate);

    ProbeResult result = probe.probeSessionContextStore();

    assertEquals(false, result.ready());
    ArgumentCaptor<SessionContext> contextCaptor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(contextCaptor.capture());
    verify(sessionContextService).deleteBySessionId(0L, contextCaptor.getValue().sessionId());
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void commandQueueProbeDeletesKeyWhenRoundTripFails() {
    SessionContextService sessionContextService = mock(SessionContextService.class);
    @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    ListOperations<String, Object> listOperations = mock(ListOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOperations);
    when(listOperations.index(anyString(), anyLong())).thenReturn("WRONG");

    GameplayLocalPathReadinessProbe probe =
        new GameplayLocalPathReadinessProbe(sessionContextService, redisTemplate);

    ProbeResult result = probe.probeCommandQueueStore();

    assertEquals(false, result.ready());
    verify(listOperations).rightPush(anyString(), anyString());
    verify(redisTemplate).delete(anyString());
  }
}
