package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.config.DevIsolatedProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class SessionRateLimiterImplTest {
  @Test
  void allowsOnlyConfiguredRate() {
    ConcurrentMap<String, String> store = new ConcurrentHashMap<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString()))
        .thenAnswer(
            inv -> {
              String key = inv.getArgument(0);
              long val = Long.parseLong(store.getOrDefault(key, "0"));
              val++;
              store.put(key, Long.toString(val));
              return val;
            });
    doAnswer(
            inv -> {
              store.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(anyString(), anyString());
    doAnswer(inv -> true).when(redis).expire(anyString(), any(Duration.class));
    SessionRateLimiterImpl limiter =
        new SessionRateLimiterImpl(redis, 2, new DevIsolatedProperties(false));
    assertTrue(limiter.allow(1L));
    assertTrue(limiter.allow(1L));
    assertFalse(limiter.allow(1L));
  }
}
