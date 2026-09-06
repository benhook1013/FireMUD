package net.firedevops.firemud.gamesession.service.impl;

import java.util.List;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Shared Redis serialization and raw-inspection helpers for script integration tests. */
final class RedisScriptTestSupport {
  private static final String TICK_LOCK_PREFIX = "gamesession:tick:lock:";

  private final RedisTemplate<String, Object> redisTemplate;

  RedisScriptTestSupport(RedisTemplate<String, Object> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  static RedisSerializer<Object> productionScriptKeySerializer(
      RedisSerializer<?> queueKeySerializer) {
    return new ProductionScriptKeySerializer(queueKeySerializer);
  }

  List<String> values(String key) {
    List<Object> values = redisTemplate.opsForList().range(key, 0, -1);
    return values == null ? List.of() : values.stream().map(Object::toString).toList();
  }

  List<byte[]> rawValues(String key) {
    List<byte[]> values =
        redisTemplate.execute(
            (RedisCallback<List<byte[]>>)
                connection -> connection.lRange(serializedKey(key), 0, -1));
    return values == null ? List.of() : values;
  }

  @SuppressWarnings("unchecked")
  byte[] serializedKey(String key) {
    RedisSerializer<Object> serializer = (RedisSerializer<Object>) redisTemplate.getKeySerializer();
    if (serializer == null) {
      throw new IllegalStateException("Redis key serializer is not configured");
    }
    byte[] serialized = serializer.serialize(key);
    if (serialized == null) {
      throw new IllegalStateException("Redis key serializer returned null");
    }
    return serialized;
  }

  @SuppressWarnings("unchecked")
  byte[] serializeValue(Object value) {
    RedisSerializer<Object> serializer =
        (RedisSerializer<Object>) redisTemplate.getValueSerializer();
    if (serializer == null) {
      throw new IllegalStateException("Redis value serializer is not configured");
    }
    byte[] serialized = serializer.serialize(value);
    if (serialized == null) {
      throw new IllegalStateException("Redis value serializer returned null");
    }
    return serialized;
  }

  private static final class ProductionScriptKeySerializer implements RedisSerializer<Object> {
    private final RedisSerializer<Object> queueKeySerializer;

    @SuppressWarnings("unchecked")
    private ProductionScriptKeySerializer(RedisSerializer<?> queueKeySerializer) {
      this.queueKeySerializer = (RedisSerializer<Object>) queueKeySerializer;
    }

    @Override
    public byte[] serialize(Object value) {
      if (value instanceof String key && key.startsWith(TICK_LOCK_PREFIX)) {
        return new StringRedisSerializer().serialize((String) value);
      }
      return queueKeySerializer.serialize(value);
    }

    @Override
    public Object deserialize(byte[] bytes) {
      return queueKeySerializer.deserialize(bytes);
    }
  }
}
