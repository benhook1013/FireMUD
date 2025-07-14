package net.firedevops.firemud.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.service.TickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class TickServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private org.springframework.data.redis.core.ValueOperations<String, Object> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private TickService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
    valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    meterRegistry = new SimpleMeterRegistry();
    service = new TickServiceImpl(redisTemplate, meterRegistry);
    ((TickServiceImpl) service).init();
  }

  @Test
  void enqueueCommandPushesToQueue() {
    service.enqueueCommand(2L, "look", false);
    verify(listOps).rightPush(any(String.class), any());
  }

  @Test
  void processTickAttemptsLockAndExecutesScript() {
    when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
    service.processTick(2L);
    ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(scriptCaptor.capture(), any(List.class));
  }

  @Test
  void lockContentionIncrementsMetric() {
    when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(false);

    service.processTick(2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("game_session_lock_contention_total").counter().count(), 0.001);
  }

  @Test
  void slowTickIncrementsBudgetMetric() {
    when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
    when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L);
    when(listOps.size(any())).thenReturn(0L);

    org.springframework.test.util.ReflectionTestUtils.setField(service, "tickBudgetMs", 0L);

    service.processTick(2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("game_session_tick_budget_exceeded_total").counter().count(), 0.001);
  }
}
