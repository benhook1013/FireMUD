package net.firedevops.firemud.entitymanagement.service;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.core.env.PropertyResolver;

/**
 * Explicit operational inputs for the Entity-owned sealed roster lineage family.
 *
 * <p>This value neither registers cleanup nor establishes disposal eligibility. The restore/replay
 * horizon must include the complete interval during which a supported restore can still replay or
 * retry a dependent original selection. Unknown horizons must leave the owning boundary
 * unavailable.
 */
public record CanonicalGameplayRosterRetentionPolicy(
    Duration minimumRetention,
    Duration supportedRetryHorizon,
    Duration supportedRestoreReplayHorizon,
    Duration cleanupCadence,
    int batchSize) {
  private static final String PREFIX = "firemud.canonical-roster.retention.";

  public CanonicalGameplayRosterRetentionPolicy {
    requirePositive(minimumRetention, "minimumRetention");
    requirePositive(supportedRetryHorizon, "supportedRetryHorizon");
    requirePositive(supportedRestoreReplayHorizon, "supportedRestoreReplayHorizon");
    requirePositive(cleanupCadence, "cleanupCadence");
    if (minimumRetention.compareTo(supportedRetryHorizon) < 0
        || minimumRetention.compareTo(supportedRestoreReplayHorizon) < 0) {
      throw new IllegalArgumentException(
          "Roster retention must cover every supported retry and restore/replay horizon");
    }
    if (minimumRetention.toMillis() <= 0
        || supportedRetryHorizon.toMillis() <= 0
        || supportedRestoreReplayHorizon.toMillis() <= 0
        || cleanupCadence.toMillis() <= 0) {
      throw new IllegalArgumentException(
          "Roster policy requires finite positive millisecond horizons");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
  }

  /** Reads mandatory ISO-8601 durations and a positive batch bound without fallback defaults. */
  public static CanonicalGameplayRosterRetentionPolicy fromConfiguration(
      PropertyResolver configuration) {
    Objects.requireNonNull(configuration, "configuration");
    return new CanonicalGameplayRosterRetentionPolicy(
        configuredDuration(configuration, "minimum-retention"),
        configuredDuration(configuration, "supported-retry-horizon"),
        configuredDuration(configuration, "supported-restore-replay-horizon"),
        configuredDuration(configuration, "cleanup-cadence"),
        Integer.parseInt(configuration.getRequiredProperty(PREFIX + "batch-size")));
  }

  private static Duration configuredDuration(PropertyResolver configuration, String property) {
    String value = configuration.getRequiredProperty(PREFIX + property);
    try {
      return Duration.parse(value);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          PREFIX + property + " must be an ISO-8601 duration", exception);
    }
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
