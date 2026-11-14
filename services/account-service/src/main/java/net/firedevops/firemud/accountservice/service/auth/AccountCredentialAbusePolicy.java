package net.firedevops.firemud.accountservice.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit Account-owned policy values, with no defaults. Validation of these values is not proof
 * that a Redis deployment actually enforces their resource envelope.
 */
public record AccountCredentialAbusePolicy(
    String scope,
    Instant validFrom,
    Instant validUntil,
    Duration window,
    Duration timeToLive,
    int maximumLiveWindows,
    long maximumActiveSubjects,
    long maximumMemoryBytes,
    long maximumBucketMemoryBytes,
    long maximumAdmissionsPerWindow,
    long maximumCounter,
    Duration decisionTimeout,
    Duration maximumSignalSkew,
    Duration maximumRetry,
    Duration unavailableRetry,
    List<Rule> rules) {
  /** Redis Lua numbers cannot represent every larger integer exactly. */
  public static final long MAX_EXACT_REDIS_INTEGER = 9_007_199_254_740_991L;

  public AccountCredentialAbusePolicy {
    if (scope == null || !scope.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw invalid();
    exactEpochMillis(validFrom);
    exactEpochMillis(validUntil);
    if (!validFrom.isBefore(validUntil)) throw invalid();
    long windowMillis = positiveMillis(window);
    long ttlMillis = positiveMillis(timeToLive);
    long decisionMillis = positiveMillis(decisionTimeout);
    long skewMillis = positiveMillis(maximumSignalSkew);
    long retryMillis = positiveMillis(maximumRetry);
    long unavailableMillis = positiveMillis(unavailableRetry);
    if (maximumLiveWindows <= 0
        || ttlMillis < windowMillis
        || ttlMillis / windowMillis + (ttlMillis % windowMillis == 0 ? 0 : 1) > maximumLiveWindows
        || decisionMillis > skewMillis
        || skewMillis >= windowMillis
        || retryMillis > ttlMillis
        || unavailableMillis > retryMillis) throw invalid();
    positiveExact(maximumActiveSubjects);
    positiveExact(maximumMemoryBytes);
    positiveExact(maximumBucketMemoryBytes);
    positiveExact(maximumAdmissionsPerWindow);
    positiveExact(maximumCounter);
    if (maximumAdmissionsPerWindow > maximumCounter) throw invalid();
    try {
      // Each active subject has one bucket per live window; pressure adds one per window.
      long maximumKeys =
          Math.multiplyExact(Math.addExact(maximumActiveSubjects, 1L), maximumLiveWindows);
      long declaredBucketEnvelope = Math.multiplyExact(maximumKeys, maximumBucketMemoryBytes);
      if (maximumKeys > MAX_EXACT_REDIS_INTEGER || declaredBucketEnvelope > maximumMemoryBytes) {
        throw invalid();
      }
    } catch (ArithmeticException overflow) {
      throw invalid();
    }
    if (rules == null || rules.isEmpty()) throw invalid();
    // Reject nulls with the same sanitized configuration error as other malformed inputs.
    for (Rule rule : rules) {
      if (rule == null) throw invalid();
    }
    rules = List.copyOf(rules);
    long previousRetry = 0;
    boolean source = false;
    boolean candidate = false;
    boolean pressure = false;
    for (Rule rule : rules) {
      if (rule == null
          || rule.sourceFailures() > maximumCounter
          || rule.candidateFailures() > maximumCounter
          || rule.globalPressure() > maximumCounter) throw invalid();
      long ruleRetry = positiveMillis(rule.retryAfter());
      if (ruleRetry <= previousRetry || ruleRetry > retryMillis) throw invalid();
      previousRetry = ruleRetry;
      source |= rule.sourceFailures() > 0;
      candidate |= rule.candidateFailures() > 0;
      pressure |= rule.globalPressure() > 0;
    }
    // A complete profile must use the three signals; it cannot silently become an IP-only policy.
    if (!source || !candidate || !pressure) throw invalid();
  }

  public boolean isEffectiveAt(Instant instant) {
    Objects.requireNonNull(instant, "Evaluation time is required");
    return !instant.isBefore(validFrom) && instant.isBefore(validUntil);
  }

  /**
   * Pure decision over the bounded signals; store availability is checked by the owner separately.
   */
  public Optional<Duration> retryAfter(Signals signals) {
    Objects.requireNonNull(signals, "Credential-abuse signals are required");
    if (signals.sourceFailures() > maximumCounter
        || signals.candidateFailures() > maximumCounter
        || signals.globalPressure() > maximumCounter) throw invalid();
    Duration result = null;
    for (Rule rule : rules) {
      if (rule.matches(signals)) result = rule.retryAfter();
    }
    return Optional.ofNullable(result);
  }

  public long maximumKeys() {
    return Math.multiplyExact(Math.addExact(maximumActiveSubjects, 1L), maximumLiveWindows);
  }

  /** Fixed-window expiration never extends when a bucket is retried or its counter changes. */
  public long expiryEpochMillis(long windowNumber) {
    if (windowNumber < 0 || windowNumber > MAX_EXACT_REDIS_INTEGER) throw invalid();
    try {
      long expiry =
          Math.addExact(Math.multiplyExact(windowNumber, window.toMillis()), timeToLive.toMillis());
      if (expiry > MAX_EXACT_REDIS_INTEGER) throw invalid();
      return expiry;
    } catch (ArithmeticException overflow) {
      throw invalid();
    }
  }

  @Override
  public String toString() {
    return "AccountCredentialAbusePolicy[explicit,non-authorizing]";
  }

  /**
   * All enabled thresholds in one rule must match. Zero disables that signal in this rule. A
   * pressure-only rule is forbidden; pressure cannot classify an individual by itself.
   */
  public record Rule(
      long sourceFailures, long candidateFailures, long globalPressure, Duration retryAfter) {
    public Rule {
      nonnegativeExact(sourceFailures);
      nonnegativeExact(candidateFailures);
      nonnegativeExact(globalPressure);
      positiveMillis(retryAfter);
      if (sourceFailures == 0 && candidateFailures == 0) throw invalid();
    }

    private boolean matches(Signals signals) {
      return (sourceFailures == 0 || signals.sourceFailures() >= sourceFailures)
          && (candidateFailures == 0 || signals.candidateFailures() >= candidateFailures)
          && (globalPressure == 0 || signals.globalPressure() >= globalPressure);
    }
  }

  public record Signals(long sourceFailures, long candidateFailures, long globalPressure) {
    public Signals {
      nonnegativeExact(sourceFailures);
      nonnegativeExact(candidateFailures);
      nonnegativeExact(globalPressure);
    }
  }

  private static long positiveMillis(Duration value) {
    if (value == null || value.isNegative() || value.isZero()) throw invalid();
    try {
      long milliseconds = value.toMillis();
      positiveExact(milliseconds);
      if (!Duration.ofMillis(milliseconds).equals(value)) throw invalid();
      return milliseconds;
    } catch (ArithmeticException overflow) {
      throw invalid();
    }
  }

  private static void exactEpochMillis(Instant value) {
    if (value == null) throw invalid();
    try {
      long milliseconds = value.toEpochMilli();
      nonnegativeExact(milliseconds);
      if (!Instant.ofEpochMilli(milliseconds).equals(value)) throw invalid();
    } catch (ArithmeticException overflow) {
      throw invalid();
    }
  }

  private static void positiveExact(long value) {
    if (value <= 0 || value > MAX_EXACT_REDIS_INTEGER) throw invalid();
  }

  private static void nonnegativeExact(long value) {
    if (value < 0 || value > MAX_EXACT_REDIS_INTEGER) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid explicit credential-abuse policy");
  }
}
