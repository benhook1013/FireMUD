package net.firedevops.firemud.accountservice.service.impl;

import java.time.Duration;
import java.util.List;
import net.firedevops.firemud.accountservice.service.PlatformAuthBucketStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** One-key atomic Redis mutations and reads for Account platform-auth buckets. */
public final class RedisPlatformAuthBucketStore implements PlatformAuthBucketStore {
  private static final RedisScript<Long> BUCKET_SCRIPT = bucketScript();
  private final PlatformAuthCacheRedisClient cacheRedisClient;

  public RedisPlatformAuthBucketStore(PlatformAuthCacheRedisClient cacheRedisClient) {
    this.cacheRedisClient = cacheRedisClient;
  }

  @Override
  public long increment(String key, String collisionFingerprint, Duration ttl, long maximumValue) {
    if (ttl == null || ttl.isZero() || ttl.isNegative() || maximumValue < 1) {
      throw new IllegalArgumentException("platform-auth bucket bounds are invalid");
    }
    return execute(
        key,
        "increment",
        collisionFingerprint,
        Long.toString(ttl.toMillis()),
        Long.toString(maximumValue));
  }

  @Override
  public long count(String key, String collisionFingerprint) {
    return execute(key, "read", collisionFingerprint, "0", "0");
  }

  private long execute(
      String key, String operation, String collisionFingerprint, String ttlMillis, String cap) {
    Long result =
        cacheRedisClient
            .template()
            .execute(BUCKET_SCRIPT, List.of(key), operation, collisionFingerprint, ttlMillis, cap);
    if (result == null || result < 0) {
      throw new IllegalStateException("platform-auth rate-limit bucket integrity check failed");
    }
    return result;
  }

  private static RedisScript<Long> bucketScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("redis/platform_auth_rate_limit_bucket.lua"));
    script.setResultType(Long.class);
    return script;
  }
}
