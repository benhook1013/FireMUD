package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Persists session context metadata in Redis keys scoped by tenant/session. */
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = true)
public final class RedisSessionContextService implements SessionContextService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration sessionTtl;

  private static final String CONTEXT_KEY_TEMPLATE = "sessionctx:%d:%d:context";
  private static final String IDENTITY_KEY_TEMPLATE = "sessionctx:%d:identity:%d:%d:context";

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
    var ops = redisTemplate.opsForValue();
    ops.set(contextKey(context.tenantId(), context.sessionId()), context, sessionTtl);
    ops.set(
        identityKey(context.tenantId(), context.accountId(), context.playerId()),
        context,
        sessionTtl);
  }

  @Override
  public Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId) {
    return Optional.ofNullable(
        (SessionContext) redisTemplate.opsForValue().get(contextKey(tenantId, sessionId)));
  }

  @Override
  public Optional<SessionContext> findByAccountAndPlayer(
      long tenantId, long accountId, long playerId) {
    return Optional.ofNullable(
        (SessionContext)
            redisTemplate.opsForValue().get(identityKey(tenantId, accountId, playerId)));
  }

  @Override
  public void deleteBySessionId(long tenantId, long sessionId) {
    Optional<SessionContext> existing = findByTenantAndSessionId(tenantId, sessionId);
    redisTemplate.delete(contextKey(tenantId, sessionId));
    existing.ifPresent(
        context ->
            redisTemplate.delete(
                identityKey(context.tenantId(), context.accountId(), context.playerId())));
  }

  private String contextKey(long tenantId, long sessionId) {
    return String.format(CONTEXT_KEY_TEMPLATE, tenantId, sessionId);
  }

  private String identityKey(long tenantId, long accountId, long playerId) {
    return String.format(IDENTITY_KEY_TEMPLATE, tenantId, accountId, playerId);
  }
}
