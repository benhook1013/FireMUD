package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class TickRedisScriptTest {
  private static final String QUEUE_KEY = "gamesession:tick:queue:42:9001";
  private static final String PENDING_KEY = "gamesession:tick:pending:42:9001";
  private static final String COMMAND_INDEX_KEY = "gamesession:tick:command-index:42:9001";
  private static final String LEASE_KEY = "gamesession:tick:lock:42:9001";
  private static final String TICK_LOCK_PREFIX = "gamesession:tick:lock:";
  private static final String LEASE_TOKEN = "lease-token";
  private static final RedisScript<Long> RESTORE_PENDING_PROJECTION_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/restore_pending_projection.lua"), Long.class);
  private static final RedisScript<Long> REMOVE_PAYLOAD_IF_OWNED_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/tick_remove_payload_if_owned.lua"), Long.class);

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactory;
  private RedisTemplate<String, Object> redisTemplate;
  private StringRedisTemplate lockRedisTemplate;
  private RedisTemplate<String, Object> scriptRedisTemplate;

  @BeforeEach
  void setUpRedis() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();

    redisTemplate = new RedisTemplate<>();
    redisTemplate.setConnectionFactory(connectionFactory);
    redisTemplate.afterPropertiesSet();

    lockRedisTemplate = new StringRedisTemplate(connectionFactory);
    lockRedisTemplate.afterPropertiesSet();

    scriptRedisTemplate = new RedisTemplate<>();
    scriptRedisTemplate.setConnectionFactory(connectionFactory);
    scriptRedisTemplate.setKeySerializer(
        new ProductionScriptKeySerializer(redisTemplate.getKeySerializer()));
    scriptRedisTemplate.setValueSerializer(redisTemplate.getValueSerializer());
    scriptRedisTemplate.afterPropertiesSet();

    redisTemplate.delete(List.of(QUEUE_KEY, PENDING_KEY, COMMAND_INDEX_KEY));
    lockRedisTemplate.delete(LEASE_KEY);
    lockRedisTemplate.opsForValue().set(LEASE_KEY, LEASE_TOKEN);
  }

  @AfterEach
  void tearDownRedis() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void restoreReplacesMultipleSealedEntriesOnExactRetryWithoutDuplicatingOrReordering() {
    String first = "N|sealed-one|look";
    String second = "S|sealed-two|wave";
    pushRaw(PENDING_KEY, first.getBytes(StandardCharsets.UTF_8));
    pushRaw(PENDING_KEY, second.getBytes(StandardCharsets.UTF_8));
    putRawIndex(first, first.getBytes(StandardCharsets.UTF_8));
    putRawIndex(second, second.getBytes(StandardCharsets.UTF_8));

    assertThat(executeRestore(LEASE_TOKEN, "0", "2", payload(first), payload(second), "0", "0"))
        .isEqualTo(1L);
    assertThat(values(PENDING_KEY)).containsExactly(first, second);

    assertThat(executeRestore(LEASE_TOKEN, "0", "2", payload(first), payload(second), "0", "0"))
        .isEqualTo(1L);
    assertThat(values(PENDING_KEY)).containsExactly(first, second);
    assertThat(rawValues(PENDING_KEY)).hasSize(2);
  }

  @Test
  void removeDeletesIndexedRawEncodingFromBothTerminalListProjections() {
    String payload = "N|remove-id|look";
    byte[] callerEncoding = serializeValue(payload);
    byte[] indexedEncoding = payload.getBytes(StandardCharsets.UTF_8);

    pushRaw(QUEUE_KEY, callerEncoding);
    pushRaw(QUEUE_KEY, indexedEncoding);
    pushRaw(PENDING_KEY, indexedEncoding);
    pushRaw(PENDING_KEY, callerEncoding);
    putRawIndex(payload, indexedEncoding);

    assertThat(executeRemove(payload)).isEqualTo(1L);
    assertThat(rawValues(QUEUE_KEY)).isEmpty();
    assertThat(rawValues(PENDING_KEY)).isEmpty();
    assertThat(readRawIndex(payload)).isNull();
  }

  private long executeRestore(Object... arguments) {
    Long result =
        scriptRedisTemplate.execute(
            RESTORE_PENDING_PROJECTION_SCRIPT,
            TickBatchExecutionService.restorePendingProjectionScriptArgumentSerializer(
                redisTemplate.getValueSerializer()),
            new GenericToStringSerializer<>(Long.class),
            List.of(PENDING_KEY, QUEUE_KEY, COMMAND_INDEX_KEY, LEASE_KEY),
            arguments);
    return result == null ? Long.MIN_VALUE : result;
  }

  private long executeRemove(String payload) {
    Long result =
        scriptRedisTemplate.execute(
            REMOVE_PAYLOAD_IF_OWNED_SCRIPT,
            new QueuePayloadScriptArgumentSerializer(redisTemplate.getValueSerializer()),
            new GenericToStringSerializer<>(Long.class),
            List.of(QUEUE_KEY, PENDING_KEY, COMMAND_INDEX_KEY, LEASE_KEY),
            LEASE_TOKEN,
            new QueuePayloadArgument(payload));
    return result == null ? Long.MIN_VALUE : result;
  }

  private Object payload(String value) {
    return TickBatchExecutionService.restoreQueuePayloadArgument(value);
  }

  private void pushJdk(String key, String value) {
    redisTemplate.opsForList().rightPush(key, value);
  }

  private void pushRaw(String key, byte[] value) {
    redisTemplate.execute(
        (RedisCallback<Long>) connection -> connection.rPush(serializedKey(key), value));
  }

  private void putRawIndex(String payload, byte[] value) {
    String commandId = payload.substring(2, payload.indexOf('|', 2));
    redisTemplate.execute(
        (RedisCallback<Boolean>)
            connection ->
                connection.hSet(
                    serializedKey(COMMAND_INDEX_KEY),
                    commandId.getBytes(StandardCharsets.UTF_8),
                    value));
  }

  private byte[] readRawIndex(String payload) {
    String commandId = payload.substring(2, payload.indexOf('|', 2));
    return redisTemplate.execute(
        (RedisCallback<byte[]>)
            connection ->
                connection.hGet(
                    serializedKey(COMMAND_INDEX_KEY), commandId.getBytes(StandardCharsets.UTF_8)));
  }

  private List<String> values(String key) {
    List<Object> values = redisTemplate.opsForList().range(key, 0, -1);
    return values == null ? List.of() : values.stream().map(Object::toString).toList();
  }

  private List<byte[]> rawValues(String key) {
    List<byte[]> values =
        redisTemplate.execute(
            (RedisCallback<List<byte[]>>)
                connection -> connection.lRange(serializedKey(key), 0, -1));
    return values == null ? List.of() : values;
  }

  @SuppressWarnings("unchecked")
  private byte[] serializedKey(String key) {
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
  private byte[] serializeValue(Object value) {
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

  private record QueuePayloadArgument(String value) {}

  private static final class QueuePayloadScriptArgumentSerializer
      implements RedisSerializer<Object> {
    private static final StringRedisSerializer RAW_STRING_SERIALIZER = new StringRedisSerializer();

    @SuppressWarnings("unchecked")
    private QueuePayloadScriptArgumentSerializer(RedisSerializer<?> valueSerializer) {
      this.valueSerializer = (RedisSerializer<Object>) valueSerializer;
    }

    private final RedisSerializer<Object> valueSerializer;

    @Override
    public byte[] serialize(Object value) {
      if (value instanceof QueuePayloadArgument payload) {
        return valueSerializer.serialize(payload.value());
      }
      if (value instanceof String string) {
        return RAW_STRING_SERIALIZER.serialize(string);
      }
      throw new IllegalArgumentException(
          "Queue script arguments must be strings or explicit queue payloads");
    }

    @Override
    public Object deserialize(byte[] bytes) {
      return RAW_STRING_SERIALIZER.deserialize(bytes);
    }
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
