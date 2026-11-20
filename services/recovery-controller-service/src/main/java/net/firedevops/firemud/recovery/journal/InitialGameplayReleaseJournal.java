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

  OutboxEntry claimEffect(UUID operationId, UUID effectId, UUID attemptId, Instant now);

  /** False only for an exact dispatch superseded after validated durable owner closure. */
  boolean recordRetryableEffectForDispatch(OutboxEntry dispatch, String outcomeCode, Instant now);

  /** False only for an exact dispatch superseded after validated durable owner closure. */
  boolean recordAmbiguousEffectForDispatch(OutboxEntry dispatch, String outcomeCode, Instant now);

  Operation recordEffectReadback(
      UUID operationId, UUID effectId, UUID attemptId, EffectReadback readback, Instant now);

  Optional<OutboxEntry> findEffect(UUID operationId, EffectKind effectKind);
}
