package net.firedevops.firemud.common.redis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Shared Lua-backed Redis primitives for atomic counters and reservations. */
public final class RedisAtomicOperations {
  private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT =
      script(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
          end
          return count
          """);

  private static final RedisScript<Long> RESERVED_COUNTER_SCRIPT =
      script(
          """
          local current = redis.call('GET', KEYS[1])
          if current then
            current = tonumber(current)
          else
            current = 0
          end

          local limit = tonumber(ARGV[1])
          local ttlMs = tonumber(ARGV[2])
          local reservationValue = ARGV[3]

          if current >= limit then
            return -1
          end

          current = redis.call('INCR', KEYS[1])
          if current == 1 then
            redis.call('PEXPIRE', KEYS[1], ttlMs)
          end

          redis.call('SET', KEYS[2], reservationValue, 'PX', ttlMs)
          return current
          """);

  private RedisAtomicOperations() {}

  public static Long incrementWithTtl(
      RedisTemplate<String, ?> redisTemplate, String key, Duration ttl) {
    Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(ttl, "ttl must not be null");
    return redisTemplate.execute(
        INCREMENT_WITH_TTL_SCRIPT, List.of(key), Long.toString(Math.max(1L, ttl.toMillis())));
  }

  public static boolean reserveBoundedCounter(
      RedisTemplate<String, ?> redisTemplate,
      String counterKey,
      String reservationKey,
      long limit,
      Duration ttl,
      String reservationValue) {
    Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    Objects.requireNonNull(counterKey, "counterKey must not be null");
    Objects.requireNonNull(reservationKey, "reservationKey must not be null");
    Objects.requireNonNull(ttl, "ttl must not be null");
    Objects.requireNonNull(reservationValue, "reservationValue must not be null");
    Long result =
        redisTemplate.execute(
            RESERVED_COUNTER_SCRIPT,
            List.of(counterKey, reservationKey),
            Long.toString(Math.max(0L, limit)),
            Long.toString(Math.max(1L, ttl.toMillis())),
            reservationValue);
    return result != null && result > 0;
  }

  private static RedisScript<Long> script(String script) {
    DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
    redisScript.setScriptText(script);
    redisScript.setResultType(Long.class);
    return redisScript;
  }
}
