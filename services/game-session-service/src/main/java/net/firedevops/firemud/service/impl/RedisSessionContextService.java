package net.firedevops.firemud.service.impl;

import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.service.SessionContext;
import net.firedevops.firemud.service.SessionContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Persists session context metadata in Redis keys scoped by tenant/session. */
@Service
public final class RedisSessionContextService implements SessionContextService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration sessionTtl;

  private static final String CONTEXT_KEY_TEMPLATE = "session:%d:%d:context";

  public RedisSessionContextService(
      RedisTemplate<String, Object> redisTemplate,
      @Value("${FIREMUD_AUTH_SESSION_EXPIRATION_MS:3600000}") long sessionExpirationMs) {
    this.redisTemplate =
        redisTemplate; // injection target is internal, no defensive copy needed
    this.sessionTtl = Duration.ofMillis(sessionExpirationMs);
  }

  @Override
  public void save(SessionContext context) {
    redisTemplate
        .opsForValue()
        .set(contextKey(context.tenantId(), context.sessionId()), context, sessionTtl);
  }

  @Override
  public Optional<SessionContext> findBySessionId(long tenantId, long sessionId) {
    return Optional.ofNullable(
        (SessionContext) redisTemplate.opsForValue().get(contextKey(tenantId, sessionId)));
  }

  private String contextKey(long tenantId, long sessionId) {
    return String.format(CONTEXT_KEY_TEMPLATE, tenantId, sessionId);
  }
}
