package net.firedevops.firemud.gamesession.service.impl;

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
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class IpConnectionLimiterImplTest {
  @Test
  void enforcesMaxConnectionsPerIp() {
    ConcurrentMap<String, String> store = new ConcurrentHashMap<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
    when(ops.increment(anyString()))
        .thenAnswer(
            inv -> {
              String key = inv.getArgument(0);
              long val = Long.parseLong(store.getOrDefault(key, "0"));
              val++;
              store.put(key, Long.toString(val));
              return val;
            });
    when(ops.decrement(anyString()))
        .thenAnswer(
            inv -> {
              String key = inv.getArgument(0);
              long val = Long.parseLong(store.getOrDefault(key, "0")) - 1;
              store.put(key, Long.toString(val));
              return val;
            });
    doAnswer(
            inv -> {
              store.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));
    doAnswer(
            inv -> {
              store.remove(inv.getArgument(0));
              return null;
            })
        .when(redis)
        .delete(anyString());

    IpConnectionLimiterImpl limiter =
        new IpConnectionLimiterImpl(redis, 1, 60, new DevIsolatedProperties(false));
    assertTrue(limiter.canAccept("1.2.3.4"));
    limiter.register("1.2.3.4", 1L);
    assertFalse(limiter.canAccept("1.2.3.4"));
    limiter.release(1L);
    assertTrue(limiter.canAccept("1.2.3.4"));
  }
}
