package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Duration;
import net.firedevops.firemud.accountservice.config.AuthProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl implements SessionService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final AuthProperties authProperties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and managed by Spring")
  public SessionServiceImpl(
      RedisTemplate<String, Object> redisTemplate, AuthProperties authProperties) {
    this.redisTemplate = redisTemplate;
    this.authProperties = authProperties;
  }

  @Override
  @Timed(value = "session.store")
  public void storeSession(Long tenantId, Long accountId, String token) {
    storeSession(tenantId, accountId, token, authProperties.getSessionExpirationMs());
  }

  @Override
  @Timed(value = "session.store_ttl")
  public void storeSession(Long tenantId, Long accountId, String token, long expirationMs) {
    String key = buildKey(tenantId, token);
    redisTemplate.opsForValue().set(key, accountId, Duration.ofMillis(expirationMs));
  }

  @Override
  @Timed(value = "session.get")
  public Long getAccountId(Long tenantId, String token) {
    Object value = redisTemplate.opsForValue().get(buildKey(tenantId, token));
    return value != null ? Long.valueOf(value.toString()) : null;
  }

  private String buildKey(Long tenantId, String token) {
    return "session:" + tenantId + ":" + token;
  }
}
