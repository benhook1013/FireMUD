package net.firedevops.firemud.gamesession.service.impl;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Shared construction for Redis scripts that must preserve queue and lease key encodings. */
final class FencedRedisScriptSupport {
  private FencedRedisScriptSupport() {}

  static RedisTemplate<String, Object> createTemplate(RedisTemplate<String, Object> redisTemplate) {
    if (redisTemplate.getConnectionFactory() == null || redisTemplate.getKeySerializer() == null) {
      return null;
    }
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(redisTemplate.getConnectionFactory());
    template.setKeySerializer(new FencedScriptKeySerializer(redisTemplate.getKeySerializer()));
    if (redisTemplate.getValueSerializer() != null) {
      template.setValueSerializer(redisTemplate.getValueSerializer());
    }
    template.afterPropertiesSet();
    return template;
  }

  private static final class FencedScriptKeySerializer implements RedisSerializer<Object> {
    private static final StringRedisSerializer RAW_STRING_SERIALIZER = new StringRedisSerializer();

    @SuppressWarnings("unchecked")
    private FencedScriptKeySerializer(RedisSerializer<?> queueKeySerializer) {
      this.queueKeySerializer = (RedisSerializer<Object>) queueKeySerializer;
    }

    private final RedisSerializer<Object> queueKeySerializer;

    @Override
    public byte[] serialize(Object value) {
      if (value instanceof String key
          && key.startsWith(TickQueueControlService.TICK_LOCK_KEY_PREFIX)) {
        return RAW_STRING_SERIALIZER.serialize(key);
      }
      return queueKeySerializer.serialize(value);
    }

    @Override
    public Object deserialize(byte[] bytes) {
      return queueKeySerializer.deserialize(bytes);
    }
  }
}
