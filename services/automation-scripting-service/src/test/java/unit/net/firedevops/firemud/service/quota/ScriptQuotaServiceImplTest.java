package net.firedevops.firemud.service.quota;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

class ScriptQuotaServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ValueOperations<String, Object> valueOps;
  private ScriptQuotaServiceImpl service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    service = new ScriptQuotaServiceImpl(redisTemplate, new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "limit", 2L);
    ReflectionTestUtils.setField(service, "windowSeconds", 60L);
    service.init();
  }

  @Test
  void tryAcquireAppliesLimit() {
    when(valueOps.increment("script_quota:1:2")).thenReturn(1L).thenReturn(2L).thenReturn(3L);

    assertTrue(service.tryAcquire(1L, 2L));
    assertTrue(service.tryAcquire(1L, 2L));
    assertFalse(service.tryAcquire(1L, 2L));

    verify(redisTemplate).expire("script_quota:1:2", Duration.ofSeconds(60L));
  }
}
