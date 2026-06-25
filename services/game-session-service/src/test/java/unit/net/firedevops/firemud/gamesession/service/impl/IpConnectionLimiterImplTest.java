package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class IpConnectionLimiterImplTest {
  @Test
  void enforcesMaxConnectionsPerIp() {
    ConcurrentMap<String, String> store = new ConcurrentHashMap<>();
    AtomicInteger executeCalls = new AtomicInteger();
    StringRedisTemplate redis =
        mock(
            StringRedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return executeCalls.incrementAndGet() == 1 ? 1L : -1L;
              }
              return null;
            });
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);
    org.mockito.Mockito.when(ops.get(anyString()))
        .thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
    org.mockito.Mockito.when(ops.decrement(anyString()))
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
        .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    doAnswer(
            inv -> {
              store.remove(inv.getArgument(0));
              return null;
            })
        .when(redis)
        .delete(anyString());

    IpConnectionLimiterImpl limiter = new IpConnectionLimiterImpl(redis, 1, 60);
    assertTrue(limiter.canAccept("1.2.3.4"));
    assertTrue(limiter.tryRegister("1.2.3.4", 1L));
    assertFalse(limiter.tryRegister("1.2.3.4", 2L));
    store.put("ipconn:1.2.3.4", "1");
    store.put("sessionip:1", "1.2.3.4");
    store.put("sessionip:2", "1.2.3.4");
    assertFalse(limiter.canAccept("1.2.3.4"));
    limiter.release(1L);
    assertTrue(limiter.canAccept("1.2.3.4"));
  }

  @Test
  void canAcceptReplacementWhenExistingSessionAlreadyOwnsSameIp() {
    ConcurrentMap<String, String> store = new ConcurrentHashMap<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);
    org.mockito.Mockito.when(ops.get(anyString()))
        .thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));

    store.put("ipconn:1.2.3.4", "1");
    store.put("sessionip:9", "1.2.3.4");

    IpConnectionLimiterImpl limiter = new IpConnectionLimiterImpl(redis, 1, 60);

    assertTrue(limiter.canAccept("1.2.3.4", 9L));
    assertFalse(limiter.canAccept("1.2.3.4", 10L));
  }

  @Test
  void transferRegistrationMovesSessionReservationWithoutChangingCounter() {
    ConcurrentMap<String, String> store = new ConcurrentHashMap<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);
    org.mockito.Mockito.when(ops.get(anyString()))
        .thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
    org.mockito.Mockito.when(redis.getExpire(anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(30_000L);
    doAnswer(
            inv -> {
              store.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    doAnswer(
            inv -> {
              store.remove(inv.getArgument(0));
              return null;
            })
        .when(redis)
        .delete(anyString());

    store.put("ipconn:1.2.3.4", "1");
    store.put("sessionip:9", "1.2.3.4");

    IpConnectionLimiterImpl limiter = new IpConnectionLimiterImpl(redis, 1, 60);

    assertTrue(limiter.transferRegistration("1.2.3.4", 9L, 10L));
    assertFalse(store.containsKey("sessionip:9"));
    assertTrue("1.2.3.4".equals(store.get("sessionip:10")));
    assertTrue("1".equals(store.get("ipconn:1.2.3.4")));
  }
}
