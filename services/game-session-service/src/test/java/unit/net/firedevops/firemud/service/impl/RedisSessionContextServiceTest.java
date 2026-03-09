package unit.net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.service.SessionContext;
import net.firedevops.firemud.service.impl.RedisSessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class RedisSessionContextServiceTest {
  private static final Duration TTL = Duration.ofMillis(1000L);

  private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
  private final ValueOperations<String, Object> valueOperations =
      Mockito.mock(ValueOperations.class);
  private RedisSessionContextService service;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    service = new RedisSessionContextService(redisTemplate, TTL.toMillis());
  }

  @Test
  void saveStoresContextInSessionAndIdentityKeys() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, "jwt");

    service.save(context);

    verify(valueOperations).set("sessionctx:10:1:context", context, TTL);
    verify(valueOperations).set("sessionctx:10:identity:20:30:context", context, TTL);
  }

  @Test
  void findByTenantAndSessionIdReturnsPersistedContext() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByTenantAndSessionId(10L, 1L);

    assertEquals(Optional.of(context), result);
  }

  @Test
  void findByAccountAndPlayerReturnsPersistedContext() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:identity:20:30:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByAccountAndPlayer(10L, 20L, 30L);

    assertEquals(Optional.of(context), result);
  }

  @Test
  void deleteBySessionIdRemovesBothKeys() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(redisTemplate).delete("sessionctx:10:identity:20:30:context");
  }
}
