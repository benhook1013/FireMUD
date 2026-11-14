package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRegionLeaseInstallDeadlineTest {
  @Test
  void shortGrantBoundsInstallBeforeLongerHandoffBudget() {
    assertThat(CanonicalGameplayRegionLeaseOwner.renewedGrantDeadline(
        1000L, Duration.ofMillis(100), 6000L)).isEqualTo(1100L);
  }

  @Test
  void longerGrantNeverExtendsHandoffBudget() {
    assertThat(CanonicalGameplayRegionLeaseOwner.renewedGrantDeadline(
        1000L, Duration.ofSeconds(30), 6000L)).isEqualTo(6000L);
  }

  @Test
  void rejectsSubmillisecondGrantAndOverflowInsteadOfExtendingAuthority() {
    assertThatThrownBy(() -> CanonicalGameplayRegionLeaseOwner.renewedGrantDeadline(
        1000L, Duration.ofNanos(1), 6000L)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> CanonicalGameplayRegionLeaseOwner.renewedGrantDeadline(
        Long.MAX_VALUE - 1, Duration.ofMillis(2), Long.MAX_VALUE))
        .isInstanceOf(IllegalStateException.class);
  }
}
