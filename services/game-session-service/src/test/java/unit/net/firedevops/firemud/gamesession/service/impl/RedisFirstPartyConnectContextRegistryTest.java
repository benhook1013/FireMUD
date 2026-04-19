package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class RedisFirstPartyConnectContextRegistryTest {
  private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();
  private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
  private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
  private RedisFirstPartyConnectContextRegistry registry;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    doAnswer(
            invocation -> {
              store.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(valueOperations)
        .set(anyString(), any(), any(Duration.class));
    when(valueOperations.get(anyString()))
        .thenAnswer(invocation -> store.get(invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              store.remove(invocation.getArgument(0));
              return null;
            })
        .when(redisTemplate)
        .delete(anyString());

    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setTtlMs(1500L);
    registry = new RedisFirstPartyConnectContextRegistry(redisTemplate, properties);
  }

  @Test
  void registerStoresContextWithShortTtl() {
    FirstPartyConnectContext context =
        new FirstPartyConnectContext(
            77L, 22L, "demo", "production", 41L, 17L, "scope-1", "jti-1", "req-1", "gateway-1");

    registry.register(91L, context);

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(valueOperations)
        .set(keyCaptor.capture(), org.mockito.ArgumentMatchers.eq(context), ttlCaptor.capture());
    assertEquals("sessionctx:first-party:91:connect-context", keyCaptor.getValue());
    assertEquals(Duration.ofMillis(1500L), ttlCaptor.getValue());
    assertEquals(Optional.of(context), registry.find(91L));
  }

  @Test
  void unregisterRemovesStoredContext() {
    FirstPartyConnectContext context =
        new FirstPartyConnectContext(
            77L, 22L, "demo", "production", 41L, 17L, "scope-1", "jti-1", "req-1", "gateway-1");
    registry.register(91L, context);

    registry.unregister(91L);

    assertEquals(Optional.empty(), registry.find(91L));
    verify(redisTemplate).delete("sessionctx:first-party:91:connect-context");
  }
}
