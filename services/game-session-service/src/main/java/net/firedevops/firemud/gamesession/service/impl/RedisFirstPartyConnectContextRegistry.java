package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

@Service
public final class RedisFirstPartyConnectContextRegistry
    implements FirstPartyConnectContextRegistry {
  private static final String KEY_TEMPLATE = "sessionctx:first-party:%d:connect-context";

  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration ttl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and used internally only")
  public RedisFirstPartyConnectContextRegistry(
      RedisTemplate<String, Object> redisTemplate, FirstPartyConnectContextProperties properties) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMillis(Math.max(properties.getTtlMs(), 1L));
  }

  @Override
  public void register(long sessionId, FirstPartyConnectContext connectContext) {
    if (connectContext == null || !connectContext.hasCompleteRoutingScope()) {
      throw new IllegalArgumentException("first-party connect context must be complete");
    }
    String key = key(sessionId);
    try {
      Object retained = valueOperations().get(key);
      if (retained != null
          && (!(retained instanceof FirstPartyConnectContext retainedContext)
              || !retainedContext.hasCompleteRoutingScope())) {
        throw new IllegalStateException(
            "Retained first-party connect context is incompatible and cannot be replaced");
      }
    } catch (SerializationException | ClassCastException ex) {
      throw new IllegalStateException(
          "Retained first-party connect context is unreadable and cannot be replaced", ex);
    }
    valueOperations().set(key, connectContext, ttl);
  }

  @Override
  public Optional<FirstPartyConnectContext> find(long sessionId) {
    Object retained = valueOperations().get(key(sessionId));
    if (retained == null) {
      return Optional.empty();
    }
    if (!(retained instanceof FirstPartyConnectContext context)) {
      throw new ClassCastException(
          "Retained first-party connect context has an incompatible value type");
    }
    return Optional.of(context);
  }

  @Override
  public void unregister(long sessionId) {
    String key = key(sessionId);
    Object retained;
    try {
      retained = valueOperations().get(key);
    } catch (SerializationException | ClassCastException ex) {
      return;
    }
    if (retained instanceof FirstPartyConnectContext context && context.hasCompleteRoutingScope()) {
      redisTemplate.delete(key);
    }
  }

  private ValueOperations<String, Object> valueOperations() {
    return redisTemplate.opsForValue();
  }

  private String key(long sessionId) {
    return String.format(KEY_TEMPLATE, sessionId);
  }
}
