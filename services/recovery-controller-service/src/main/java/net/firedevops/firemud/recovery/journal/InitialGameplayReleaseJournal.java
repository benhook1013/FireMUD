package net.firedevops.firemud.recovery.journal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectKind;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionTuple;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;

/** Durable owner store for the initial-gameplay-release operation and effects. */
public interface InitialGameplayReleaseJournal {
  Operation create(Scope scope, UUID controllerIssuedLockIdentity);

  Optional<Operation> find(UUID operationId);

  Operation bindVerifiedEvidence(
      Scope scope,
      String scopeDigest,
      InitialGameplayReleaseEvidence evidence,
      String evidenceDigest,
      Instant now);

  Optional<Operation> findTransition(PublicTransitionTuple tuple);

  Operation transition(PublicTransitionTuple tuple, Instant now);

  Optional<OutboxEntry> findNextEffect(UUID operationId);

  /**
   * Uses a fresh attempt identity for each invocation, including retries after an uncertain claim
   * outcome. An existing dispatched or ambiguous result is for exact owner readback, not permission
   * to send again. A request for the already dispatched identity is rejected so a caller cannot
   * mistake a replay for a new claim. Reuse is intentional only after a proven local NOT_DISPATCHED
   * outcome and creates a new dispatch ordinal. A durable owner rejection or closure permanently
   * retires that identity.
   */
  OutboxEntry claimEffect(UUID operationId, UUID effectId, UUID attemptId, Instant now);

  /** False only for an exact dispatch superseded after definitive owner rejection or closure. */
  boolean recordRetryableEffectForDispatch(
      OutboxEntry dispatch, RetrySource source, String outcomeCode, Instant now);

  /** False only for an exact dispatch superseded after definitive owner rejection or closure. */
  boolean recordAmbiguousEffectForDispatch(OutboxEntry dispatch, String outcomeCode, Instant now);

  enum RetrySource {
    NOT_DISPATCHED,
    OWNER_REJECTED
  }

  Operation recordEffectReadback(OutboxEntry dispatch, EffectReadback readback, Instant now);

  Optional<OutboxEntry> findEffect(UUID operationId, EffectKind effectKind);
}
