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
    String key = key(sessionId);
    try {
      Object retained = valueOperations().get(key);
      if (retained != null && !(retained instanceof FirstPartyConnectContext)) {
        return;
      }
    } catch (SerializationException | ClassCastException ex) {
      return;
    }
    valueOperations().set(key, connectContext, ttl);
  }

  @Override
  public Optional<FirstPartyConnectContext> find(long sessionId) {
    try {
      return Optional.ofNullable((FirstPartyConnectContext) valueOperations().get(key(sessionId)));
    } catch (SerializationException | ClassCastException ex) {
      return Optional.empty();
    }
  }

  @Override
  public void unregister(long sessionId) {
    String key = key(sessionId);
    try {
      if (valueOperations().get(key) instanceof FirstPartyConnectContext) {
        redisTemplate.delete(key);
      }
    } catch (SerializationException | ClassCastException ex) {
      // Retain unreadable evidence instead of treating it as an absent context.
    }
  }

  private ValueOperations<String, Object> valueOperations() {
    return redisTemplate.opsForValue();
  }

  private String key(long sessionId) {
    return String.format(KEY_TEMPLATE, sessionId);
  }
}
