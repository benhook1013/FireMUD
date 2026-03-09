package net.firedevops.firemud.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import net.firedevops.firemud.service.quota.ScriptQuotaService;
import net.firedevops.firemud.service.tick.ScriptTickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ScriptTickServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private org.springframework.data.redis.core.ValueOperations<String, Object> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private ScriptQuotaService quotaService;
  private net.firedevops.firemud.common.conflict.ConflictTracker conflictTracker;
  private ScriptTickService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
    valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    quotaService = mock(ScriptQuotaService.class);
    when(quotaService.tryAcquire(any(Long.class), any(Long.class))).thenReturn(true);
    meterRegistry = new SimpleMeterRegistry();
    conflictTracker = mock(net.firedevops.firemud.common.conflict.ConflictTracker.class);
    service =
        new ScriptTickServiceImpl(redisTemplate, meterRegistry, quotaService, conflictTracker);
    ((ScriptTickServiceImpl) service).init();
  }

  @Test
  void enqueueEventPushesToQueue() {
    service.enqueueEvent(1L, 2L, "evt");
    verify(listOps).rightPush(any(String.class), any(Object.class));
    verify(quotaService).tryAcquire(1L, 2L);
  }

  @Test
  void enqueueEventSkippedWhenQuotaExceeded() {
    when(quotaService.tryAcquire(any(Long.class), any(Long.class))).thenReturn(false);
    service.enqueueEvent(1L, 2L, "evt");
    org.mockito.Mockito.verifyNoInteractions(listOps);
  }

  @Test
  void processTickAttemptsLockAndExecutesScript() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    service.processTick(1L, 2L);
    ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.<String>anyList());
  }

  @Test
  void lockContentionRecordsConflict() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(false);

    service.processTick(1L, 2L);

    verify(conflictTracker).recordConflict("script:1:2");
  }

  @Test
  void slowTickIncrementsBudgetMetric() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(redisTemplate.execute(
            any(RedisScript.class), org.mockito.ArgumentMatchers.<String>anyList()))
        .thenReturn(1L);
    when(listOps.size(any(String.class))).thenReturn(0L);

    org.springframework.test.util.ReflectionTestUtils.setField(service, "tickDurationMs", 0L);

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("automation_tick_budget_exceeded_total").counter().count(), 0.001);
  }
}
