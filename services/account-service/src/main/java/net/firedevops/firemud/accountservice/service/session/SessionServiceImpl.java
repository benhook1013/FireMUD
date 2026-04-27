package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl implements SessionService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final AccountTokenProperties tokenProperties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and managed by Spring")
  public SessionServiceImpl(
      RedisTemplate<String, Object> redisTemplate, AccountTokenProperties tokenProperties) {
    this.redisTemplate = redisTemplate;
    this.tokenProperties = tokenProperties;
  }

  @Override
  @Timed(value = "session.store")
  public void storeSession(Long tenantId, Long accountId, String token) {
    storeSession(tenantId, accountId, token, tokenProperties.getSessionExpirationMs());
  }

  @Override
  @Timed(value = "session.store_ttl")
  public void storeSession(Long tenantId, Long accountId, String token, long expirationMs) {
    Duration ttl = Duration.ofMillis(expirationMs);
    redisTemplate.opsForValue().set(accountKey(accountId, token), accountId, ttl);
    redisTemplate.opsForValue().set(tenantKey(tenantId, token), accountId, ttl);
  }

  @Override
  @Timed(value = "session.get")
  public Long getAccountId(Long tenantId, String token) {
    Object value = redisTemplate.opsForValue().get(tenantKey(tenantId, token));
    return value != null ? Long.valueOf(value.toString()) : null;
  }

  private String accountKey(Long accountId, String token) {
    return "session:auth:account:" + accountId + ":" + tokenHash(token);
  }

  private String tenantKey(Long tenantId, String token) {
    return "session:auth:tenant:" + tenantId + ":" + tokenHash(token);
  }

  private String tokenHash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 digest unavailable", ex);
    }
  }
}
