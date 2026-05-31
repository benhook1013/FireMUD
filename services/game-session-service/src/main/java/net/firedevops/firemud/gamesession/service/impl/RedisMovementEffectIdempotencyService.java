package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

@Service
public final class RedisMovementEffectIdempotencyService
    implements MovementEffectIdempotencyService {
  private static final int MAX_SAVE_RETRIES = 8;

  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration sessionTtl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is a shared Spring bean used only internally")
  public RedisMovementEffectIdempotencyService(
      RedisTemplate<String, Object> redisTemplate,
      @Value("${FIREMUD_AUTH_SESSION_EXPIRATION_MS:3600000}") long sessionExpirationMs) {
    this.redisTemplate = redisTemplate;
    this.sessionTtl = Duration.ofMillis(sessionExpirationMs);
  }

  @Override
  public MoveEffectApplyResult apply(
      String effectId, SessionContext expectedContext, String destinationRoomInstanceId) {
    for (int attempt = 0; attempt < MAX_SAVE_RETRIES; attempt++) {
      MoveEffectApplyResult result =
          redisTemplate.execute(
              new SessionCallback<>() {
                @Override
                public MoveEffectApplyResult execute(
                    org.springframework.data.redis.core.RedisOperations operations) {
                  String contextKey =
                      SessionContextRedisKeys.contextKey(
                          expectedContext.tenantId(), expectedContext.sessionId());
                  String sessionKey =
                      SessionContextRedisKeys.sessionKey(expectedContext.sessionId());
                  String effectKey =
                      SessionContextRedisKeys.movementEffectKey(
                          expectedContext.tenantId(), expectedContext.sessionId(), effectId);
                  LinkedHashSet<String> watchedKeys =
                      new LinkedHashSet<>(List.of(contextKey, sessionKey, effectKey));
                  while (true) {
                    operations.watch(watchedKeys);
                    SessionContext current = readContext(operations, contextKey);
                    if (current == null) {
                      operations.unwatch();
                      return new MoveEffectApplyResult(MoveEffectApplyStatus.NOT_FOUND, null);
                    }
                    SessionContext replayed = readContext(operations, effectKey);
                    if (replayed != null) {
                      operations.unwatch();
                      return new MoveEffectApplyResult(MoveEffectApplyStatus.REPLAYED, replayed);
                    }
                    LinkedHashSet<String> additionalWatchKeys = new LinkedHashSet<>();
                    addWatchKeys(additionalWatchKeys, current);
                    if (additionalWatchKeys.removeAll(watchedKeys)
                        && !additionalWatchKeys.isEmpty()) {
                      operations.unwatch();
                      watchedKeys.addAll(additionalWatchKeys);
                      continue;
                    }
                    if (!sameGameplaySession(current, expectedContext)
                        || !roomMatches(
                            current.roomInstanceId(), expectedContext.roomInstanceId())) {
                      operations.unwatch();
                      return new MoveEffectApplyResult(MoveEffectApplyStatus.CONFLICT, current);
                    }
                    SessionContext updated = updateRoom(current, destinationRoomInstanceId);
                    operations.multi();
                    deleteIndexes(operations, current);
                    writeContext(operations, updated);
                    operations.opsForValue().set(effectKey, updated, sessionTtl);
                    if (operations.exec() != null) {
                      return new MoveEffectApplyResult(MoveEffectApplyStatus.APPLIED, updated);
                    }
                  }
                }
              });
      if (result != null) {
        return result;
      }
    }
    throw new IllegalStateException("Failed to apply movement effect after concurrent retries");
  }

  private SessionContext updateRoom(SessionContext current, String destinationRoomInstanceId) {
    return new SessionContext(
        current.sessionId(),
        current.tenantId(),
        current.accountId(),
        current.loginName(),
        current.characterId(),
        current.characterName(),
        current.gameInstanceId(),
        destinationRoomInstanceId,
        current.jwt(),
        current.localeTag(),
        current.bootstrapGameInstanceId(),
        current.worldSlug(),
        current.realmSlug(),
        current.pointerVersion(),
        current.playableStateScope(),
        current.connectScopeId(),
        current.connectRequestId());
  }

  private boolean sameGameplaySession(SessionContext current, SessionContext expected) {
    return current.sessionId() == expected.sessionId()
        && current.tenantId() == expected.tenantId()
        && current.accountId() == expected.accountId()
        && current.characterId() == expected.characterId()
        && current.gameInstanceId() == expected.gameInstanceId();
  }

  private boolean roomMatches(String currentRoomId, String expectedRoomId) {
    return java.util.Objects.equals(normalizeRoom(currentRoomId), normalizeRoom(expectedRoomId));
  }

  private String normalizeRoom(String roomId) {
    return roomId == null || roomId.isBlank() ? null : roomId;
  }

  private SessionContext readContext(
      org.springframework.data.redis.core.RedisOperations<String, Object> operations, String key) {
    return (SessionContext) operations.opsForValue().get(key);
  }

  private void addWatchKeys(LinkedHashSet<String> watchKeys, SessionContext context) {
    if (context == null) {
      return;
    }
    watchKeys.add(SessionContextRedisKeys.contextKey(context.tenantId(), context.sessionId()));
    watchKeys.add(SessionContextRedisKeys.sessionKey(context.sessionId()));
    if (hasGameplayIdentity(context)) {
      watchKeys.add(
          SessionContextRedisKeys.identityKey(
              context.tenantId(), context.gameInstanceId(), context.characterId()));
      if (hasGameplayName(context)) {
        watchKeys.add(
            SessionContextRedisKeys.nameKey(
                context.tenantId(), context.gameInstanceId(), context.characterName()));
      }
    }
  }

  private void deleteIndexes(
      org.springframework.data.redis.core.RedisOperations<String, Object> operations,
      SessionContext context) {
    if (context == null) {
      return;
    }
    operations.delete(SessionContextRedisKeys.contextKey(context.tenantId(), context.sessionId()));
    operations.delete(SessionContextRedisKeys.sessionKey(context.sessionId()));
    if (hasGameplayIdentity(context)) {
      operations.delete(
          SessionContextRedisKeys.identityKey(
              context.tenantId(), context.gameInstanceId(), context.characterId()));
      if (hasGameplayName(context)) {
        operations.delete(
            SessionContextRedisKeys.nameKey(
                context.tenantId(), context.gameInstanceId(), context.characterName()));
      }
    }
  }

  private void writeContext(
      org.springframework.data.redis.core.RedisOperations<String, Object> operations,
      SessionContext context) {
    var ops = operations.opsForValue();
    ops.set(
        SessionContextRedisKeys.contextKey(context.tenantId(), context.sessionId()),
        context,
        sessionTtl);
    ops.set(SessionContextRedisKeys.sessionKey(context.sessionId()), context, sessionTtl);
    if (hasGameplayIdentity(context)) {
      ops.set(
          SessionContextRedisKeys.identityKey(
              context.tenantId(), context.gameInstanceId(), context.characterId()),
          context,
          sessionTtl);
      if (hasGameplayName(context)) {
        ops.set(
            SessionContextRedisKeys.nameKey(
                context.tenantId(), context.gameInstanceId(), context.characterName()),
            context,
            sessionTtl);
      }
    }
  }

  private boolean hasGameplayIdentity(SessionContext context) {
    return context.gameInstanceId() > 0 && context.characterId() > 0;
  }

  private boolean hasGameplayName(SessionContext context) {
    return context.characterName() != null && !context.characterName().isBlank();
  }
}
