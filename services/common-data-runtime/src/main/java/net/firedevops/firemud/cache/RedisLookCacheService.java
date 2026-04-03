package net.firedevops.firemud.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Redis/ObjectMapper dependencies are shared framework singletons")
public class RedisLookCacheService implements LookCacheService {
  private static final Duration LOOK_CACHE_TTL = Duration.ofMinutes(10);
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public RedisLookCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  private static final String KEY_TEMPLATE = "lookcache:%d:%d";

  private static final class CachedPayload {
    public String roomId;
    public String renderedText;
    public String protocolText;
    public long cachedAtMs;
  }

  @Override
  public void cache(
      long tenantId, long sessionId, String roomId, String renderedText, String protocolText) {
    CachedPayload payload = new CachedPayload();
    payload.roomId = roomId;
    payload.renderedText = renderedText;
    payload.protocolText = protocolText;
    payload.cachedAtMs = System.currentTimeMillis();
    try {
      redisTemplate
          .opsForValue()
          .set(
              key(tenantId, sessionId),
              objectMapper.writeValueAsString(payload),
              LOOK_CACHE_TTL);
    } catch (JacksonException e) {
      throw new IllegalStateException("Failed to serialize LOOK cache payload", e);
    }
  }

  @Override
  public Optional<CachedLook> get(long tenantId, long sessionId) {
    String payload = redisTemplate.opsForValue().get(key(tenantId, sessionId));
    if (payload == null) {
      return Optional.empty();
    }
    try {
      CachedPayload cached = objectMapper.readValue(payload, CachedPayload.class);
      return Optional.of(
          new CachedLook(
              cached.roomId, cached.renderedText, cached.protocolText, cached.cachedAtMs));
    } catch (JacksonException e) {
      redisTemplate.delete(key(tenantId, sessionId));
      return Optional.empty();
    }
  }

  private String key(long tenantId, long sessionId) {
    return String.format(KEY_TEMPLATE, tenantId, sessionId);
  }
}
