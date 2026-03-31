package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
    valueOperations().set(key(sessionId), connectContext, ttl);
  }

  @Override
  public Optional<FirstPartyConnectContext> find(long sessionId) {
    return Optional.ofNullable((FirstPartyConnectContext) valueOperations().get(key(sessionId)));
  }

  @Override
  public void unregister(long sessionId) {
    redisTemplate.delete(key(sessionId));
  }

  private ValueOperations<String, Object> valueOperations() {
    return redisTemplate.opsForValue();
  }

  private String key(long sessionId) {
    return String.format(KEY_TEMPLATE, sessionId);
  }
}
