package net.firedevops.firemud.recovery.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseConflictException;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal.RetrySource;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands.ContinueRecovery;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands.Resume;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionTuple;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TransitionVerb;
import net.firedevops.firemud.recovery.model.MaintenanceLockCredential;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort.ApplyStatus;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort.EffectApplyResult;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort.EffectReadbackResolution;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider.EvidenceResolution;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider.ResolutionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Narrow initial-gameplay-release owner. It has no HTTP ingress or autonomous effect dispatcher.
 */
public final class InitialGameplayReleaseController {
  private final InitialGameplayReleaseJournal journal;
  private final InitialGameplayReleaseTupleCodec tupleCodec;
  private final InitialGameplayReleaseEvidenceProvider evidenceProvider;
  private final InitialGameplayReleaseEffectPort effectPort;
  private final Clock clock;

  public InitialGameplayReleaseController(
      InitialGameplayReleaseJournal journal,
      InitialGameplayReleaseTupleCodec tupleCodec,
      InitialGameplayReleaseEvidenceProvider evidenceProvider,
      InitialGameplayReleaseEffectPort effectPort) {
    this(journal, tupleCodec, evidenceProvider, effectPort, Clock.systemUTC());
  }

  InitialGameplayReleaseController(
      InitialGameplayReleaseJournal journal,
      InitialGameplayReleaseTupleCodec tupleCodec,
      InitialGameplayReleaseEvidenceProvider evidenceProvider,
      InitialGameplayReleaseEffectPort effectPort,
      Clock clock) {
    this.journal = Objects.requireNonNull(journal, "journal must not be null");
    this.tupleCodec = Objects.requireNonNull(tupleCodec, "tupleCodec must not be null");
    this.evidenceProvider =
        Objects.requireNonNull(evidenceProvider, "evidenceProvider must not be null");
    this.effectPort = Objects.requireNonNull(effectPort, "effectPort must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Denies operation creation until Account redemption, genuine owner association/storage checks,
   * and exclusive maintenance fence/lock acquisition exist. Typed selectors grant no authority.
   */
  public RecoveryCreated recover(InitialGameplayRecoverRequest command) {
    Objects.requireNonNull(command, "command must not be null");
    throw unavailable("INITIAL_GAMEPLAY_RECOVER_AUTHORIZATION_AND_EXCLUSIVE_FENCE_UNAVAILABLE");
  }

  /** Resolves all source-owned proof and only then binds the immutable readiness tuple. */
  public Operation refreshReadiness(UUID operationId, MaintenanceLockCredential maintenanceLock) {
    Operation operation = requireOperation(operationId);
    requireLock(operation, maintenanceLock);
    EvidenceResolution resolution = evidenceProvider.read(operation.scope());
    if (resolution.status() != ResolutionStatus.AVAILABLE) {
      throw unavailable("INITIAL_GAMEPLAY_RELEASE_EVIDENCE_" + resolution.status().name());
    }
    InitialGameplayReleaseEvidence evidence = resolution.evidence();
    evidence.validateFor(operation.scope());
    String digest = tupleCodec.digest(evidence);
    return journal.bindVerifiedEvidence(
        operation.scope(), tupleCodec.digest(operation.scope()), evidence, digest, clock.instant());
  }

  /**
   * The only public continuation transition. It can move ready_to_reopen to awaiting_resume and
   * never releases the owner fence.
   */
  public Operation continueRecovery(ContinueRecovery command) {
    Objects.requireNonNull(command, "command must not be null");
    return transition(
        command.operationId(),
        command.expectedPhase(),
        command.maintenanceLock(),
        command.evidenceRef(),
        TransitionVerb.CONTINUE_RECOVERY);
  }

  /** Separately records explicit authorization from awaiting_resume to resume_authorized. */
  public Operation resume(Resume command) {
    Objects.requireNonNull(command, "command must not be null");
    return transition(
        command.operationId(),
        command.expectedPhase(),
        command.maintenanceLock(),
        command.evidenceRef(),
        TransitionVerb.RESUME);
  }

  /**
   * Performs one outbox attempt or reconciles its exact prior attempt by owner readback. This
   * method is deliberately not scheduled or exposed as a network endpoint in this source-only
   * stage. Reconciliation must run without a caller-owned transaction so a successful claim is
   * durable before an owner effect can be dispatched.
   */
  public Operation reconcileNextEffect(UUID operationId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable("INITIAL_GAMEPLAY_RELEASE_RECONCILIATION_REQUIRES_NO_CALLER_TRANSACTION");
    }
    Operation operation = requireOperation(operationId);
    Optional<OutboxEntry> next = journal.findNextEffect(operationId);
    if (next.isEmpty()) {
      return requireOperation(operationId);
    }
    OutboxEntry outbox = next.orElseThrow();
    if (outbox.state() == EffectOutboxState.PENDING
        || outbox.state() == EffectOutboxState.RETRYABLE) {
      return applyEffect(operation, outbox);
    }
    return readBackEffect(operation, outbox);
  }

  private Operation applyEffect(Operation operation, OutboxEntry outbox) {
    UUID attemptId = UUID.randomUUID();
    OutboxEntry dispatched =
        journal.claimEffect(
            operation.scope().operationId(),
            outbox.effect().effectId(),
            attemptId,
            clock.instant());
    if (dispatched.state() != EffectOutboxState.DISPATCHED
        || !attemptId.equals(dispatched.lastAttemptId())) {
      return requireOperation(operation.scope().operationId());
    }
    final EffectApplyResult applyResult;
    try {
      applyResult =
          Objects.requireNonNull(
              effectPort.apply(dispatched.effect(), attemptId, dispatched.attemptCount()),
              "applyResult");
    } catch (RuntimeException exception) {
      journal.recordAmbiguousEffectForDispatch(
          dispatched, "EFFECT_CALL_OUTCOME_UNKNOWN", clock.instant());
      return requireOperation(operation.scope().operationId());
    }
    if (applyResult.status() == ApplyStatus.RETRYABLE_NO_EFFECT
        || applyResult.status() == ApplyStatus.NOT_DISPATCHED) {
      // An exact, durably closed prior dispatch can be superseded while the apply call was pending.
      journal.recordRetryableEffectForDispatch(
          dispatched,
          applyResult.status() == ApplyStatus.NOT_DISPATCHED
              ? RetrySource.NOT_DISPATCHED
              : RetrySource.OWNER_REJECTED,
          applyResult.outcomeCode(),
          clock.instant());
      return requireOperation(operation.scope().operationId());
    }
    if (applyResult.status() == ApplyStatus.AMBIGUOUS) {
      journal.recordAmbiguousEffectForDispatch(
          dispatched, applyResult.outcomeCode(), clock.instant());
      return requireOperation(operation.scope().operationId());
    }
    return readBackEffect(operation, dispatched);
  }

  private Operation readBackEffect(Operation operation, OutboxEntry outbox) {
    UUID attemptId = outbox.lastAttemptId();
    if (attemptId == null) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: dispatched effect has no attempt identity");
    }
    EffectReadbackResolution resolution;
    try {
      resolution = effectPort.readback(outbox.effect(), attemptId, outbox.attemptCount());
    } catch (RuntimeException exception) {
      return requireOperation(operation.scope().operationId());
    }
    if (resolution.status() != InitialGameplayReleaseEffectPort.ReadbackStatus.AVAILABLE) {
      return requireOperation(operation.scope().operationId());
    }
    EffectReadback readback = resolution.readback();
    Instant now = clock.instant();
    if (resolution.observedAt().isBefore(readback.observedAt())
        || resolution.observedAt().isAfter(now)
        || readback.observedAt().isAfter(now)) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: effect readback observation time changed");
    }
    return journal.recordEffectReadback(outbox, readback, now);
  }

  private Operation transition(
      UUID operationId,
      Phase expectedPhase,
      MaintenanceLockCredential maintenanceLock,
      String evidenceRef,
      TransitionVerb verb) {
    Operation operation = requireOperation(operationId);
    requireLock(operation, maintenanceLock);
    if (!operation.scope().evidenceRef().equals(evidenceRef)) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: evidence reference changed");
    }
    if (operation.evidence() == null) {
      throw new OwnerBoundaryUnavailableException("INITIAL_GAMEPLAY_RELEASE_EVIDENCE_NOT_READY");
    }
    PublicTransitionTuple tuple =
        new PublicTransitionTuple(
            operationId,
            tupleCodec.digest(operation.scope()),
            verb,
            expectedPhase,
            operation.maintenanceLockIdentity(),
            evidenceRef,
            operation.evidence().evidenceIdentity(),
            tupleCodec.digest(operation.evidence()));
    Optional<Operation> prior = journal.findTransition(tuple);
    if (prior.isPresent()) {
      return prior.orElseThrow();
    }
    try {
      verifyFreshEvidence(operation, evidenceProvider.read(operation.scope()));
    } catch (OwnerBoundaryUnavailableException
        | IllegalArgumentException
        | InitialGameplayReleaseConflictException evidenceFailure) {
      return journal.findTransition(tuple).orElseThrow(() -> evidenceFailure);
    }
    return journal.transition(tuple, clock.instant());
  }

  private void verifyFreshEvidence(Operation operation, EvidenceResolution resolution) {
    if (resolution.status() != ResolutionStatus.AVAILABLE) {
      throw unavailable("INITIAL_GAMEPLAY_RELEASE_EVIDENCE_" + resolution.status().name());
    }
    InitialGameplayReleaseEvidence currentEvidence = resolution.evidence();
    currentEvidence.validateFor(operation.scope());
    if (!operation.evidence().evidenceIdentity().equals(currentEvidence.evidenceIdentity())
        || !tupleCodec.digest(operation.evidence()).equals(tupleCodec.digest(currentEvidence))) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: owner evidence changed after readiness was recorded");
    }
  }

  private Operation requireOperation(UUID operationId) {
    return journal
        .find(Objects.requireNonNull(operationId, "operationId must not be null"))
        .orElseThrow(() -> new IllegalArgumentException("INITIAL_GAMEPLAY_RELEASE_NOT_FOUND"));
  }

  private static void requireLock(Operation operation, MaintenanceLockCredential maintenanceLock) {
    Objects.requireNonNull(maintenanceLock, "maintenanceLock must not be null");
    if (!operation.maintenanceLockIdentity().equals(maintenanceLock.identity())) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: maintenance-lock identity changed");
    }
  }

  private static OwnerBoundaryUnavailableException unavailable(String code) {
    return new OwnerBoundaryUnavailableException(code);
  }

  public record RecoveryCreated(Operation operation, MaintenanceLockCredential maintenanceLock) {
    public RecoveryCreated {
      Objects.requireNonNull(operation, "operation must not be null");
      Objects.requireNonNull(maintenanceLock, "maintenanceLock must not be null");
      if (!operation.maintenanceLockIdentity().equals(maintenanceLock.identity())) {
        throw new IllegalArgumentException("issued lock does not match the stored operation");
      }
    }
  }
}
