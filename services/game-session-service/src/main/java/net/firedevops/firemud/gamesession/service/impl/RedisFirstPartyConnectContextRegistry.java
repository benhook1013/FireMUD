package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

@Service
public final class RedisFirstPartyConnectContextRegistry
    implements FirstPartyConnectContextRegistry {
  private static final Logger LOG =
      LoggerFactory.getLogger(RedisFirstPartyConnectContextRegistry.class);
  private static final String KEY_TEMPLATE = "sessionctx:first-party:%d:connect-context";

  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration ttl;
  private final FirstPartyConnectContextService contextService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and used internally only")
  public RedisFirstPartyConnectContextRegistry(
      RedisTemplate<String, Object> redisTemplate,
      FirstPartyConnectContextProperties properties,
      FirstPartyConnectContextService contextService) {
    this.redisTemplate = redisTemplate;
    this.contextService = contextService;
    this.ttl = Duration.ofMillis(Math.max(properties.getTtlMs(), 1L));
  }

  @Override
  public void register(long sessionId, FirstPartyConnectContext connectContext) {
    connectContext = contextService.requireRetained(connectContext);
    String key = key(sessionId);
    try {
      Object retained = valueOperations().get(key);
      if (retained instanceof FirstPartyConnectContext previous) {
        FirstPartyConnectContext validated = contextService.requireRetained(previous);
        if (!validated.equals(connectContext)) {
          throw new IllegalStateException("Retained first-party context cannot be replaced");
        }
      }
      if (retained != null && !(retained instanceof FirstPartyConnectContext)) {
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
    try {
      Object retained = valueOperations().get(key(sessionId));
      if (retained == null) {
        return Optional.empty();
      }
      if (!(retained instanceof FirstPartyConnectContext context)) {
        throw new IllegalStateException("Retained first-party context is incompatible");
      }
      return Optional.of(contextService.requireRetained(context));
    } catch (SerializationException | ClassCastException ex) {
      throw new IllegalStateException("Retained first-party context is unreadable", ex);
    }
  }

  @Override
  public FirstPartyConnectContext requireRetained(FirstPartyConnectContext context) {
    return contextService.requireRetained(context);
  }

  @Override
  public void unregister(long sessionId) {
    String key = key(sessionId);
    try {
      if (valueOperations().get(key) instanceof FirstPartyConnectContext context) {
        // Delete only a verified, complete retained handoff; incompatible evidence is retained.
        contextService.requireRetained(context);
        redisTemplate.delete(key);
      }
    } catch (SerializationException | ClassCastException | IllegalStateException ex) {
      // Retain unreadable, incomplete or unverifiable evidence instead of treating it as an absent
      // context.
      LOG.warn(
          "Retained first-party connect context kept for session {}: {}",
          sessionId,
          ex.getClass().getSimpleName());
    }
  }

  private ValueOperations<String, Object> valueOperations() {
    return redisTemplate.opsForValue();
  }

  private String key(long sessionId) {
    return String.format(KEY_TEMPLATE, sessionId);
  }
}
