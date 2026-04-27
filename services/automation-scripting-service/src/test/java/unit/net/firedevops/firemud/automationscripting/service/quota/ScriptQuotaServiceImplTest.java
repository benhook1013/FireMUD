package net.firedevops.firemud.automationscripting.service.quota;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.automationscripting.config.ScriptQuotaProperties;
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
    ScriptQuotaProperties properties = new ScriptQuotaProperties();
    properties.setLimit(2L);
    properties.setWindowSeconds(60L);
    service = new ScriptQuotaServiceImpl(redisTemplate, new SimpleMeterRegistry(), properties);
    service.init();
  }

  @Test
  void tryAcquireAppliesLimit() {
    assertTrue(service.tryAcquire("tenant-1", "script-1"));
    assertTrue(service.tryAcquire("tenant-1", "script-1"));
    assertFalse(service.tryAcquire("tenant-1", "script-1"));

    verify(redisTemplate, never())
        .expire(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Duration.class));
  }
}
