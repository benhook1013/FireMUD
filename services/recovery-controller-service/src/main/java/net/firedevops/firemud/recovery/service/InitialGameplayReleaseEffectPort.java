package net.firedevops.firemud.recovery.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TypedEffect;

/**
 * Game Session owner boundary for exact typed effects and owner-produced durable/current readbacks.
 */
public interface InitialGameplayReleaseEffectPort {
  /**
   * RETRYABLE_NO_EFFECT is a definitive owner rejection for this exact attempt, never a timeout or
   * absence observation. The owner must not apply the rejected attempt later.
   */
  EffectApplyResult apply(TypedEffect effect, UUID attemptId);

  /**
   * Returns evidence bound to this exact attempt. PROVEN_NO_EFFECT requires the owner to have
   * durably closed the attempt so a delayed claimant can no longer apply it; absence alone is not
   * proof.
   */
  EffectReadbackResolution readback(TypedEffect effect, UUID attemptId);

  record EffectApplyResult(ApplyStatus status, String outcomeCode) {
    public EffectApplyResult {
      Objects.requireNonNull(status, "status");
      if (status == ApplyStatus.RETRYABLE_NO_EFFECT || status == ApplyStatus.AMBIGUOUS) {
        net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.requiredOutcomeCode(
            outcomeCode);
      }
    }

    public static EffectApplyResult applied() {
      return new EffectApplyResult(ApplyStatus.APPLIED, null);
    }

    public static EffectApplyResult retryableNoEffect(String outcomeCode) {
      return new EffectApplyResult(ApplyStatus.RETRYABLE_NO_EFFECT, outcomeCode);
    }

    public static EffectApplyResult ambiguous(String outcomeCode) {
      return new EffectApplyResult(ApplyStatus.AMBIGUOUS, outcomeCode);
    }
  }

  enum ApplyStatus {
    APPLIED,
    RETRYABLE_NO_EFFECT,
    AMBIGUOUS
  }

  record EffectReadbackResolution(
      ReadbackStatus status, EffectReadback readback, Instant observedAt) {
    public EffectReadbackResolution {
      Objects.requireNonNull(status, "status");
      if ((status == ReadbackStatus.AVAILABLE) != (readback != null)) {
        throw new IllegalArgumentException("readback must be present exactly for AVAILABLE");
      }
      if (status == ReadbackStatus.AVAILABLE && !readback.observedAt().equals(observedAt)) {
        throw new IllegalArgumentException(
            "available readback observation time must match receipt");
      }
    }

    public static EffectReadbackResolution available(EffectReadback readback) {
      return new EffectReadbackResolution(
          ReadbackStatus.AVAILABLE, readback, readback.observedAt());
    }

    public static EffectReadbackResolution unavailable() {
      return new EffectReadbackResolution(ReadbackStatus.UNAVAILABLE, null, null);
    }
  }

  enum ReadbackStatus {
    AVAILABLE,
    UNAVAILABLE
  }
}
