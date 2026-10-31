package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

@SuppressWarnings("unchecked")
class RedisSessionContextServiceTest {
  private static final Duration TTL = Duration.ofMillis(1000L);
  private static final String ACCOUNT_UUID = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";
  private static final String OTHER_ACCOUNT_UUID = "2b23537d-c5cc-4fa8-8c0d-dc4c38c77a92";
  private static final String THIRD_ACCOUNT_UUID = "f0381e18-4931-4660-9bc4-4a4f73a7ea6d";

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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "7",
        "bad-account",
        "00000000-0000-0000-0000-000000000000",
        " b8d093f7-cb70-40ed-9fac-3c82d4bf28f1"
      })
  void invalidRetainedAccountIdentityCannotBeUsedReplacedOrDeleted(String invalidAccount) {
    SessionContext retained = new SessionContext(1L, 10L, invalidAccount, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(retained);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(retained);
    assertThrows(SessionContextUnavailableException.class, () -> service.findBySessionId(1L));
    assertThrows(
        SessionContextUnavailableException.class,
        () -> service.save(new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "new-jwt")));
    assertThrows(
        SessionContextUnavailableException.class, () -> service.deleteBySessionId(10L, 1L));
    verify(redisTemplate, never()).multi();
    verify(redisTemplate, never()).delete(Mockito.anyString());
    verify(valueOperations, never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
  }

  @Test
  void saveRejectsNumericAccountBeforeAnyRedisMutation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.save(new SessionContext(1L, 10L, "7", 30L, 40L, "jwt")));
    verify(redisTemplate, never()).execute(Mockito.any(SessionCallback.class));
  }

  @Test
  void saveStoresContextInSessionAndIdentityKeys() {
    SessionContext context = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "jwt");

    service.save(context);

    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    verify(valueOperations).set("sessionctx:10:1:context", context.withoutJwt(), TTL);
    verify(valueOperations).set("sessionctx:10:identity:40:30:context", context.withoutJwt(), TTL);
  }

  @Test
  void saveSerializesOnlyTheNoJwtProjectionAcrossEveryIndex() {
    var firstParty =
        new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture().context();
    SessionContext context =
        new SessionContext(
                1L,
                10L,
                ACCOUNT_UUID,
                "player@example.com",
                30L,
                "Player",
                40L,
                "R-1",
                "private-delegation-jwt-must-stay-process-local",
                "fr",
                40L,
                "demo",
                "production",
                7L,
                "SHARED",
                "scope",
                "request")
            .withFirstPartyConnectContext(firstParty);

    service.save(context);

    var captured = org.mockito.ArgumentCaptor.forClass(SessionContext.class);
    verify(valueOperations, times(4)).set(Mockito.anyString(), captured.capture(), Mockito.eq(TTL));
    for (SessionContext stored : captured.getAllValues()) {
      assertEquals(context.withoutJwt(), stored);
      assertNull(stored.jwt());
      assertFalse(SessionContextSerializationTestSupport.serialize(stored).contains(context.jwt()));
    }
    assertEquals("private-delegation-jwt-must-stay-process-local", context.jwt());
    assertSame(firstParty, context.withoutJwt().firstPartyConnectContext());
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "session", "identity", "name"})
  void legacyReadsScrubJwtWithoutMutatingOrRefreshingStoredEvidence(String lookup) {
    SessionContext original =
        new SessionContext(
            1L,
            10L,
            ACCOUNT_UUID,
            "player@example.com",
            30L,
            "Player",
            40L,
            "R-1",
            "retained-private-delegation-jwt",
            "fr",
            40L,
            "demo",
            "production",
            7L,
            "SHARED",
            "scope",
            "request");
    SessionContext legacy = SessionContextSerializationTestSupport.roundTrip(original);
    String key =
        switch (lookup) {
          case "tenant" -> "sessionctx:10:1:context";
          case "session" -> "sessionctx:session:1:context";
          case "identity" -> "sessionctx:10:identity:40:30:context";
          case "name" -> "sessionctx:10:identity:40:name:player:context";
          default -> throw new IllegalArgumentException(lookup);
        };
    when(valueOperations.get(key)).thenReturn(legacy);

    Optional<SessionContext> result =
        switch (lookup) {
          case "tenant" -> service.findByTenantAndSessionId(10L, 1L);
          case "session" -> service.findBySessionId(1L);
          case "identity" -> service.findByGameplayIdentity(10L, 40L, 30L);
          case "name" -> service.findByGameplayName(10L, 40L, "Player");
          default -> throw new IllegalArgumentException(lookup);
        };

    assertEquals(Optional.of(original.withoutJwt()), result);
    assertEquals(original, legacy);
    assertTrue(SessionContextSerializationTestSupport.serialize(legacy).contains(original.jwt()));
    verify(valueOperations).get(key);
    Mockito.verifyNoMoreInteractions(valueOperations);
    verify(redisTemplate, never()).execute(Mockito.any(SessionCallback.class));
    verify(redisTemplate, never()).delete(Mockito.anyString());
    verify(redisTemplate, never()).expire(Mockito.anyString(), Mockito.any(Duration.class));
  }

  @Test
  void saveRemovesStaleSessionKeyBeforeWritingNewIdentityBinding() {
    SessionContext existing = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "old-jwt");
    SessionContext replacement = new SessionContext(2L, 10L, ACCOUNT_UUID, 30L, 40L, "new-jwt");
    when(valueOperations.get("sessionctx:10:identity:40:30:context")).thenReturn(existing);

    service.save(replacement);

    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(valueOperations).set("sessionctx:10:2:context", replacement.withoutJwt(), TTL);
    verify(valueOperations)
        .set("sessionctx:10:identity:40:30:context", replacement.withoutJwt(), TTL);
  }

  @Test
  void savePreservesForeignTenantIndexesFromSessionAlias() {
    SessionContext incoming =
        new SessionContext(
            1L, 10L, ACCOUNT_UUID, "new-login", 60L, "Current", 50L, "R-1", "new-jwt");
    SessionContext foreignTenant =
        new SessionContext(
            1L, 99L, OTHER_ACCOUNT_UUID, "other-login", 31L, "Foreign", 41L, "R-2", "old-jwt");
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(foreignTenant);

    service.save(incoming);

    verify(redisTemplate, never()).delete("sessionctx:99:1:context");
    verify(redisTemplate, never()).delete("sessionctx:99:identity:41:31:context");
    verify(redisTemplate, never()).delete("sessionctx:99:identity:41:name:foreign:context");
    verify(redisTemplate, never()).delete("sessionctx:session:1:context");
    verify(redisTemplate, never())
        .watch(
            Mockito.argThat((Collection<String> keys) -> keys.contains("sessionctx:99:1:context")));
    verify(valueOperations).set("sessionctx:10:1:context", incoming.withoutJwt(), TTL);
    verify(valueOperations).set("sessionctx:session:1:context", incoming.withoutJwt(), TTL);
  }

  @Test
  void savePreservesForeignSessionIndexesFromSessionAlias() {
    SessionContext incoming =
        new SessionContext(
            1L, 10L, ACCOUNT_UUID, "new-login", 60L, "Current", 50L, "R-1", "new-jwt");
    SessionContext foreignSession =
        new SessionContext(
            2L, 10L, OTHER_ACCOUNT_UUID, "other-login", 31L, "Foreign", 41L, "R-2", "old-jwt");
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(foreignSession);

    service.save(incoming);

    verify(redisTemplate, never()).delete("sessionctx:10:2:context");
    verify(redisTemplate, never()).delete("sessionctx:10:identity:41:31:context");
    verify(redisTemplate, never()).delete("sessionctx:10:identity:41:name:foreign:context");
    verify(redisTemplate, never()).delete("sessionctx:session:2:context");
    verify(redisTemplate, never())
        .watch(
            Mockito.argThat((Collection<String> keys) -> keys.contains("sessionctx:10:2:context")));
    verify(valueOperations).set("sessionctx:10:1:context", incoming.withoutJwt(), TTL);
    verify(valueOperations).set("sessionctx:session:1:context", incoming.withoutJwt(), TTL);
  }

  @Test
  void saveCleansDistinctSessionAliasIndexesForSameTenantAndSession() {
    SessionContext incoming =
        new SessionContext(
            1L, 10L, ACCOUNT_UUID, "new-login", 60L, "Current", 50L, "R-1", "new-jwt");
    SessionContext existingContext =
        new SessionContext(
            1L, 10L, OTHER_ACCOUNT_UUID, "context-login", 31L, "ContextOld", 41L, "R-2", "ctx-jwt");
    SessionContext existingSessionAlias =
        new SessionContext(
            1L, 10L, THIRD_ACCOUNT_UUID, "alias-login", 32L, "AliasOld", 42L, "R-3", "alias-jwt");
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
    verify(valueOperations).set("sessionctx:10:1:context", incoming.withoutJwt(), TTL);
    verify(valueOperations).set("sessionctx:session:1:context", incoming.withoutJwt(), TTL);
  }

  @Test
  void findByTenantAndSessionIdReturnsPersistedContext() {
    SessionContext context = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByTenantAndSessionId(10L, 1L);

    assertEquals(Optional.of(context.withoutJwt()), result);
  }

  @Test
  void findByGameplayIdentityReturnsPersistedContext() {
    SessionContext context = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:identity:40:30:context")).thenReturn(context);

    Optional<SessionContext> result = service.findByGameplayIdentity(10L, 40L, 30L);

    assertEquals(Optional.of(context.withoutJwt()), result);
  }

  @Test
  void unreadableRetainedContextFailsClosedWithoutTreatingItAsAbsence() {
    String retainedKey = "sessionctx:10:1:context";
    when(valueOperations.get(retainedKey))
        .thenThrow(new SerializationException("old SessionContext record shape"));

    assertThrows(
        SessionContextUnavailableException.class, () -> service.findByTenantAndSessionId(10L, 1L));
    assertThrows(
        SerializationException.class,
        () -> service.deleteBySessionId(10L, 1L),
        "unreadable retained state must stop cleanup before its evidence can be deleted");

    SessionContext reauthenticated =
        new SessionContext(
            2L, 10L, ACCOUNT_UUID, "demo@example.com", 0L, null, 0L, null, "fresh-jwt");
    service.save(reauthenticated);

    verify(valueOperations).set("sessionctx:10:2:context", reauthenticated.withoutJwt(), TTL);
    verify(valueOperations).set("sessionctx:session:2:context", reauthenticated.withoutJwt(), TTL);
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
        () ->
            service.save(
                new SessionContext(1L, 10L, ACCOUNT_UUID, null, 0L, null, 0L, null, null)));

    verify(redisTemplate, never()).multi();
    verify(redisTemplate, never()).exec();
    verify(redisTemplate, never()).delete("sessionctx:session:1:context");
    verify(valueOperations, never())
        .set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
  }

  @Test
  void deleteBySessionIdRemovesBothKeys() {
    SessionContext context = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(context);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate).multi();
    verify(redisTemplate).exec();
    verify(redisTemplate).delete("sessionctx:10:1:context");
    verify(redisTemplate).delete("sessionctx:10:identity:40:30:context");
  }

  @Test
  void deleteBySessionIdPreservesDifferentTenantAliasIndexes() {
    SessionContext differentTenant =
        new SessionContext(1L, 99L, OTHER_ACCOUNT_UUID, 31L, 41L, "other-jwt");
    when(valueOperations.get("sessionctx:10:1:context")).thenReturn(null);
    when(valueOperations.get("sessionctx:session:1:context")).thenReturn(differentTenant);

    service.deleteBySessionId(10L, 1L);

    verify(redisTemplate, never()).delete("sessionctx:99:1:context");
    verify(redisTemplate, never()).delete("sessionctx:session:1:context");
    verify(redisTemplate, never()).delete("sessionctx:99:identity:41:31:context");
  }

  @Test
  void deleteBySessionIdPreservesDifferentSessionAliasIndexes() {
    SessionContext requested = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "requested-jwt");
    SessionContext differentSession =
        new SessionContext(2L, 10L, OTHER_ACCOUNT_UUID, 31L, 41L, "other-jwt");
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
    SessionContext requested = new SessionContext(1L, 10L, ACCOUNT_UUID, 30L, 40L, "requested-jwt");
    SessionContext sameOwner = new SessionContext(1L, 10L, ACCOUNT_UUID, 31L, 41L, "other-jwt");
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
        new SessionContext(1L, 10L, ACCOUNT_UUID, null, 30L, null, 40L, "R-1", "jwt", "fr", 40L);

    service.save(context);

    verify(valueOperations).set("sessionctx:10:1:context", context.withoutJwt(), TTL);
    assertEquals("fr", context.localeTag());
  }
}
