package net.firedevops.firemud.accountservice.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import net.firedevops.firemud.accountservice.config.PlatformAuthRateLimitProperties;
import net.firedevops.firemud.accountservice.service.CredentialAttemptSource;
import net.firedevops.firemud.accountservice.service.PlatformAuthBucketStore;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash.Digest;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash.HmacKey;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash.PlatformAuthPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Central, Account-owned graduated throttle for pre-tenant credential attempts. */
@Service
public final class AccountPlatformAuthAbuseLimiter {
  private static final String RETRY_LATER = "AUTH_RETRY_LATER";
  private static final String UNAVAILABLE = "AUTH_ABUSE_CONTROL_UNAVAILABLE";

  private final PlatformAuthRateLimitProperties properties;
  private final PlatformAuthBucketStore buckets;
  private final Clock clock;

  @Autowired
  public AccountPlatformAuthAbuseLimiter(
      PlatformAuthRateLimitProperties properties, PlatformAuthBucketStore buckets) {
    this(properties, buckets, Clock.systemUTC());
  }

  AccountPlatformAuthAbuseLimiter(
      PlatformAuthRateLimitProperties properties, PlatformAuthBucketStore buckets, Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.buckets = Objects.requireNonNull(buckets, "buckets must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** Accounts for one credential attempt before account lookup, hashing, OTP, or mutation work. */
  public AttemptPermit begin(CredentialAttemptSource source) {
    Objects.requireNonNull(source, "source must not be null");
    HmacKey key = activeKey();
    try {
      Window globalWindow = window(clock.instant(), properties.getGlobalWindowSeconds());
      Bucket global = bucket(key, PlatformAuthPolicy.GLOBAL_PRESSURE, new byte[0], globalWindow);
      long globalCount =
          buckets.increment(
              global.key(),
              global.digest().collisionFingerprint(),
              globalWindow.ttl(),
              properties.getMaxCounterValue());
      if (globalCount > properties.getGlobalAdmissionsPerWindow()) {
        throw retry(
            globalCount,
            properties.getGlobalAdmissionsPerWindow(),
            properties.getGlobalRetryBaseSeconds(),
            globalWindow);
      }

      byte[] sourceBytes = source.canonicalClientAddress();
      Window subjectWindow = window(clock.instant(), properties.getSubjectWindowSeconds());
      Bucket sourceAttempts =
          bucket(key, PlatformAuthPolicy.SOURCE_ATTEMPT, sourceBytes, subjectWindow);
      long attemptCount =
          buckets.increment(
              sourceAttempts.key(),
              sourceAttempts.digest().collisionFingerprint(),
              subjectWindow.ttl(),
              properties.getMaxCounterValue());
      enforce(
          attemptCount,
          properties.getSourceAttemptsPerWindow(),
          properties.getSourceRetryBaseSeconds(),
          subjectWindow);

      Bucket sourceFailures =
          bucket(key, PlatformAuthPolicy.SOURCE_FAILURE, sourceBytes, subjectWindow);
      long failureCount =
          buckets.count(sourceFailures.key(), sourceFailures.digest().collisionFingerprint());
      enforceFailureThreshold(
          failureCount,
          properties.getSourceFailuresPerWindow(),
          properties.getSourceRetryBaseSeconds(),
          subjectWindow);
      return new AttemptPermit(key, sourceBytes);
    } catch (AuthenticationException expected) {
      throw expected;
    } catch (RuntimeException unavailable) {
      throw unavailable(unavailable);
    }
  }

  /** Applies account-candidate admission to Account's normalized submitted selector. */
  public void admitCandidate(AttemptPermit permit, byte[] canonicalCandidateIdentity) {
    Objects.requireNonNull(permit, "permit must not be null");
    Objects.requireNonNull(canonicalCandidateIdentity, "candidate identity must not be null");
    try {
      Window candidateWindow = window(clock.instant(), properties.getSubjectWindowSeconds());
      Bucket candidateAttempts =
          bucket(
              permit.key,
              PlatformAuthPolicy.CANDIDATE_ATTEMPT,
              canonicalCandidateIdentity,
              candidateWindow);
      long attemptCount =
          buckets.increment(
              candidateAttempts.key(),
              candidateAttempts.digest().collisionFingerprint(),
              candidateWindow.ttl(),
              properties.getMaxCounterValue());
      enforce(
          attemptCount,
          properties.getCandidateAttemptsPerWindow(),
          properties.getCandidateRetryBaseSeconds(),
          candidateWindow);

      Bucket candidateFailures =
          bucket(
              permit.key,
              PlatformAuthPolicy.CANDIDATE_FAILURE,
              canonicalCandidateIdentity,
              candidateWindow);
      long failureCount =
          buckets.count(candidateFailures.key(), candidateFailures.digest().collisionFingerprint());
      enforceFailureThreshold(
          failureCount,
          properties.getCandidateFailuresPerWindow(),
          properties.getCandidateRetryBaseSeconds(),
          candidateWindow);
    } catch (AuthenticationException expected) {
      throw expected;
    } catch (RuntimeException unavailable) {
      throw unavailable(unavailable);
    }
  }

  /** Records only credential rejection, using separate single-bucket atomic Redis mutations. */
  public void recordFailure(AttemptPermit permit, byte[] canonicalCandidateIdentity) {
    Objects.requireNonNull(permit, "permit must not be null");
    Objects.requireNonNull(canonicalCandidateIdentity, "candidate identity must not be null");
    try {
      Window failureWindow = window(clock.instant(), properties.getSubjectWindowSeconds());
      Bucket sourceFailures =
          bucket(permit.key, PlatformAuthPolicy.SOURCE_FAILURE, permit.sourceBytes, failureWindow);
      buckets.increment(
          sourceFailures.key(),
          sourceFailures.digest().collisionFingerprint(),
          failureWindow.ttl(),
          properties.getMaxCounterValue());

      Bucket candidateFailures =
          bucket(
              permit.key,
              PlatformAuthPolicy.CANDIDATE_FAILURE,
              canonicalCandidateIdentity,
              failureWindow);
      buckets.increment(
          candidateFailures.key(),
          candidateFailures.digest().collisionFingerprint(),
          failureWindow.ttl(),
          properties.getMaxCounterValue());
    } catch (RuntimeException unavailable) {
      throw unavailable(unavailable);
    }
  }

  /**
   * Encodes Account's already-normalized submitted selector as UTF-8. Account does not resolve
   * aliases to a stored identity here; different normalized selectors are not claimed to converge.
   */
  public static byte[] candidateIdentity(String canonicalIdentity) {
    if (canonicalIdentity == null || canonicalIdentity.isBlank()) {
      throw new IllegalArgumentException("candidate identity must not be blank");
    }
    byte[] bytes = canonicalIdentity.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 512) {
      throw new IllegalArgumentException("candidate identity exceeds the bounded size");
    }
    return bytes;
  }

  private HmacKey activeKey() {
    try {
      validateBounds();
      return properties.activeHmacKey();
    } catch (RuntimeException unavailable) {
      throw unavailable(unavailable);
    }
  }

  private void validateBounds() {
    if (properties.getSubjectWindowSeconds() < 1
        || properties.getSubjectWindowSeconds() > 3600
        || properties.getGlobalWindowSeconds() < 1
        || properties.getGlobalWindowSeconds() > 3600
        || properties.getGlobalAdmissionsPerWindow() < 1
        || properties.getSourceAttemptsPerWindow() < 1
        || properties.getCandidateAttemptsPerWindow() < 1
        || properties.getSourceFailuresPerWindow() < 1
        || properties.getCandidateFailuresPerWindow() < 1
        || properties.getSourceRetryBaseSeconds() < 1
        || properties.getCandidateRetryBaseSeconds() < 1
        || properties.getGlobalRetryBaseSeconds() < 1
        || properties.getMaxRetrySeconds() < 1
        || properties.getMaxRetrySeconds() > 3600
        || properties.getMaxCounterValue()
            <= Math.max(
                properties.getGlobalAdmissionsPerWindow(),
                Math.max(
                    properties.getSourceAttemptsPerWindow(),
                    Math.max(
                        properties.getCandidateAttemptsPerWindow(),
                        Math.max(
                            properties.getSourceFailuresPerWindow(),
                            properties.getCandidateFailuresPerWindow()))))
        || properties.getMaxCounterValue() < 1
        || properties.getMaxCounterValue() > 1_000_000L) {
      throw new IllegalStateException("platform-auth rate-limit bounds are invalid");
    }
  }

  private Bucket bucket(HmacKey key, PlatformAuthPolicy policy, byte[] identity, Window window) {
    Digest digest = RateLimitSubjectHash.platformAuthSubject(key, policy, identity);
    return new Bucket(RateLimitSubjectHash.platformAuthBucketKey(digest, window.number()), digest);
  }

  private Window window(Instant now, int windowSeconds) {
    long epochSecond = now.getEpochSecond();
    long number = Math.floorDiv(epochSecond, windowSeconds);
    long remainingMillis =
        Math.max(1L, ((number + 1L) * windowSeconds * 1000L) - now.toEpochMilli());
    return new Window(number, Duration.ofMillis(remainingMillis));
  }

  private void enforce(long count, int threshold, int baseRetrySeconds, Window window) {
    if (count > threshold) {
      throw retry(count, threshold, baseRetrySeconds, window);
    }
  }

  private void enforceFailureThreshold(
      long count, int threshold, int baseRetrySeconds, Window window) {
    if (count >= threshold) {
      throw retry(count, threshold, baseRetrySeconds, window);
    }
  }

  private AuthenticationException retry(long count, int threshold, int base, Window window) {
    long excess = Math.max(1L, count - threshold);
    long scaled = Math.min((long) base * excess, properties.getMaxRetrySeconds());
    long remainingSeconds = Math.max(1L, (window.ttl().toMillis() + 999L) / 1000L);
    int retryAfter = (int) Math.max(1L, Math.min(scaled, remainingSeconds));
    return new AuthenticationException(
        RETRY_LATER, "Authentication temporarily unavailable; retry later.", retryAfter);
  }

  private AuthenticationException unavailable(RuntimeException cause) {
    return new AuthenticationException(
        UNAVAILABLE, "Shared authentication abuse control is unavailable.", cause);
  }

  /** Opaque per-call state pins HMAC key/window across a concurrent rotation boundary. */
  public static final class AttemptPermit {
    private final HmacKey key;
    private final byte[] sourceBytes;

    private AttemptPermit(HmacKey key, byte[] sourceBytes) {
      this.key = key;
      this.sourceBytes = sourceBytes.clone();
    }

    @Override
    public String toString() {
      return "AttemptPermit[keyId=" + key.keyId() + ", source=redacted, window=redacted]";
    }
  }

  private record Bucket(String key, Digest digest) {}

  private record Window(long number, Duration ttl) {}
}
