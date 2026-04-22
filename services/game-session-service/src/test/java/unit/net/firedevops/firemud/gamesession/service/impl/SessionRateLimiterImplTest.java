package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

@SuppressWarnings("unchecked")
class SessionRateLimiterImplTest {
  @Test
  void allowsOnlyConfiguredRate() {
    AtomicInteger calls = new AtomicInteger();
    StringRedisTemplate redis =
        mock(
            StringRedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return (long) calls.incrementAndGet();
              }
              return null;
            });
    SessionRateLimiterImpl limiter = new SessionRateLimiterImpl(redis, 2);
    assertTrue(limiter.allow(1L));
    assertTrue(limiter.allow(1L));
    assertFalse(limiter.allow(1L));
    verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
  }
}
