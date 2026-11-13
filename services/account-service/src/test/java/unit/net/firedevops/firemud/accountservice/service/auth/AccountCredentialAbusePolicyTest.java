package net.firedevops.firemud.accountservice.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbusePolicy.Rule;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbusePolicy.Signals;
import org.junit.jupiter.api.Test;

class AccountCredentialAbusePolicyTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void evaluatesOnlyExplicitGraduatedRulesAndBoundsRetry() {
    var policy = fixture(rules());
    assertThat(policy.retryAfter(new Signals(0, 0, 0))).isEmpty();
    assertThat(policy.retryAfter(new Signals(3, 1, 100))).isEmpty();
    assertThat(policy.retryAfter(new Signals(2, 2, 100))).isEmpty();
    assertThat(policy.retryAfter(new Signals(3, 2, 0))).contains(Duration.ofSeconds(1));
    assertThat(policy.retryAfter(new Signals(3, 2, 10))).contains(Duration.ofSeconds(5));
    assertThat(policy.retryAfter(new Signals(1_000, 1_000, 1_000))).contains(Duration.ofSeconds(5));
  }

  @Test
  void neverLetsCoarsePressureClassifyAnIndividualByItself() {
    assertThatThrownBy(() -> new Rule(0, 0, 1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(fixture(rules()).retryAfter(new Signals(0, 0, 1_000))).isEmpty();
    assertThatThrownBy(() -> fixture(List.of(new Rule(3, 0, 1, Duration.ofSeconds(1)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesResourceEnvelopeInsteadOfTreatingLimitFieldsAsAuthority() {
    assertThat(fixture(rules()).maximumKeys()).isEqualTo(18);
    assertThatThrownBy(() -> fixture(rules(), 8, 18_431, 1_024))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fixture(
                    rules(), AccountCredentialAbusePolicy.MAX_EXACT_REDIS_INTEGER, 65_536, 1_024))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(fixture(rules()).toString())
        .isEqualTo("AccountCredentialAbusePolicy[explicit,non-authorizing]");
  }

  @Test
  void rejectsMalformedCounterAndRetryRules() {
    assertThatThrownBy(() -> new Signals(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Rule(1, 1, 1, Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture(List.of(new Rule(1, 1, 1, Duration.ofSeconds(11)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture(List.of(new Rule(1_001, 1, 1, Duration.ofSeconds(1)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fixture(
                    List.of(
                        new Rule(1, 1, 1, Duration.ofSeconds(5)),
                        new Rule(2, 2, 2, Duration.ofSeconds(1)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retainsImmutableRulesAndExactValidityBoundary() {
    var mutable = new ArrayList<>(rules());
    var policy = fixture(mutable);
    mutable.clear();
    assertThat(policy.rules()).hasSize(2);
    assertThatThrownBy(() -> policy.rules().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(policy.isEffectiveAt(START.minusNanos(1))).isFalse();
    assertThat(policy.isEffectiveAt(START)).isTrue();
    assertThat(policy.isEffectiveAt(START.plusSeconds(3_600))).isFalse();
  }

  @Test
  void derivesOneFixedWindowExpiryWithoutRetryExtension() {
    var policy = fixture(rules());
    assertThat(policy.expiryEpochMillis(7)).isEqualTo(540_000);
    assertThat(policy.expiryEpochMillis(8)).isEqualTo(600_000);
    assertThatThrownBy(() -> policy.expiryEpochMillis(Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAbsentAndNonRepresentableValidityInsteadOfDefaultingIt() {
    var p = fixture(rules());
    for (Instant invalid :
        List.of(Instant.MIN, Instant.MAX, START.plusNanos(1), Instant.ofEpochMilli(-1))) {
      assertThatThrownBy(
              () ->
                  withTiming(
                      p,
                      invalid,
                      p.validUntil(),
                      p.window(),
                      p.timeToLive(),
                      2,
                      p.decisionTimeout(),
                      p.maximumSignalSkew(),
                      p.maximumRetry(),
                      p.unavailableRetry()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid explicit credential-abuse policy");
    }
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    null,
                    p.validUntil(),
                    p.window(),
                    p.timeToLive(),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    START,
                    p.window(),
                    p.timeToLive(),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingFractionalAndOverflowingDurations() {
    var p = fixture(rules());
    for (Duration invalid :
        List.of(
            Duration.ZERO,
            Duration.ofMillis(-1),
            Duration.ofNanos(1),
            Duration.ofSeconds(Long.MAX_VALUE),
            Duration.ofMillis(AccountCredentialAbusePolicy.MAX_EXACT_REDIS_INTEGER + 1))) {
      assertThatThrownBy(
              () ->
                  withTiming(
                      p,
                      START,
                      p.validUntil(),
                      invalid,
                      p.timeToLive(),
                      2,
                      p.decisionTimeout(),
                      p.maximumSignalSkew(),
                      p.maximumRetry(),
                      p.unavailableRetry()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    null,
                    p.timeToLive(),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresFiniteLiveWindowsAndSignalAndRetryTimingRelationships() {
    var p = fixture(rules());
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    p.timeToLive(),
                    1,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    Duration.ofSeconds(59),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    p.timeToLive(),
                    2,
                    Duration.ofMillis(201),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    p.timeToLive(),
                    2,
                    p.decisionTimeout(),
                    p.window(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    p.timeToLive(),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    Duration.ofSeconds(121),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    p.timeToLive(),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    Duration.ofSeconds(11)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundsUpDeclaredLiveWindowsAndRejectsNullRulesAndUnboundedSignals() {
    var p = fixture(rules());
    assertThatThrownBy(
            () ->
                withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    Duration.ofMillis(120_001),
                    2,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            withTiming(
                    p,
                    START,
                    p.validUntil(),
                    p.window(),
                    Duration.ofMillis(120_001),
                    3,
                    p.decisionTimeout(),
                    p.maximumSignalSkew(),
                    p.maximumRetry(),
                    p.unavailableRetry())
                .maximumKeys())
        .isEqualTo(27);
    var nullRule = new ArrayList<Rule>();
    nullRule.add(null);
    assertThatThrownBy(() -> fixture(nullRule))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid explicit credential-abuse policy");
    assertThatThrownBy(() -> p.retryAfter(new Signals(1_001, 0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Signals(0, 0, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountCredentialAbusePolicy withTiming(
      AccountCredentialAbusePolicy p,
      Instant from,
      Instant until,
      Duration window,
      Duration ttl,
      int liveWindows,
      Duration timeout,
      Duration skew,
      Duration maximumRetry,
      Duration unavailableRetry) {
    return new AccountCredentialAbusePolicy(
        p.scope(),
        from,
        until,
        window,
        ttl,
        liveWindows,
        p.maximumActiveSubjects(),
        p.maximumMemoryBytes(),
        p.maximumBucketMemoryBytes(),
        p.maximumAdmissionsPerWindow(),
        p.maximumCounter(),
        timeout,
        skew,
        maximumRetry,
        unavailableRetry,
        p.rules());
  }

  private static List<Rule> rules() {
    return List.of(
        new Rule(3, 2, 0, Duration.ofSeconds(1)), new Rule(3, 2, 10, Duration.ofSeconds(5)));
  }

  private static AccountCredentialAbusePolicy fixture(List<Rule> rules) {
    return fixture(rules, 8, 65_536, 1_024);
  }

  private static AccountCredentialAbusePolicy fixture(
      List<Rule> rules, long subjects, long memory, long bucketMemory) {
    // Explicit synthetic fixture values, not default or recommended deployment settings.
    return new AccountCredentialAbusePolicy(
        "login-v1",
        START,
        START.plusSeconds(3_600),
        Duration.ofSeconds(60),
        Duration.ofSeconds(120),
        2,
        subjects,
        memory,
        bucketMemory,
        100,
        1_000,
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofSeconds(10),
        Duration.ofSeconds(1),
        rules);
  }
}
