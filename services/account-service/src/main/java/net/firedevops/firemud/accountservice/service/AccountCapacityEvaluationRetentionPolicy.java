package net.firedevops.firemud.accountservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Classifies an observed evaluation using explicit policy inputs. Grants no cleanup authority. */
final class AccountCapacityEvaluationRetentionPolicy {
  private AccountCapacityEvaluationRetentionPolicy() {}

  enum Classification {
    CANDIDATE,
    LIVE_EVALUATION,
    RETAINED_RESERVATION_SCOPE,
    POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE,
    HELD,
    ABOVE_SAFE_WATERMARK,
    RETENTION_WINDOW_OPEN
  }

  enum Hold {
    CLEAR,
    HELD
  }

  /** The declared durations are relative to evaluation time. Maximum caps the dependencies. */
  record Schedule(
      String version,
      Duration maximum,
      Duration evidence,
      Duration retry,
      Duration replay,
      Duration restore,
      Duration investigation) {
    Schedule {
      requireVersion(version);
      requirePositive(maximum);
      for (Duration horizon : List.of(evidence, retry, replay, restore, investigation)) {
        requirePositive(horizon);
        if (horizon.compareTo(maximum) > 0)
          throw new IllegalArgumentException("Retention horizon exceeds the schedule maximum");
      }
    }

    private Duration requiredHorizon() {
      return Collections.max(List.of(evidence, retry, replay, restore, investigation));
    }
  }

  /** One bounded hold observation. Its safe watermark is inclusive in evaluation-time order. */
  record Safety(
      String scheduleVersion,
      Instant observedAt,
      Instant validUntil,
      Instant safeWatermark,
      Map<String, Hold> holds) {
    Safety {
      requireVersion(scheduleVersion);
      Objects.requireNonNull(observedAt);
      Objects.requireNonNull(validUntil);
      Objects.requireNonNull(safeWatermark);
      if (!observedAt.isBefore(validUntil) || safeWatermark.isAfter(observedAt))
        throw new IllegalArgumentException("Invalid capacity retention observation window");
      if (Objects.requireNonNull(holds).size()
          > AccountCapacityEvaluationRetentionInspectionOwner.MAX_SAMPLE_ROWS)
        throw new IllegalArgumentException(
            "Capacity retention hold observations exceed the sample bound");
      holds = Map.copyOf(holds);
    }
  }

  static boolean current(
      @Nullable Schedule schedule, @Nullable Safety safety, @Nullable Instant observedAt) {
    return schedule != null
        && safety != null
        && observedAt != null
        && schedule.version().equals(safety.scheduleVersion())
        && !observedAt.isBefore(safety.observedAt())
        && observedAt.isBefore(safety.validUntil());
  }

  static Classification classify(
      @Nullable Schedule schedule,
      @Nullable Safety safety,
      @Nullable Instant observedAt,
      @Nullable String evaluationIdentity,
      @Nullable Instant evaluatedAt,
      @Nullable Instant expiresAt,
      @Nullable Boolean scopeReferenced) {
    if (Boolean.TRUE.equals(scopeReferenced)) return Classification.RETAINED_RESERVATION_SCOPE;
    if (scopeReferenced == null
        || observedAt == null
        || evaluationIdentity == null
        || evaluationIdentity.isBlank()
        || evaluatedAt == null
        || expiresAt == null
        || !evaluatedAt.isBefore(expiresAt)
        || evaluatedAt.isAfter(observedAt))
      return Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE;
    if (observedAt.isBefore(expiresAt)) return Classification.LIVE_EVALUATION;
    if (schedule == null
        || safety == null
        || !current(schedule, safety, observedAt)
        || Duration.between(evaluatedAt, expiresAt).compareTo(schedule.maximum()) > 0)
      return Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE;
    Hold hold = safety.holds().get(evaluationIdentity);
    if (hold == null) return Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE;
    if (hold == Hold.HELD) return Classification.HELD;
    if (evaluatedAt.isAfter(safety.safeWatermark())) return Classification.ABOVE_SAFE_WATERMARK;
    if (Duration.between(evaluatedAt, observedAt).compareTo(schedule.requiredHorizon()) < 0)
      return Classification.RETENTION_WINDOW_OPEN;
    return Classification.CANDIDATE;
  }

  private static void requireVersion(String version) {
    if (Objects.requireNonNull(version).isBlank())
      throw new IllegalArgumentException("Capacity retention schedule version is required");
  }

  private static void requirePositive(Duration duration) {
    if (Objects.requireNonNull(duration).isZero() || duration.isNegative())
      throw new IllegalArgumentException("Capacity retention horizons must be positive");
  }
}
