package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

class TickLockServiceImplTest {
  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private TickLockServiceImpl service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    meterRegistry = new SimpleMeterRegistry();
    service = new TickLockServiceImpl(redisTemplate, meterRegistry);
    service.initMetrics();
    ReflectionTestUtils.setField(service, "tickDurationMs", 1000L);
  }

  @Test
  void acquireAndReleaseLock() {
    when(valueOps.setIfAbsent(eq("tick:lock:1:2"), eq("1"), any(Duration.class))).thenReturn(true);

    assertTrue(service.acquireLock(1L, 2L));
    service.releaseLock(1L, 2L);

    verify(redisTemplate).delete("tick:lock:1:2");
  }

  @Test
  void lockContentionIncrementsMetric() {
    when(valueOps.setIfAbsent(eq("tick:lock:1:2"), eq("1"), any(Duration.class))).thenReturn(false);

    service.acquireLock(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("tick_lock_contention_total").counter().count(), 0.001);
  }
}
