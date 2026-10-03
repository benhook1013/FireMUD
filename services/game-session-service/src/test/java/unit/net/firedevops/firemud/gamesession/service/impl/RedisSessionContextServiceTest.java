package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

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
  void saveStoresContextInSessionAndIdentityKeys() {
    SessionContext context = new SessionContext(1L, 10L, "20", 30L, 40L, "jwt");

    service.save(context);

    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    verify(valueOperations).set("sessionctx:10:1:context", context, TTL);
    verify(valueOperations).set("sessionctx:10:identity:40:30:context", context, TTL);
  }

  @Test
  void saveRemovesStaleSessionKeyBeforeWritingNewIdentityBinding() {
    SessionContext existing = new SessionContext(1L, 10L, "20", 30L, 40L, "old-jwt");
    SessionContext replacement = new SessionContext(2L, 10L, "20", 30L, 40L, "new-jwt");
    when(valueOperations.get("sessionctx:10:identity:40:30:context")).thenReturn(existing);

    service.save(replacement);

    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(valueOperations).set("sessionctx:10:2:context", replacement, TTL);
    verify(valueOperations).set("sessionctx:10:identity:40:30:context", replacement, TTL);
  }

  @Test
  void savePreservesForeignTenantIndexesFromSessionAlias() {
    SessionContext incoming =
        new SessionContext(1L, 10L, "88", "new-login", 60L, "Current", 50L, "R-1", "new-jwt");
    SessionContext foreignTenant =
        new SessionContext(1L, 99L, "30", "other-login", 31L, "Foreign", 41L, "R-2", "old-jwt");
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(foreignTenant);

    service.save(incoming);

    verify(redisTemplate, never()).delete("sessionctx:99:1:context");
    verify(redisTemplate, never()).delete("sessionctx:99:identity:41:31:context");
    verify(redisTemplate, never()).delete("sessionctx:99:identity:41:name:foreign:context");
    verify(redisTemplate, never()).delete("sessionctx:session:1:context");
    verify(redisTemplate, never())
        .watch(
            Mockito.argThat((Collection<String> keys) -> keys.contains("sessionctx:99:1:context")));
    verify(valueOperations).set("sessionctx:10:1:context", incoming, TTL);
    verify(valueOperations).set("sessionctx:session:1:context", incoming, TTL);
  }

  @Test
  void savePreservesForeignSessionIndexesFromSessionAlias() {
    SessionContext incoming =
        new SessionContext(1L, 10L, "88", "new-login", 60L, "Current", 50L, "R-1", "new-jwt");
    SessionContext foreignSession =
        new SessionContext(2L, 10L, "30", "other-login", 31L, "Foreign", 41L, "R-2", "old-jwt");
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(foreignSession);

    service.save(incoming);

    verify(redisTemplate, never()).delete("sessionctx:10:2:context");
    verify(redisTemplate, never()).delete("sessionctx:10:identity:41:31:context");
    verify(redisTemplate, never()).delete("sessionctx:10:identity:41:name:foreign:context");
    verify(redisTemplate, never()).delete("sessionctx:session:2:context");
    verify(redisTemplate, never())
        .watch(
            Mockito.argThat((Collection<String> keys) -> keys.contains("sessionctx:10:2:context")));
    verify(valueOperations).set("sessionctx:10:1:context", incoming, TTL);
    verify(valueOperations).set("sessionctx:session:1:context", incoming, TTL);
  }

  @Test
  void saveCleansDistinctSessionAliasIndexesForSameTenantAndSession() {
    SessionContext incoming =
        new SessionContext(1L, 10L, "88", "new-login", 60L, "Current", 50L, "R-1", "new-jwt");
    SessionContext existingContext =
        new SessionContext(
            1L, 10L, "30", "context-login", 31L, "ContextOld", 41L, "R-2", "ctx-jwt");
    SessionContext existingSessionAlias =
        new SessionContext(1L, 10L, "31", "alias-login", 32L, "AliasOld", 42L, "R-3", "alias-jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(existingContext);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(existingSessionAlias);

    service.save(incoming);

    verify(redisTemplate).delete("sessionctx:10:identity:42:32:context");
    verify(redisTemplate).delete("sessionctx:10:identity:42:name:aliasold:context");
    verify(redisTemplate, Mockito.atLeastOnce())
        .watch(
            Mockito.argThat(
                (Collection<String> keys) ->
                    keys.contains("sessionctx:10:identity:42:32:context")));
    verify(valueOperations).set("sessionctx:10:1:context", incoming, TTL);
    verify(valueOperations).set("sessionctx:session:1:context", incoming, TTL);
  }

  @Test
  void findByTenantAndSessionIdReturnsPersistedContext() {
    SessionContext context = new SessionContext(1L, 10L, "20", 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByTenantAndSessionId(10L, 1L);

    assertEquals(Optional.of(context), result);
  }

  @Test
  void findByGameplayIdentityReturnsPersistedContext() {
    SessionContext context = new SessionContext(1L, 10L, "20", 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:identity:40:30:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByGameplayIdentity(10L, 40L, 30L);

    assertEquals(Optional.of(context), result);
  }

  @Test
  void unreadableRetainedContextFailsClosedAndDoesNotBlockFreshReauthentication() {
    String retainedKey = "sessionctx:10:1:context";
    when(valueOperations.get(retainedKey))
        .thenThrow(new SerializationException("old SessionContext record shape"));

    assertEquals(Optional.empty(), service.findByTenantAndSessionId(10L, 1L));
    assertThrows(
        SerializationException.class,
        () -> service.deleteBySessionId(10L, 1L),
        "unreadable retained state must stop cleanup before its evidence can be deleted");

    SessionContext reauthenticated =
        new SessionContext(2L, 10L, "77", "demo@example.com", 0L, null, 0L, null, "fresh-jwt");
    service.save(reauthenticated);

    verify(valueOperations).set("sessionctx:10:2:context", reauthenticated, TTL);
    verify(valueOperations).set("sessionctx:session:2:context", reauthenticated, TTL);
    verify(redisTemplate, never()).delete(retainedKey);
    verify(redisTemplate, never()).delete("sessionctx:1");
  }

  @Test
  void unreadableSessionAliasIsNotOverwrittenOrDeletedWhenTenantContextIsAbsent() {
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(null);
    when(valueOperations.get("sessionctx:session:1:context"))
        .thenThrow(new SerializationException("old session alias record shape"));

    assertThrows(SerializationException.class, () -> service.deleteBySessionId(10L, 1L));
    assertThrows(
        SerializationException.class,
        () -> service.save(new SessionContext(1L, 10L, "77", null, 0L, null, 0L, null, null)));

    verify(redisTemplate, never()).multi();
    verify(redisTemplate, never()).exec();
    verify(redisTemplate, never()).delete("sessionctx:session:1:context");
    verify(valueOperations, never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
  }

  @Test
  void deleteBySessionIdRemovesBothKeys() {
    SessionContext context = new SessionContext(1L, 10L, "20", 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(redisTemplate).delete("sessionctx:10:identity:40:30:context");
  }

  @Test
  void deleteBySessionIdPreservesDifferentTenantAliasIndexes() {
    SessionContext differentTenant = new SessionContext(1L, 99L, "30", 31L, 41L, "other-jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(null);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(differentTenant);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate, never()).delete("sessionctx:99:1:context");
    verify(redisTemplate, never()).delete("sessionctx:session:1:context");
    verify(redisTemplate, never()).delete("sessionctx:99:identity:41:31:context");
  }

  @Test
  void deleteBySessionIdPreservesDifferentSessionAliasIndexes() {
    SessionContext requested = new SessionContext(1L, 10L, "20", 30L, 40L, "requested-jwt");
    SessionContext differentSession = new SessionContext(2L, 10L, "21", 31L, 41L, "other-jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(requested);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(differentSession);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(redisTemplate).delete("sessionctx:10:identity:40:30:context");
    verify(redisTemplate, never()).delete("sessionctx:10:2:context");
    verify(redisTemplate, never()).delete("sessionctx:session:2:context");
    verify(redisTemplate, never()).delete("sessionctx:10:identity:41:31:context");
  }

  @Test
  void deleteBySessionIdCleansDistinctContextForSameTenantAndSession() {
    SessionContext requested = new SessionContext(1L, 10L, "20", 30L, 40L, "requested-jwt");
    SessionContext sameOwner = new SessionContext(1L, 10L, "20", 31L, 41L, "other-jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(requested);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(sameOwner);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate, times(2)).delete("sessionctx:10:1:context");
    verify(redisTemplate, times(2)).delete("sessionctx:session:1:context");
    verify(redisTemplate).delete("sessionctx:10:identity:40:30:context");
    verify(redisTemplate).delete("sessionctx:10:identity:41:31:context");
  }

  @Test
  void savePreservesLocaleTagInStoredContext() {
    SessionContext context =
        new SessionContext(1L, 10L, "20", null, 30L, null, 40L, "R-1", "jwt", "fr", 40L);

    service.save(context);

    verify(valueOperations).set("sessionctx:10:1:context", context, TTL);
    assertEquals("fr", context.localeTag());
  }
}
