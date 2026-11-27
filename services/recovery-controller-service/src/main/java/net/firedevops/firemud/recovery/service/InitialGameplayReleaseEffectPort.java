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
   * Applies the effect for its exact attempt and dispatch ordinal. RETRYABLE_NO_EFFECT is a
   * definitive owner rejection for this exact attempt, never a timeout or absence observation. The
   * owner must not apply the rejected attempt later. NOT_DISPATCHED means the local adapter can
   * prove it never sent this attempt; it is retryable without owner evidence. A possibly
   * transmitted request must not be classified as NOT_DISPATCHED.
   *
   * <p>For COMMIT_OWNER_AUTHORIZED_HANDOFF, Game Session must verify that the definitive
   * preparation fence is current as part of applying the mutation. The recovery journal's dispatch
   * claim does not establish that the fence remained current through owner execution.
   */
  EffectApplyResult apply(TypedEffect effect, UUID attemptId, int attemptOrdinal);

  /**
   * Returns evidence bound to this exact attempt and dispatch ordinal. The owner must return the
   * requested ordinal in the typed receipt so an earlier observation for a reused attempt ID cannot
   * satisfy a later dispatch. The receipt identity must be valid I-JSON and retain the exact owner
   * value without normalization. PROVEN_NO_EFFECT requires the owner to have durably closed the
   * attempt so a delayed claimant can no longer apply it; absence alone is not proof.
   *
   * <p>AVAILABLE carries the owner's original observation timestamp. The adapter must establish
   * that it is comparable with the recovery controller's UTC clock and preserve the receipt's time
   * precision. The journal checks it against the persisted dispatch time and the controller's time
   * after receiving the readback. If that clock relationship cannot be established, return
   * UNAVAILABLE. Do not replace the observation with an adapter or controller receipt timestamp.
   *
   * <p>If a newer partial observation supersedes an older definitive receipt, advancing the
   * transition requires a fresh exact observation with a distinct authenticated readback identity.
   * The owner may repeat the same outcome in a new observation. Reusing a prior definitive identity
   * with a new timestamp is insufficient.
   */
  EffectReadbackResolution readback(TypedEffect effect, UUID attemptId, int attemptOrdinal);

  record EffectApplyResult(ApplyStatus status, String outcomeCode) {
    public EffectApplyResult {
      Objects.requireNonNull(status, "status");
      if (status != ApplyStatus.APPLIED) {
        net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.requiredOutcomeCode(
            outcomeCode);
      }
    }

    public static EffectApplyResult applied() {
      return new EffectApplyResult(ApplyStatus.APPLIED, null);
    }

    public static EffectApplyResult notDispatched(String outcomeCode) {
      return new EffectApplyResult(ApplyStatus.NOT_DISPATCHED, outcomeCode);
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
    NOT_DISPATCHED,
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
