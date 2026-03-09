package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings("unchecked")
class TickServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private org.springframework.data.redis.core.ValueOperations<String, Object> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private net.firedevops.firemud.common.conflict.ConflictTracker conflictTracker;
  private net.firedevops.firemud.gamesession.repository.GameInstanceRepository repository;
  private TickService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
    valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    meterRegistry = new SimpleMeterRegistry();
    conflictTracker = mock(net.firedevops.firemud.common.conflict.ConflictTracker.class);
    repository = mock(net.firedevops.firemud.gamesession.repository.GameInstanceRepository.class);
    service =
        new TickServiceImpl(
            redisTemplate,
            meterRegistry,
            conflictTracker,
            repository,
            new DevIsolatedProperties(false));
    ((TickServiceImpl) service).init();
    var instance = new net.firedevops.firemud.gamesession.entity.GameInstance();
    instance.setTenantId(1L);
    when(repository.findById(anyLong())).thenReturn(java.util.Optional.of(instance));
  }

  @Test
  void enqueueCommandPushesToQueue() {
    service.enqueueCommand(2L, "look", false);
    verify(listOps).rightPush(any(String.class), any(Object.class));
  }

  @Test
  void processTickAttemptsLockAndExecutesScript() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    service.processTick(2L);
    ArgumentCaptor<RedisScript<?>> scriptCaptor = redisScriptCaptor();
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.<String>anyList());
  }

  @Test
  void lockContentionIncrementsMetric() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(false);

    service.processTick(2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("game_session_lock_contention_total").counter().count(), 0.001);
    verify(conflictTracker).recordConflict("session:1:2");
  }

  @Test
  void slowTickIncrementsBudgetMetric() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(redisTemplate.execute(
            any(RedisScript.class), org.mockito.ArgumentMatchers.<String>anyList()))
        .thenReturn(1L);
    when(listOps.size(any(String.class))).thenReturn(0L);

    setField(service, "tickBudgetMs", 0L);

    service.processTick(2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("game_session_tick_budget_exceeded_total").counter().count(), 0.001);
  }

  @Test
  void retryQueueGaugeRecorded() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.size(any(String.class))).thenReturn(3L);
    var instance = new net.firedevops.firemud.gamesession.entity.GameInstance();
    instance.setId(2L);
    instance.setTenantId(10L);
    when(repository.findById(2L)).thenReturn(java.util.Optional.of(instance));
    service.processTick(2L);
    org.junit.jupiter.api.Assertions.assertEquals(
        3.0,
        meterRegistry
            .get("tick_retry_queue_depth")
            .tags("tenantId", "10", "regionId", "2")
            .gauge()
            .value(),
        0.001);
  }

  @SuppressWarnings("unchecked")
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
