package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import net.firedevops.firemud.accountservice.config.PlatformAuthRateLimitProperties;
import net.firedevops.firemud.accountservice.service.CredentialAttemptSource;
import net.firedevops.firemud.accountservice.service.PlatformAuthBucketStore;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.config.RedisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AccountPlatformAuthAbuseLimiterTest {
  @Test
  void annotationContextWiresProductionConstructor() {
    PlatformAuthBucketStore bucketStore = mock(PlatformAuthBucketStore.class);
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(PlatformAuthRateLimitProperties.class, () -> properties());
      context.registerBean(PlatformAuthBucketStore.class, () -> bucketStore);
      context.register(AccountPlatformAuthAbuseLimiter.class);

      context.refresh();

      assertNotNull(context.getBean(AccountPlatformAuthAbuseLimiter.class));
    }
  }

  @Test
  void cacheCommandTimeoutDefaultAndBoundsAreExplicit() {
    assertEquals(
        1_000, new PlatformAuthRateLimitProperties().getCacheRedis().getCommandTimeoutMillis());

    for (int invalidTimeout : new int[] {0, 5_001}) {
      PlatformAuthRateLimitProperties.CacheRedis cache =
          new PlatformAuthRateLimitProperties.CacheRedis();
      cache.setCommandTimeoutMillis(invalidTimeout);
      IllegalStateException invalid =
          assertThrows(
              IllegalStateException.class,
              () -> new PlatformAuthCacheRedisClient(cache, new RedisProperties()));
      assertEquals(
          "Account platform-auth Cache Redis command timeout is invalid", invalid.getMessage());
    }
  }

  @Test
  void sourceBudgetIsSharedAcrossConnectionModes() {
    PlatformAuthRateLimitProperties properties = properties();
    properties.setSourceAttemptsPerWindow(1);
    AccountPlatformAuthAbuseLimiter limiter =
        new AccountPlatformAuthAbuseLimiter(
            properties,
            new InMemoryBuckets(),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));

    limiter.begin(source("198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB));
    AuthenticationException rejected =
        assertThrows(
            AuthenticationException.class,
            () ->
                limiter.begin(
                    source(
                        "198.51.100.7", CredentialAttemptSource.ConnectionMode.TRUSTED_TCP_PROXY)));

    assertEquals("AUTH_RETRY_LATER", rejected.getCode());
    assertTrue(rejected.getRetryAfterSeconds() >= 1);
  }

  @Test
  void sharedCacheEnforcesOneSourceBudgetAcrossLimiterReplicas() {
    PlatformAuthRateLimitProperties firstProperties = properties();
    firstProperties.setSourceAttemptsPerWindow(1);
    PlatformAuthRateLimitProperties secondProperties = properties();
    secondProperties.setSourceAttemptsPerWindow(1);
    InMemoryBuckets sharedCache = new InMemoryBuckets();
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
    AccountPlatformAuthAbuseLimiter firstReplica =
        new AccountPlatformAuthAbuseLimiter(firstProperties, sharedCache, clock);
    AccountPlatformAuthAbuseLimiter secondReplica =
        new AccountPlatformAuthAbuseLimiter(secondProperties, sharedCache, clock);

    firstReplica.begin(
        source("198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB));
    AuthenticationException rejected =
        assertThrows(
            AuthenticationException.class,
            () ->
                secondReplica.begin(
                    source(
                        "198.51.100.7", CredentialAttemptSource.ConnectionMode.TRUSTED_TCP_PROXY)));

    assertEquals("AUTH_RETRY_LATER", rejected.getCode());
  }

  @Test
  void candidateFailureBudgetIsSharedAcrossSourceAddresses() {
    PlatformAuthRateLimitProperties properties = properties();
    properties.setCandidateFailuresPerWindow(1);
    AccountPlatformAuthAbuseLimiter limiter =
        new AccountPlatformAuthAbuseLimiter(
            properties,
            new InMemoryBuckets(),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));
    byte[] candidate =
        AccountPlatformAuthAbuseLimiter.candidateIdentity("normalized-alias@example.test");

    AccountPlatformAuthAbuseLimiter.AttemptPermit first =
        limiter.begin(
            source("198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB));
    limiter.admitCandidate(first, candidate);
    limiter.recordFailure(first, candidate);

    AccountPlatformAuthAbuseLimiter.AttemptPermit second =
        limiter.begin(
            source("198.51.100.8", CredentialAttemptSource.ConnectionMode.TRUSTED_TCP_PROXY));
    AuthenticationException rejected =
        assertThrows(
            AuthenticationException.class, () -> limiter.admitCandidate(second, candidate));

    assertEquals("AUTH_RETRY_LATER", rejected.getCode());
    assertTrue(rejected.getRetryAfterSeconds() >= 1);
  }

  @Test
  void sharedStoreFailureFailsClosedWithUnavailableCode() {
    PlatformAuthRateLimitProperties properties = properties();
    PlatformAuthBucketStore unavailableStore =
        new PlatformAuthBucketStore() {
          @Override
          public long increment(String key, String fingerprint, Duration ttl, long maximumValue) {
            throw new IllegalStateException("store unavailable");
          }

          @Override
          public long count(String key, String fingerprint) {
            throw new IllegalStateException("store unavailable");
          }
        };
    AccountPlatformAuthAbuseLimiter limiter =
        new AccountPlatformAuthAbuseLimiter(
            properties,
            unavailableStore,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                limiter.begin(
                    source(
                        "198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB)));

    assertEquals("AUTH_ABUSE_CONTROL_UNAVAILABLE", unavailable.getCode());
    assertEquals(0, unavailable.getRetryAfterSeconds());
  }

  @Test
  void globalPressureBoundsSubjectBucketAdmissionAndKeysContainNoRawSource() {
    PlatformAuthRateLimitProperties properties = properties();
    properties.setGlobalAdmissionsPerWindow(1);
    InMemoryBuckets buckets = new InMemoryBuckets();
    AccountPlatformAuthAbuseLimiter limiter =
        new AccountPlatformAuthAbuseLimiter(
            properties,
            buckets,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));

    limiter.begin(source("198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB));
    AuthenticationException rejected =
        assertThrows(
            AuthenticationException.class,
            () ->
                limiter.begin(
                    source(
                        "198.51.100.8", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB)));

    assertEquals("AUTH_RETRY_LATER", rejected.getCode());
    assertEquals(2, buckets.size());
    assertTrue(buckets.keys().stream().noneMatch(key -> key.contains("198.51.100.")));
  }

  @Test
  void resetAndWindowExpiryOnlyLoseHeuristicThrottleState() {
    PlatformAuthRateLimitProperties properties = properties();
    properties.setSourceAttemptsPerWindow(1);
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    InMemoryBuckets sharedCache = new InMemoryBuckets(clock);
    AccountPlatformAuthAbuseLimiter limiter =
        new AccountPlatformAuthAbuseLimiter(properties, sharedCache, clock);
    CredentialAttemptSource source =
        source("198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);

    limiter.begin(source);
    assertThrows(AuthenticationException.class, () -> limiter.begin(source));

    sharedCache.reset();
    limiter.begin(source);
    assertThrows(AuthenticationException.class, () -> limiter.begin(source));

    clock.advance(Duration.ofSeconds(properties.getSubjectWindowSeconds()));
    limiter.begin(source);
  }

  @Test
  void missingActiveKeyFailsClosedBeforeCallingSharedStore() {
    PlatformAuthRateLimitProperties properties = new PlatformAuthRateLimitProperties();
    InMemoryBuckets buckets = new InMemoryBuckets();
    AccountPlatformAuthAbuseLimiter limiter =
        new AccountPlatformAuthAbuseLimiter(
            properties,
            buckets,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));

    AuthenticationException unavailable =
        assertThrows(
            AuthenticationException.class,
            () ->
                limiter.begin(
                    source(
                        "198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB)));

    assertEquals("AUTH_ABUSE_CONTROL_UNAVAILABLE", unavailable.getCode());
    assertEquals(0, buckets.size());
  }

  @Test
  void keyRotationCreatesIsolatedBucketNamespaces() {
    InMemoryBuckets buckets = new InMemoryBuckets();
    PlatformAuthRateLimitProperties firstProperties = properties();
    AccountPlatformAuthAbuseLimiter first =
        new AccountPlatformAuthAbuseLimiter(
            firstProperties,
            buckets,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));
    firstProperties.setSourceAttemptsPerWindow(1);
    first.begin(source("198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB));
    AuthenticationException oldKeyStillThrottles =
        assertThrows(
            AuthenticationException.class,
            () ->
                first.begin(
                    source(
                        "198.51.100.7", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB)));
    assertEquals("AUTH_RETRY_LATER", oldKeyStillThrottles.getCode());

    PlatformAuthRateLimitProperties rotatedProperties = properties();
    rotatedProperties.setHmacKeyId("rotated");
    rotatedProperties.setHmacKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
    rotatedProperties.setSourceAttemptsPerWindow(1);
    AccountPlatformAuthAbuseLimiter rotated =
        new AccountPlatformAuthAbuseLimiter(
            rotatedProperties,
            buckets,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));

    rotated.begin(source("198.51.100.7", CredentialAttemptSource.ConnectionMode.TRUSTED_TCP_PROXY));
  }

  private static PlatformAuthRateLimitProperties properties() {
    PlatformAuthRateLimitProperties properties = new PlatformAuthRateLimitProperties();
    properties.setHmacKeyId("test-v1");
    properties.setHmacKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
    return properties;
  }

  private static CredentialAttemptSource source(
      String ip, CredentialAttemptSource.ConnectionMode mode) {
    return new CredentialAttemptSource(ip, mode);
  }

  private static final class InMemoryBuckets implements PlatformAuthBucketStore {
    private final Map<String, Value> values = new HashMap<>();
    private final Clock clock;

    private InMemoryBuckets() {
      this(null);
    }

    private InMemoryBuckets(Clock clock) {
      this.clock = clock;
    }

    @Override
    public long increment(String key, String fingerprint, Duration ttl, long maximumValue) {
      expireOldValues();
      Value old = values.get(key);
      if (old != null && !old.fingerprint.equals(fingerprint)) {
        throw new IllegalStateException("collision");
      }
      long next = old == null ? 1 : Math.min(maximumValue, old.count + 1);
      Instant expiresAt =
          old == null && clock != null
              ? clock.instant().plus(ttl)
              : old == null ? null : old.expiresAt;
      values.put(key, new Value(fingerprint, next, expiresAt));
      return next;
    }

    @Override
    public long count(String key, String fingerprint) {
      expireOldValues();
      Value value = values.get(key);
      if (value == null) {
        return 0;
      }
      if (!value.fingerprint.equals(fingerprint)) {
        throw new IllegalStateException("collision");
      }
      return value.count;
    }

    int size() {
      expireOldValues();
      return values.size();
    }

    java.util.Set<String> keys() {
      expireOldValues();
      return values.keySet();
    }

    void reset() {
      values.clear();
    }

    private void expireOldValues() {
      if (clock == null) {
        return;
      }
      Instant now = clock.instant();
      values.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private record Value(String fingerprint, long count, Instant expiresAt) {}
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
