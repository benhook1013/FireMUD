package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Persists session context metadata in Redis keys scoped by tenant/session. */
@Service
public final class RedisSessionContextService implements SessionContextService {
  private static final int MAX_SAVE_RETRIES = 8;
  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration sessionTtl;

  private static final String CONTEXT_KEY_TEMPLATE = "sessionctx:%d:%d:context";
  private static final String SESSION_KEY_TEMPLATE = "sessionctx:session:%d:context";
  private static final String IDENTITY_KEY_TEMPLATE = "sessionctx:%d:identity:%d:%d:context";
  private static final String NAME_KEY_TEMPLATE = "sessionctx:%d:identity:%d:name:%s:context";

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is a shared Spring bean used only internally")
  public RedisSessionContextService(
      RedisTemplate<String, Object> redisTemplate,
      @Value("${FIREMUD_AUTH_SESSION_EXPIRATION_MS:3600000}") long sessionExpirationMs) {
    this.redisTemplate = redisTemplate; // injection target is internal, no defensive copy needed
    this.sessionTtl = Duration.ofMillis(sessionExpirationMs);
  }

  @Override
  public void save(SessionContext context) {
    for (int attempt = 0; attempt < MAX_SAVE_RETRIES; attempt++) {
      Boolean committed =
          redisTemplate.execute(
              new SessionCallback<>() {
                @Override
                public Boolean execute(
                    org.springframework.data.redis.core.RedisOperations operations) {
                  LinkedHashSet<String> watchedKeys = new LinkedHashSet<>(watchKeys(context));
                  while (true) {
                    operations.watch(watchedKeys);
                    SessionContext existingContext =
                        readContext(
                            operations, contextKey(context.tenantId(), context.sessionId()));
                    SessionContext existingIdentityContext =
                        hasGameplayIdentity(context)
                            ? readContext(
                                operations,
                                identityKey(
                                    context.tenantId(),
                                    context.gameInstanceId(),
                                    context.characterId()))
                            : null;
                    SessionContext existingNameContext =
                        hasGameplayIdentity(context) && StringUtils.hasText(context.characterName())
                            ? readContext(
                                operations,
                                nameKey(
                                    context.tenantId(),
                                    context.gameInstanceId(),
                                    context.characterName()))
                            : null;
                    LinkedHashSet<String> additionalWatchKeys = new LinkedHashSet<>();
                    addWatchKeys(additionalWatchKeys, existingContext);
                    addWatchKeys(additionalWatchKeys, existingIdentityContext);
                    addWatchKeys(additionalWatchKeys, existingNameContext);
                    if (additionalWatchKeys.removeAll(watchedKeys)
                        && !additionalWatchKeys.isEmpty()) {
                      operations.unwatch();
                      watchedKeys.addAll(additionalWatchKeys);
                      continue;
                    }
                    operations.multi();
                    deleteIndexes(operations, existingContext);
                    deleteIndexes(operations, existingIdentityContext);
                    deleteIndexes(operations, existingNameContext);
                    writeContext(operations, context);
                    return operations.exec() != null;
                  }
                }
              });
      if (Boolean.TRUE.equals(committed)) {
        return;
      }
    }
    throw new IllegalStateException("Failed to save session context after concurrent retries");
  }

  @Override
  public Optional<SessionContext> findBySessionId(long sessionId) {
    return Optional.ofNullable(
        (SessionContext) redisTemplate.opsForValue().get(sessionKey(sessionId)));
  }

  @Override
  public Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId) {
    return Optional.ofNullable(
        (SessionContext) redisTemplate.opsForValue().get(contextKey(tenantId, sessionId)));
  }

  @Override
  public Optional<SessionContext> findByGameplayIdentity(
      long tenantId, long gameInstanceId, long characterId) {
    return Optional.ofNullable(
        (SessionContext)
            redisTemplate.opsForValue().get(identityKey(tenantId, gameInstanceId, characterId)));
  }

  @Override
  public Optional<SessionContext> findByGameplayName(
      long tenantId, long gameInstanceId, String characterName) {
    if (!StringUtils.hasText(characterName)) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        (SessionContext)
            redisTemplate.opsForValue().get(nameKey(tenantId, gameInstanceId, characterName)));
  }

  @Override
  public void deleteBySessionId(long tenantId, long sessionId) {
    for (int attempt = 0; attempt < MAX_SAVE_RETRIES; attempt++) {
      Boolean committed =
          redisTemplate.execute(
              new SessionCallback<>() {
                @Override
                public Boolean execute(
                    org.springframework.data.redis.core.RedisOperations operations) {
                  LinkedHashSet<String> watchedKeys =
                      new LinkedHashSet<>(
                          List.of(contextKey(tenantId, sessionId), sessionKey(sessionId)));
                  while (true) {
                    operations.watch(watchedKeys);
                    SessionContext existing =
                        readContext(operations, contextKey(tenantId, sessionId));
                    LinkedHashSet<String> additionalWatchKeys = new LinkedHashSet<>();
                    addWatchKeys(additionalWatchKeys, existing);
                    if (additionalWatchKeys.removeAll(watchedKeys)
                        && !additionalWatchKeys.isEmpty()) {
                      operations.unwatch();
                      watchedKeys.addAll(additionalWatchKeys);
                      continue;
                    }
                    operations.multi();
                    deleteIndexes(operations, existing);
                    return operations.exec() != null;
                  }
                }
              });
      if (Boolean.TRUE.equals(committed)) {
        return;
      }
    }
    throw new IllegalStateException("Failed to delete session context after concurrent retries");
  }

  private String contextKey(long tenantId, long sessionId) {
    return String.format(CONTEXT_KEY_TEMPLATE, tenantId, sessionId);
  }

  private String sessionKey(long sessionId) {
    return String.format(SESSION_KEY_TEMPLATE, sessionId);
  }

  private String identityKey(long tenantId, long gameInstanceId, long characterId) {
    return String.format(IDENTITY_KEY_TEMPLATE, tenantId, gameInstanceId, characterId);
  }

  private String nameKey(long tenantId, long gameInstanceId, String characterName) {
    return String.format(NAME_KEY_TEMPLATE, tenantId, gameInstanceId, normalizeName(characterName));
  }

  private boolean hasGameplayIdentity(SessionContext context) {
    return context.gameInstanceId() > 0 && context.characterId() > 0;
  }

  private String normalizeName(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private List<String> watchKeys(SessionContext context) {
    List<String> keys = new ArrayList<>();
    keys.add(contextKey(context.tenantId(), context.sessionId()));
    keys.add(sessionKey(context.sessionId()));
    if (hasGameplayIdentity(context)) {
      keys.add(identityKey(context.tenantId(), context.gameInstanceId(), context.characterId()));
      if (StringUtils.hasText(context.characterName())) {
        keys.add(nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()));
      }
    }
    return keys;
  }

  private SessionContext readContext(
      org.springframework.data.redis.core.RedisOperations<String, Object> operations, String key) {
    return (SessionContext) operations.opsForValue().get(key);
  }

  private void addWatchKeys(Set<String> watchKeys, SessionContext context) {
    if (context == null) {
      return;
    }
    watchKeys.add(contextKey(context.tenantId(), context.sessionId()));
    watchKeys.add(sessionKey(context.sessionId()));
    if (hasGameplayIdentity(context)) {
      watchKeys.add(
          identityKey(context.tenantId(), context.gameInstanceId(), context.characterId()));
      if (StringUtils.hasText(context.characterName())) {
        watchKeys.add(
            nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()));
      }
    }
  }

  private void deleteIndexes(
      org.springframework.data.redis.core.RedisOperations<String, Object> operations,
      SessionContext context) {
    if (context == null) {
      return;
    }
    operations.delete(contextKey(context.tenantId(), context.sessionId()));
    operations.delete(sessionKey(context.sessionId()));
    if (hasGameplayIdentity(context)) {
      operations.delete(
          identityKey(context.tenantId(), context.gameInstanceId(), context.characterId()));
      if (StringUtils.hasText(context.characterName())) {
        operations.delete(
            nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()));
      }
    }
  }

  private void writeContext(
      org.springframework.data.redis.core.RedisOperations<String, Object> operations,
      SessionContext context) {
    var ops = operations.opsForValue();
    ops.set(contextKey(context.tenantId(), context.sessionId()), context, sessionTtl);
    ops.set(sessionKey(context.sessionId()), context, sessionTtl);
    if (hasGameplayIdentity(context)) {
      ops.set(
          identityKey(context.tenantId(), context.gameInstanceId(), context.characterId()),
          context,
          sessionTtl);
      if (StringUtils.hasText(context.characterName())) {
        ops.set(
            nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()),
            context,
            sessionTtl);
      }
    }
  }
}
