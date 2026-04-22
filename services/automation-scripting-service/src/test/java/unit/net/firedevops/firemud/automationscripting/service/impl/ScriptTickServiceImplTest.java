package net.firedevops.firemud.automationscripting.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import net.firedevops.firemud.automationscripting.service.quota.ScriptQuotaService;
import net.firedevops.firemud.automationscripting.service.tick.ScriptTickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings("unchecked")
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
    when(quotaService.tryAcquire(any(String.class), any(String.class))).thenReturn(true);
    meterRegistry = new SimpleMeterRegistry();
    conflictTracker = mock(net.firedevops.firemud.common.conflict.ConflictTracker.class);
    service =
        new ScriptTickServiceImpl(redisTemplate, meterRegistry, quotaService, conflictTracker);
    ((ScriptTickServiceImpl) service).init();
  }

  @Test
  void enqueueEventPushesToQueue() {
    service.enqueueEvent("tenant-1", "instance-1", "script-1", "evt");
    verify(listOps)
        .rightPush(
            "automation:tick:{tenant:tenant-1:instance:instance-1:script:script-1}:queue", "evt");
    verify(quotaService).tryAcquire("tenant-1", "script-1");
  }

  @Test
  void enqueueEventSkippedWhenQuotaExceeded() {
    when(quotaService.tryAcquire(any(String.class), any(String.class))).thenReturn(false);
    service.enqueueEvent("tenant-1", "instance-1", "script-1", "evt");
    org.mockito.Mockito.verifyNoInteractions(listOps);
  }

  @Test
  void processTickAttemptsLockAndExecutesScript() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    service.processTick("tenant-1", "instance-1", "script-1");
    ArgumentCaptor<RedisScript<?>> scriptCaptor = redisScriptCaptor();
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.<String>anyList());
    verify(redisTemplate, never()).delete(any(String.class));
  }

  @Test
  void processTickUsesAutomationNamespacedKeys() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);

    service.processTick("tenant-1", "instance-1", "script-1");

    verify(valueOps)
        .setIfAbsent(
            eq("automation:tick:{tenant:tenant-1:instance:instance-1:script:script-1}:lock"),
            any(Object.class),
            any(Duration.class));
    verify(listOps)
        .size("automation:tick:{tenant:tenant-1:instance:instance-1:script:script-1}:pending");
  }

  @Test
  void lockContentionRecordsConflict() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(false);

    service.processTick("tenant-1", "instance-1", "script-1");

    verify(conflictTracker).recordConflict("script:tenant-1:instance-1:script-1");
  }

  @Test
  void slowTickIncrementsBudgetMetric() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(redisTemplate.execute(
            any(RedisScript.class), org.mockito.ArgumentMatchers.<String>anyList()))
        .thenReturn(1L);
    when(listOps.size(any(String.class))).thenReturn(0L);

    setField(service, "tickDurationMs", 0L);

    service.processTick("tenant-1", "instance-1", "script-1");

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("automation_tick_budget_exceeded_total").counter().count(), 0.001);
  }

  private static ArgumentCaptor<RedisScript<?>> redisScriptCaptor() {
    return (ArgumentCaptor<RedisScript<?>>)
        (ArgumentCaptor<?>) ArgumentCaptor.forClass(RedisScript.class);
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to set field " + fieldName, e);
    }
  }
}
