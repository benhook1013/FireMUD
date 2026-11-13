package net.firedevops.firemud.accountservice.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Backend;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Buckets;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.CredentialSourceContext;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Outcome;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Transport;
import org.junit.jupiter.api.Test;

class AccountCredentialAbuseServiceTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void localPermitRecheckRejectsConsumedForeignAndExpiredAdmissionsWithoutBackendWork() {
    var now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
    Clock clock =
        new Clock() {
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException();
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    var backend = new FakeBackend();
    try (var key = key()) {
      var owner =
          new AccountCredentialAbuseService(
              AccountCredentialAbusePolicySource.controlled(policy(), key), backend, clock);
      var other = service(key, backend);
      var permit = owner.admit(source(Transport.HTTP), "player@example.test").permit();
      assertThat(owner.isUsable(permit)).isTrue();
      assertThat(owner.isUsable(null)).isFalse();
      assertThat(other.isUsable(permit)).isFalse();
      now.set(NOW.plusSeconds(120));
      assertThat(owner.isUsable(permit)).isFalse();
      now.set(NOW);
      assertThat(owner.recordFailure(permit).outcome()).isEqualTo(Outcome.FAILURE_RECORDED);
      assertThat(owner.isUsable(permit)).isFalse();
      assertThat(backend.buckets).hasSize(1);
      assertThat(backend.failures).isEqualTo(1);
    }
  }

  @Test
  void missingOrExpiredPolicyAndStoreNeverReachCredentialAdmission() {
    var backend = new FakeBackend();
    assertThat(
            new AccountCredentialAbuseService(null, backend, CLOCK)
                .admit(source(Transport.HTTP), "unknown@example.test")
                .outcome())
        .isEqualTo(Outcome.UNAVAILABLE);
    try (var key = key()) {
      var policies = AccountCredentialAbusePolicySource.controlled(policy(), key);
      assertThat(
              new AccountCredentialAbuseService(policies, null, CLOCK)
                  .admit(source(Transport.HTTP), "unknown@example.test")
                  .outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
      var expired = Clock.fixed(NOW.plusSeconds(3_600), ZoneOffset.UTC);
      assertThat(
              new AccountCredentialAbuseService(policies, backend, expired)
                  .admit(source(Transport.HTTP), "unknown@example.test")
                  .outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
    }
    assertThat(backend.buckets).isEmpty();
  }

  @Test
  void transportsAndCandidateSpellingShareTheSameFailureSubjects() {
    var backend = new FakeBackend();
    try (var key = key()) {
      var service = service(key, backend);
      var http = service.admit(source(Transport.HTTP), "  PLAYER@example.TEST ");
      var tcp =
          service.admit(
              new CredentialSourceContext("::ffff:192.0.2.1", Transport.TCP_PROXY),
              "player@example.test");
      assertThat(http.outcome()).isEqualTo(Outcome.ADMITTED);
      assertThat(tcp.outcome()).isEqualTo(Outcome.ADMITTED);
      assertThat(backend.buckets.get(0).source().redisKey())
          .isEqualTo(backend.buckets.get(1).source().redisKey());
      assertThat(backend.buckets.get(0).candidate().redisKey())
          .isEqualTo(backend.buckets.get(1).candidate().redisKey());
      assertThat(service.recordFailure(http.permit()).outcome())
          .isEqualTo(Outcome.FAILURE_RECORDED);
      assertThat(service.recordFailure(http.permit()).outcome()).isEqualTo(Outcome.UNAVAILABLE);
      assertThat(backend.failures).isEqualTo(1);
    }
  }

  @Test
  void graduatedRetriesUseOnlyPolicySignalsWithoutAccountExistence() {
    var backend = new FakeBackend();
    backend.signals = new AccountCredentialAbusePolicy.Signals(3, 2, 10);
    try (var key = key()) {
      var service = service(key, backend);
      for (String candidate : List.of("known@example.test", "unknown@example.test")) {
        var result = service.admit(source(Transport.HTTP), candidate);
        assertThat(result.outcome().refusalCode()).isEqualTo("AUTH_RETRY_LATER");
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(result.permit()).isNull();
      }
    }
  }

  @Test
  void overloadUnavailabilityAndUnsafeInputsHaveNoAdmittingFallback() {
    var backend = new FakeBackend();
    backend.signals = null;
    try (var key = key()) {
      var service = service(key, backend);
      var result = service.admit(source(Transport.HTTP), "unknown@example.test");
      assertThat(result.outcome().refusalCode()).isEqualTo("AUTH_ABUSE_CONTROL_UNAVAILABLE");
      assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(1));
      assertThat(result.permit()).isNull();
      assertThat(
              service
                  .admit(
                      new CredentialSourceContext("untrusted-host.test", Transport.HTTP),
                      "unknown@example.test")
                  .outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
      assertThat(backend.buckets).hasSize(1);
    }
  }

  @Test
  void failureAccountingIsOneUseOwnerBoundAndFailClosed() {
    var backend = new FakeBackend();
    try (var key = key()) {
      var owner = service(key, backend);
      var other = service(key, backend);
      var permit = owner.admit(source(Transport.HTTP), "unknown@example.test").permit();
      assertThat(other.recordFailure(permit).outcome()).isEqualTo(Outcome.UNAVAILABLE);
      backend.recorded = false;
      assertThat(owner.recordFailure(permit).outcome()).isEqualTo(Outcome.UNAVAILABLE);
      assertThat(owner.recordFailure(permit).outcome()).isEqualTo(Outcome.UNAVAILABLE);
      assertThat(backend.failures).isEqualTo(1);
      assertThat(permit.toString()).doesNotContain("192.0.2.1", "unknown@example.test");
    }
  }

  @Test
  void thrownPolicyAndStoreFailuresAreSanitized() {
    var backend = new FakeBackend();
    AccountCredentialAbusePolicySource broken =
        now -> {
          throw new IllegalStateException("private-input");
        };
    var result =
        new AccountCredentialAbuseService(broken, backend, CLOCK)
            .admit(source(Transport.HTTP), "unknown@example.test");
    assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
    assertThat(result.toString()).doesNotContain("private-input", "unknown@example.test");
  }

  static AccountCredentialAbusePolicy policy() {
    // Synthetic controlled values only; neither deployment defaults nor real memory proof.
    return new AccountCredentialAbusePolicy(
        "login-v1",
        NOW,
        NOW.plusSeconds(3_600),
        Duration.ofSeconds(60),
        Duration.ofSeconds(120),
        2,
        8,
        65_536,
        1_024,
        100,
        1_000,
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofSeconds(10),
        Duration.ofSeconds(1),
        List.of(
            new AccountCredentialAbusePolicy.Rule(3, 2, 0, Duration.ofSeconds(1)),
            new AccountCredentialAbusePolicy.Rule(3, 2, 10, Duration.ofSeconds(5))));
  }

  private static AccountCredentialAbuseSubjectKeys.DigestKey key() {
    return new AccountCredentialAbuseSubjectKeys.DigestKey("test-key", new byte[32]);
  }

  private static AccountCredentialAbuseService service(
      AccountCredentialAbuseSubjectKeys.DigestKey key, Backend backend) {
    return new AccountCredentialAbuseService(
        AccountCredentialAbusePolicySource.controlled(policy(), key), backend, CLOCK);
  }

  private static CredentialSourceContext source(Transport transport) {
    return new CredentialSourceContext("192.0.2.1", transport);
  }

  private static final class FakeBackend implements Backend {
    final List<Buckets> buckets = new ArrayList<>();
    AccountCredentialAbusePolicy.Signals signals =
        new AccountCredentialAbusePolicy.Signals(0, 0, 0);
    boolean recorded = true;
    int failures;

    public AccountCredentialAbusePolicy.Signals admit(
        AccountCredentialAbusePolicy policy, Buckets input, Instant now) {
      buckets.add(input);
      return signals;
    }

    public boolean recordFailure(AccountCredentialAbusePolicy policy, Buckets input, Instant now) {
      failures++;
      return recorded;
    }
  }
}
