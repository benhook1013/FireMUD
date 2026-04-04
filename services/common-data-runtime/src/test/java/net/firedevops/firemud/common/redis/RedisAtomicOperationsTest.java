package net.firedevops.firemud.common.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
class RedisAtomicOperationsTest {
  @Test
  void incrementWithTtlUsesAtomicScriptResult() {
    AtomicInteger calls = new AtomicInteger();
    RedisTemplate<String, Object> redisTemplate =
        mock(
            RedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return (long) calls.incrementAndGet();
              }
              return null;
            });

    assertEquals(
        1L,
        RedisAtomicOperations.incrementWithTtl(redisTemplate, "counter", Duration.ofSeconds(1)));
    assertEquals(
        2L,
        RedisAtomicOperations.incrementWithTtl(redisTemplate, "counter", Duration.ofSeconds(1)));
    assertEquals(
        3L,
        RedisAtomicOperations.incrementWithTtl(redisTemplate, "counter", Duration.ofSeconds(1)));
  }

  @Test
  void reserveBoundedCounterStopsAtLimit() {
    AtomicInteger calls = new AtomicInteger();
    RedisTemplate<String, Object> redisTemplate =
        mock(
            RedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return calls.incrementAndGet() == 1 ? 1L : -1L;
              }
              return null;
            });

    assertTrue(
        RedisAtomicOperations.reserveBoundedCounter(
            redisTemplate, "counter", "reservation", 1L, Duration.ofSeconds(1), "session-1"));
    assertFalse(
        RedisAtomicOperations.reserveBoundedCounter(
            redisTemplate, "counter", "reservation", 1L, Duration.ofSeconds(1), "session-2"));
  }
}
