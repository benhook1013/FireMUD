package net.firedevops.firemud.accountservice.service.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One Account-global credential-attempt owner. It never receives a secret, looks up an Account,
 * creates a security lock, changes a session, or authenticates transport custody itself.
 */
public final class AccountCredentialAbuseService {
  private final AccountCredentialAbusePolicySource policies;
  private final Backend store;
  private final Clock clock;

  public AccountCredentialAbuseService(
      AccountCredentialAbusePolicySource policies, Backend store, Clock clock) {
    this.policies = policies;
    this.store = store;
    this.clock = Objects.requireNonNull(clock, "Credential-abuse clock is required");
  }

  /** Called only after the receiving owner authenticates the workload and source carrier. */
  public Decision admit(CredentialSourceContext source, String candidate) {
    AccountCredentialAbusePolicy policy = null;
    try {
      if (policies == null || store == null || source == null) return unavailable(null);
      Instant now = clock.instant();
      var snapshot = policies.resolve(now);
      if (snapshot == null || !snapshot.policy().isEffectiveAt(now)) return unavailable(null);
      policy = snapshot.policy();
      var keys = new AccountCredentialAbuseSubjectKeys(policy.scope(), snapshot.key());
      long window = Math.floorDiv(now.toEpochMilli(), policy.window().toMillis());
      var buckets =
          new Buckets(
              keys.source(source.literalAddress(), window),
              keys.candidate(candidate, window),
              keys.pressure(window),
              policy.expiryEpochMillis(window));
      long started = System.nanoTime();
      var signals = store.admit(policy, buckets, now);
      if (signals == null
          || System.nanoTime() - started > policy.maximumSignalSkew().toNanos()
          || !policy.isEffectiveAt(clock.instant())) return unavailable(policy);
      Optional<Duration> retry = policy.retryAfter(signals);
      if (retry.isPresent()) return new Decision(Outcome.RETRY_LATER, retry.get(), null);
      return new Decision(Outcome.ADMITTED, null, new Permit(this, policy, buckets));
    } catch (RuntimeException unavailable) {
      // Never preserve exceptions that may contain a raw candidate, source or delivered secret.
      return unavailable(policy);
    }
  }

  /** Local recheck after receiving SQL/request-lock waits; performs no remote operation. */
  public boolean isUsable(Permit permit) {
    if (permit == null || permit.owner != this || permit.consumed.get()) return false;
    try {
      Instant now = clock.instant();
      return permit.policy.isEffectiveAt(now)
          && now.toEpochMilli() < permit.buckets.expiryEpochMillis();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /**
   * Account's credential owner calls this exactly once after an ordinary failure, outside its SQL
   * rollback boundary. A failed/uncertain accounting result must become abuse-control unavailable.
   */
  public Decision recordFailure(Permit permit) {
    if (permit == null || permit.owner != this || !permit.consumed.compareAndSet(false, true)) {
      return unavailable(null);
    }
    var policy = permit.policy;
    try {
      Instant now = clock.instant();
      if (!policy.isEffectiveAt(now)
          || now.toEpochMilli() >= permit.buckets.expiryEpochMillis()
          || !store.recordFailure(policy, permit.buckets, now)) return unavailable(policy);
      return new Decision(Outcome.FAILURE_RECORDED, null, null);
    } catch (RuntimeException unavailable) {
      return unavailable(policy);
    }
  }

  private static Decision unavailable(AccountCredentialAbusePolicy policy) {
    return new Decision(
        Outcome.UNAVAILABLE, policy == null ? null : policy.unavailableRetry(), null);
  }

  /** Backend is injected only by the owning composition; it is not source or store authority. */
  public interface Backend {
    AccountCredentialAbusePolicy.Signals admit(
        AccountCredentialAbusePolicy policy, Buckets buckets, Instant now);

    boolean recordFailure(AccountCredentialAbusePolicy policy, Buckets buckets, Instant now);
  }

  /** Transport is validated by the receiver but deliberately not part of an allowance key. */
  public record CredentialSourceContext(String literalAddress, Transport transport) {
    public CredentialSourceContext {
      if (literalAddress == null || literalAddress.isBlank() || transport == null) {
        throw new IllegalArgumentException("Credential source context is required");
      }
    }

    @Override
    public String toString() {
      return "CredentialSourceContext[redacted]";
    }
  }

  public enum Transport {
    HTTP,
    TCP_PROXY
  }

  public record Buckets(
      AccountCredentialAbuseSubjectKeys.Subject source,
      AccountCredentialAbuseSubjectKeys.Subject candidate,
      String pressure,
      long expiryEpochMillis) {
    public Buckets {
      Objects.requireNonNull(source);
      Objects.requireNonNull(candidate);
      Objects.requireNonNull(pressure);
      if (expiryEpochMillis < 1
          || expiryEpochMillis > AccountCredentialAbusePolicy.MAX_EXACT_REDIS_INTEGER) {
        throw new IllegalArgumentException("Invalid credential-abuse bucket lifetime");
      }
    }

    @Override
    public String toString() {
      return "CredentialAbuseBuckets[redacted]";
    }
  }

  public enum Outcome {
    ADMITTED,
    FAILURE_RECORDED,
    RETRY_LATER,
    UNAVAILABLE;

    public String refusalCode() {
      return switch (this) {
        case RETRY_LATER -> "AUTH_RETRY_LATER";
        case UNAVAILABLE -> "AUTH_ABUSE_CONTROL_UNAVAILABLE";
        default -> throw new IllegalStateException("This internal outcome is not a refusal");
      };
    }
  }

  public static final class Permit {
    private final AccountCredentialAbuseService owner;
    private final AccountCredentialAbusePolicy policy;
    private final Buckets buckets;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Permit(
        AccountCredentialAbuseService owner, AccountCredentialAbusePolicy policy, Buckets buckets) {
      this.owner = owner;
      this.policy = policy;
      this.buckets = buckets;
    }

    @Override
    public String toString() {
      return "CredentialAbusePermit[redacted,not-identity-proof]";
    }
  }

  public record Decision(Outcome outcome, Duration retryAfter, Permit permit) {
    public Decision {
      Objects.requireNonNull(outcome);
      if ((outcome == Outcome.ADMITTED) != (permit != null)
          || (outcome == Outcome.RETRY_LATER && retryAfter == null)
          || (retryAfter != null && (retryAfter.isNegative() || retryAfter.isZero()))
          || ((outcome == Outcome.ADMITTED || outcome == Outcome.FAILURE_RECORDED)
              && retryAfter != null)) {
        throw new IllegalArgumentException("Invalid credential-abuse decision");
      }
    }

    @Override
    public String toString() {
      return "CredentialAbuseDecision[" + outcome + "]";
    }
  }
}
