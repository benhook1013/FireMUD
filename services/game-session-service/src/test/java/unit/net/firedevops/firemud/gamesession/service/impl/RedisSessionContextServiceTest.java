package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class RedisSessionContextServiceTest {
  private static final Duration TTL = Duration.ofMillis(1000L);
  private static final String DISTINCTIVE_JWT = "distinctive-raw-backend-jwt-for-test";

  private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
  private final ValueOperations<String, Object> valueOperations =
      Mockito.mock(ValueOperations.class);
  private RedisSessionContextService service;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(redisTemplate.execute(Mockito.any(SessionCallback.class)))
        .thenAnswer(
            invocation -> {
              SessionCallback<?> callback = invocation.getArgument(0);
              return callback.execute((RedisOperations<String, Object>) redisTemplate);
            });
    when(redisTemplate.exec()).thenReturn(List.of(1L));
    service = new RedisSessionContextService(redisTemplate, TTL.toMillis());
  }

  @Test
  void saveStoresContextInSessionAndIdentityKeys() throws Exception {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, DISTINCTIVE_JWT);

    service.save(context);

    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    SessionContext persisted = context.withoutJwt();
    verify(valueOperations).set("sessionctx:10:1:context", persisted, TTL);
    verify(valueOperations).set("sessionctx:session:1:context", persisted, TTL);
    verify(valueOperations).set("sessionctx:10:identity:40:30:context", persisted, TTL);

    ArgumentCaptor<SessionContext> storedContexts = ArgumentCaptor.forClass(SessionContext.class);
    verify(valueOperations, Mockito.times(3)).set(anyString(), storedContexts.capture(), eq(TTL));
    for (SessionContext stored : storedContexts.getAllValues()) {
      assertNull(stored.jwt());
      assertFalse(serialize(stored).contains(DISTINCTIVE_JWT));
    }
  }

  @Test
  void saveRemovesStaleSessionKeyBeforeWritingNewIdentityBinding() {
    SessionContext existing = new SessionContext(1L, 10L, 20L, 30L, 40L, "old-jwt");
    SessionContext replacement = new SessionContext(2L, 10L, 20L, 30L, 40L, "new-jwt");
    when(valueOperations.get("sessionctx:10:identity:40:30:context")).thenReturn(existing);

    service.save(replacement);

    verify(redisTemplate).delete("sessionctx:10:1:context");
    SessionContext persisted = replacement.withoutJwt();
    verify(valueOperations).set("sessionctx:10:2:context", persisted, TTL);
    verify(valueOperations).set("sessionctx:10:identity:40:30:context", persisted, TTL);
  }

  @Test
  void findByTenantAndSessionIdProjectsLegacyJwtOut() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, DISTINCTIVE_JWT);
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByTenantAndSessionId(10L, 1L);

    assertEquals(Optional.of(context.withoutJwt()), result);
    assertNull(result.orElseThrow().jwt());
  }

  @Test
  void findBySessionIdProjectsLegacyJwtOut() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, DISTINCTIVE_JWT);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(context);

    Optional<SessionContext> result = service.findBySessionId(1L);

    assertEquals(Optional.of(context.withoutJwt()), result);
    assertNull(result.orElseThrow().jwt());
  }

  @Test
  void findByGameplayIdentityProjectsLegacyJwtOut() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, DISTINCTIVE_JWT);
    when(valueOperations.get("sessionctx:10:identity:40:30:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByGameplayIdentity(10L, 40L, 30L);

    assertEquals(Optional.of(context.withoutJwt()), result);
    assertNull(result.orElseThrow().jwt());
  }

  @Test
  void findByGameplayNameProjectsLegacyJwtOut() {
    SessionContext context =
        new SessionContext(
            1L,
            10L,
            20L,
            "demo",
            30L,
            "Hero",
            40L,
            "R-1",
            DISTINCTIVE_JWT,
            "en",
            40L,
            "world",
            "realm",
            1L,
            "SHARED");
    when(valueOperations.get("sessionctx:10:identity:40:name:hero:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByGameplayName(10L, 40L, "Hero");

    assertEquals(Optional.of(context.withoutJwt()), result);
    assertNull(result.orElseThrow().jwt());
  }

  @Test
  void deleteBySessionIdRemovesBothKeys() {
    SessionContext context = new SessionContext(1L, 10L, 20L, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(redisTemplate).delete("sessionctx:10:identity:40:30:context");
  }

  @Test
  void savePreservesLocaleTagInStoredContext() {
    SessionContext context =
        new SessionContext(1L, 10L, 20L, null, 30L, null, 40L, "R-1", "jwt", "fr", 40L);

    service.save(context);

    verify(valueOperations).set("sessionctx:10:1:context", context.withoutJwt(), TTL);
    assertEquals("fr", context.localeTag());
  }

  private String serialize(SessionContext context) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
      objectOutput.writeObject(context);
    }
    return output.toString(StandardCharsets.ISO_8859_1);
  }
}
