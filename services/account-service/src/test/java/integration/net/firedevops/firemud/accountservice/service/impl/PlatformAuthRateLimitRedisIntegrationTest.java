package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.config.PlatformAuthRateLimitProperties;
import net.firedevops.firemud.common.config.RedisProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Docker-gated proof of the Account bucket Lua contract against disposable Redis. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class PlatformAuthRateLimitRedisIntegrationTest {
  private static final String KEY = "ratelimit:platform-auth:v1:" + "a".repeat(64) + ":0";
  private static final String FINGERPRINT = "b".repeat(64);

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  private PlatformAuthCacheRedisClient cacheClient;
  private RedisPlatformAuthBucketStore buckets;

  @BeforeEach
  void createCacheRoleClient() {
    PlatformAuthRateLimitProperties.CacheRedis cacheProperties =
        new PlatformAuthRateLimitProperties.CacheRedis();
    cacheProperties.setHost(redis.getHost());
    cacheProperties.setPort(redis.getMappedPort(6379));
    RedisProperties coordinationProperties = new RedisProperties();
    coordinationProperties.setHost("coordination.invalid");
    coordinationProperties.setPort(redis.getMappedPort(6379));
    cacheClient = new PlatformAuthCacheRedisClient(cacheProperties, coordinationProperties);
    buckets = new RedisPlatformAuthBucketStore(cacheClient);
  }

  @AfterEach
  void removeFixtureKeysAndCloseClient() {
    if (cacheClient != null) {
      try {
        cacheClient.template().delete(KEY);
      } finally {
        cacheClient.close();
      }
    }
  }

  @Test
  void absentAndEvictedBucketsReadAsZeroAndCanStartFresh() {
    assertThat(buckets.count(KEY, FINGERPRINT)).isZero();
    assertThat(buckets.increment(KEY, FINGERPRINT, Duration.ofSeconds(30), 10)).isEqualTo(1);
    assertThat(cacheClient.template().getExpire(KEY, TimeUnit.MILLISECONDS)).isPositive();

    // Exact-key deletion models reset/eviction without broadening the fixture to FLUSHDB.
    assertThat(cacheClient.template().delete(KEY)).isTrue();
    assertThat(buckets.count(KEY, FINGERPRINT)).isZero();
    assertThat(buckets.increment(KEY, FINGERPRINT, Duration.ofSeconds(30), 10)).isEqualTo(1);
  }

  @Test
  void presentHashWithoutFingerprintIsCorruptionNotAnAbsentBucket() {
    cacheClient.template().opsForHash().put(KEY, "count", "1");

    assertThatThrownBy(() -> buckets.count(KEY, FINGERPRINT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("platform-auth rate-limit bucket integrity check failed");
  }

  @Test
  void existingBucketWithoutPositiveTtlFailsClosedAndIsNotRepaired() {
    cacheClient
        .template()
        .opsForHash()
        .putAll(KEY, Map.of("fingerprint", FINGERPRINT, "count", "1"));

    assertThatThrownBy(() -> buckets.count(KEY, FINGERPRINT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("platform-auth rate-limit bucket integrity check failed");
    assertThatThrownBy(() -> buckets.increment(KEY, FINGERPRINT, Duration.ofSeconds(30), 10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("platform-auth rate-limit bucket integrity check failed");
    assertThat(cacheClient.template().getExpire(KEY, TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }
}
