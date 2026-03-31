package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisDisconnectDeduplicationServiceTest {
  @Test
  void shouldProcessAcceptsNewerDisconnectSequences() {
    ConcurrentMap<String, Long> store = new ConcurrentHashMap<>();
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    when(redisTemplate.execute(any(), anyList(), any(), any()))
        .thenAnswer(
            invocation -> {
              List<String> keys = invocation.getArgument(1);
              String key = keys.get(0);
              long sequence = Long.parseLong(invocation.getArgument(2));
              Long current = store.get(key);
              if (current == null || sequence > current) {
                store.put(key, sequence);
                return 1L;
              }
              return 0L;
            });

    RedisDisconnectDeduplicationService service =
        new RedisDisconnectDeduplicationService(redisTemplate, 1500L);

    assertTrue(service.shouldProcess("proxy-1", 1L));
    assertFalse(service.shouldProcess("proxy-1", 1L));
    assertTrue(service.shouldProcess("proxy-1", 2L));
  }

  @Test
  void shouldProcessFailsOpenWhenRedisIsUnavailable() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    when(redisTemplate.execute(any(), anyList(), any(), any()))
        .thenThrow(new RuntimeException("redis unavailable"));

    RedisDisconnectDeduplicationService service =
        new RedisDisconnectDeduplicationService(redisTemplate, 1500L);

    assertTrue(service.shouldProcess("proxy-1", 1L));
  }
}
