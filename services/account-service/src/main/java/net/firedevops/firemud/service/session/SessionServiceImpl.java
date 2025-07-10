package net.firedevops.firemud.service.session;

import java.time.Duration;
import net.firedevops.firemud.config.AuthProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl implements SessionService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final AuthProperties authProperties;

  public SessionServiceImpl(
      RedisTemplate<String, Object> redisTemplate, AuthProperties authProperties) {
    this.redisTemplate = redisTemplate;
    this.authProperties = authProperties;
  }

  @Override
  public void storeSession(Long tenantId, Long accountId, String token) {
    String key = buildKey(tenantId, token);
    redisTemplate
        .opsForValue()
        .set(key, accountId, Duration.ofMillis(authProperties.getSessionExpirationMs()));
  }

  @Override
  public Long getAccountId(Long tenantId, String token) {
    Object value = redisTemplate.opsForValue().get(buildKey(tenantId, token));
    return value != null ? Long.valueOf(value.toString()) : null;
  }

  private String buildKey(Long tenantId, String token) {
    return "session:" + tenantId + ":" + token;
  }
}
