package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyResult;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyStatus;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class RedisMovementEffectIdempotencyServiceTest {
  private static final Duration TTL = Duration.ofMillis(1000L);

  private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
  private final ValueOperations<String, Object> valueOperations =
      Mockito.mock(ValueOperations.class);
  private RedisMovementEffectIdempotencyService service;

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
    service = new RedisMovementEffectIdempotencyService(redisTemplate, TTL.toMillis());
  }

  @Test
  void applyPreservesRoutingBundleWhenUpdatingRoom() {
    SessionContext current =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "demo",
            1L,
            "R-1021",
            "jwt-token",
            "en-NZ",
            1L,
            "demo",
            "production",
            7L,
            "SHARED");
    when(valueOperations.get("sessionctx:22:41:context")).thenReturn(current);
    when(valueOperations.get("sessionctx:22:41:movement-effect:tfx-1")).thenReturn(null);

    MoveEffectApplyResult result = service.apply("tfx-1", current, "R-2045");

    assertEquals(MoveEffectApplyStatus.APPLIED, result.status());
    SessionContext updated = result.context();
    assertNotNull(updated);
    assertEquals("R-2045", updated.roomInstanceId());
    assertEquals("demo", updated.worldSlug());
    assertEquals("production", updated.realmSlug());
    assertEquals(7L, updated.pointerVersion());
    assertEquals("SHARED", updated.playableStateScope());
    verify(valueOperations).set("sessionctx:22:41:context", updated, TTL);
    verify(valueOperations).set("sessionctx:session:41:context", updated, TTL);
    verify(valueOperations).set("sessionctx:22:identity:1:7001:context", updated, TTL);
    verify(valueOperations).set("sessionctx:22:identity:1:name:demo:context", updated, TTL);
    verify(valueOperations).set("sessionctx:22:41:movement-effect:tfx-1", updated, TTL);
  }

  @Test
  void applyReturnsConflictWhenGameplayIdentityMismatch() {
    SessionContext current = baselineContext();
    when(valueOperations.get("sessionctx:22:41:context")).thenReturn(current);
    when(valueOperations.get("sessionctx:22:41:movement-effect:tfx-1")).thenReturn(null);
    MoveEffectApplyResult result = service.apply("tfx-1", withAccountId(current, 999L), "R-2045");

    assertEquals(MoveEffectApplyStatus.CONFLICT, result.status());
    assertEquals(current, result.context());
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  void applyReturnsReplayedContextWhenReplayAlreadyStored() {
    SessionContext current = baselineContext();
    SessionContext replayed = withRoom(current, "R-2045");
    when(valueOperations.get("sessionctx:22:41:context")).thenReturn(current);
    when(valueOperations.get("sessionctx:22:41:movement-effect:tfx-1")).thenReturn(replayed);

    MoveEffectApplyResult result = service.apply("tfx-1", current, "R-2045");

    assertEquals(MoveEffectApplyStatus.REPLAYED, result.status());
    assertEquals(replayed, result.context());
    verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    verify(redisTemplate, never()).delete(anyString());
  }

  private SessionContext baselineContext() {
    return new SessionContext(
        41L,
        22L,
        123L,
        "demo@example.com",
        7001L,
        "demo",
        1L,
        "R-1021",
        "jwt-token",
        "en-NZ",
        1L,
        "demo",
        "production",
        7L,
        "SHARED");
  }

  private SessionContext withAccountId(SessionContext context, long accountId) {
    return new SessionContext(
        context.sessionId(),
        context.tenantId(),
        accountId,
        context.loginName(),
        context.characterId(),
        context.characterName(),
        context.gameInstanceId(),
        context.roomInstanceId(),
        context.jwt(),
        context.localeTag(),
        context.bootstrapGameInstanceId(),
        context.worldSlug(),
        context.realmSlug(),
        context.pointerVersion(),
        context.playableStateScope(),
        context.connectScopeId(),
        context.connectRequestId());
  }

  private SessionContext withRoom(SessionContext context, String roomInstanceId) {
    return new SessionContext(
        context.sessionId(),
        context.tenantId(),
        context.accountId(),
        context.loginName(),
        context.characterId(),
        context.characterName(),
        context.gameInstanceId(),
        roomInstanceId,
        context.jwt(),
        context.localeTag(),
        context.bootstrapGameInstanceId(),
        context.worldSlug(),
        context.realmSlug(),
        context.pointerVersion(),
        context.playableStateScope(),
        context.connectScopeId(),
        context.connectRequestId());
  }
}
