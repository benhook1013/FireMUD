package net.firedevops.firemud.automationscripting.service.quota;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
class ScriptQuotaServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ScriptQuotaServiceImpl service;

  @BeforeEach
  void setup() {
    AtomicInteger calls = new AtomicInteger();
    redisTemplate =
        mock(
            RedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return (long) calls.incrementAndGet();
              }
              return null;
            });
    service = new ScriptQuotaServiceImpl(redisTemplate, new SimpleMeterRegistry());
    setField(service, "limit", 2L);
    setField(service, "windowSeconds", 60L);
    service.init();
  }

  @Test
  void tryAcquireAppliesLimit() {
    assertTrue(service.tryAcquire(1L, 2L));
    assertTrue(service.tryAcquire(1L, 2L));
    assertFalse(service.tryAcquire(1L, 2L));

    verify(redisTemplate, never())
        .expire(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Duration.class));
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
