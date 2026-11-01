package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy.DeploymentValue;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy.PlatformValue;
import org.junit.jupiter.api.Test;

class AccountRecentReauthenticationPolicyTest {
  @Test
  void explicitPlatformAndDeploymentValuesResolveToExactVersionedEffectivePolicy() {
    var baseline =
        AccountRecentReauthenticationPolicy.resolve(
            new PlatformValue(1L, 300L, 30L), new DeploymentValue(1L, 300L, 30L));
    var tightened =
        AccountRecentReauthenticationPolicy.resolve(
            new PlatformValue(1L, 300L, 30L), new DeploymentValue(2L, 120L, 10L));

    assertThat(baseline.recentAgeSeconds()).isEqualTo(300L);
    assertThat(baseline.maxClockSkewSeconds()).isEqualTo(30L);
    assertThat(baseline.effectivePolicyVersion()).startsWith("sha256:");
    assertThat(tightened.recentAgeSeconds()).isEqualTo(120L);
    assertThat(tightened.maxClockSkewSeconds()).isEqualTo(10L);
    assertThat(tightened.effectivePolicyVersion()).isNotEqualTo(baseline.effectivePolicyVersion());
  }

  @Test
  void deploymentCannotLoosenPlatformOrExceedHardAgeAndSkewLimits() {
    var platform = new PlatformValue(1L, 300L, 30L);
    assertThatThrownBy(
            () ->
                AccountRecentReauthenticationPolicy.resolve(
                    platform, new DeploymentValue(2L, 301L, 30L)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AccountRecentReauthenticationPolicy.resolve(
                    platform, new DeploymentValue(2L, 300L, 31L)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AccountRecentReauthenticationPolicy.resolve(
                    platform, new DeploymentValue(2L, 300L, 300L)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AccountRecentReauthenticationPolicy.resolve(
                    new PlatformValue(1L, 90L, 30L), new DeploymentValue(1L, 300L, 30L)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exactDeadlineAndFutureBeyondSkewAreRejectedWithoutWideningTheWindow() {
    var policy =
        AccountRecentReauthenticationPolicy.resolve(
            new PlatformValue(1L, 300L, 30L), new DeploymentValue(1L, 300L, 30L));
    Instant issuedAt = Instant.parse("2026-11-01T00:00:00Z");

    assertThat(policy.isRecent(issuedAt, issuedAt.plusSeconds(270).minusNanos(1))).isTrue();
    assertThat(policy.isRecent(issuedAt, issuedAt.plusSeconds(270))).isFalse();
    assertThat(policy.isRecent(issuedAt.plusSeconds(30), issuedAt)).isTrue();
    assertThat(policy.isRecent(issuedAt.plusSeconds(31), issuedAt)).isFalse();
  }

  @Test
  void instantArithmeticOverflowAtExtremeValidTimestampFailsClosed() {
    var policy =
        AccountRecentReauthenticationPolicy.resolve(
            new PlatformValue(1L, 300L, 0L), new DeploymentValue(1L, 300L, 0L));

    assertThat(policy.isRecent(Instant.MAX, Instant.MAX)).isFalse();
  }
}
