package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CanonicalGameplayRosterRetentionPolicyTest {
  private static final String PREFIX = "firemud.canonical-roster.retention.";

  @Test
  void configuredRetentionMustCoverBothIndependentSupportedHorizons() {
    MockEnvironment configuration = configuration();
    CanonicalGameplayRosterRetentionPolicy policy =
        CanonicalGameplayRosterRetentionPolicy.fromConfiguration(configuration);

    assertThat(policy.minimumRetention()).isEqualTo(Duration.ofHours(8));
    assertThat(policy.cleanupCadence()).isEqualTo(Duration.ofMinutes(3));
    assertThat(policy.batchSize()).isEqualTo(25);

    configuration.setProperty(PREFIX + "minimum-retention", "PT3H");
    assertThatThrownBy(
            () -> CanonicalGameplayRosterRetentionPolicy.fromConfiguration(configuration))
        .isInstanceOf(IllegalArgumentException.class);
    configuration.setProperty(PREFIX + "minimum-retention", "PT8H");
    configuration.setProperty(PREFIX + "supported-retry-horizon", "PT9H");
    assertThatThrownBy(
            () -> CanonicalGameplayRosterRetentionPolicy.fromConfiguration(configuration))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void absentPolicyNeverSilentlySelectsADurationOrUnlimitedRetention() {
    for (String property :
        new String[] {
          "minimum-retention",
          "supported-retry-horizon",
          "supported-restore-replay-horizon",
          "cleanup-cadence",
          "batch-size"
        }) {
      MockEnvironment configuration = new MockEnvironment();
      MockEnvironment complete = configuration();
      for (String retainedProperty :
          new String[] {
            "minimum-retention",
            "supported-retry-horizon",
            "supported-restore-replay-horizon",
            "cleanup-cadence",
            "batch-size"
          }) {
        if (!retainedProperty.equals(property)) {
          configuration.setProperty(
              PREFIX + retainedProperty, complete.getRequiredProperty(PREFIX + retainedProperty));
        }
      }
      assertThatThrownBy(
              () -> CanonicalGameplayRosterRetentionPolicy.fromConfiguration(configuration))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rejectsInvalidDurationsAndUnboundedBatches() {
    for (String value : new String[] {"PT0S", "-PT1S", "indefinite", ""}) {
      MockEnvironment configuration = configuration();
      configuration.setProperty(PREFIX + "cleanup-cadence", value);
      assertThatThrownBy(
              () -> CanonicalGameplayRosterRetentionPolicy.fromConfiguration(configuration))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String value : new String[] {"0", "-1", "unlimited", "2147483648"}) {
      MockEnvironment configuration = configuration();
      configuration.setProperty(PREFIX + "batch-size", value);
      assertThatThrownBy(
              () -> CanonicalGameplayRosterRetentionPolicy.fromConfiguration(configuration))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static MockEnvironment configuration() {
    return new MockEnvironment()
        .withProperty(PREFIX + "minimum-retention", "PT8H")
        .withProperty(PREFIX + "supported-retry-horizon", "PT2H")
        .withProperty(PREFIX + "supported-restore-replay-horizon", "PT8H")
        .withProperty(PREFIX + "cleanup-cadence", "PT3M")
        .withProperty(PREFIX + "batch-size", "25");
  }
}
