package net.firedevops.firemud.recovery.journal;

import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.CommitOwnerAuthorizedHandoffEffect;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectKind;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PreparePlayableStateFencedEffect;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionTuple;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TransitionVerb;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TypedEffect;
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.requiredDigest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * PostgreSQL compare-and-set journal and transactional outbox for initial gameplay release only.
 */
public final class JooqInitialGameplayReleaseJournal implements InitialGameplayReleaseJournal {
  private static final String TRANSITION_TABLE =
      "recovery_control.initial_gameplay_release_transition";
  private static final String OUTBOX_TABLE = "recovery_control.initial_gameplay_release_outbox";

  private final DSLContext dsl;
  private final InitialGameplayReleaseTupleCodec tupleCodec;

  public JooqInitialGameplayReleaseJournal(
      DSLContext dsl, InitialGameplayReleaseTupleCodec tupleCodec) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.tupleCodec = Objects.requireNonNull(tupleCodec, "tupleCodec must not be null");
  }

  @Override
  public Operation create(Scope scope, UUID controllerIssuedLockIdentity) {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(
        controllerIssuedLockIdentity, "controllerIssuedLockIdentity must not be null");
    String scopeDigest = tupleCodec.digest(scope);
    JSONB scopePayload = JSONB.valueOf(tupleCodec.toJson(scope));
    JSONB bindingPayload = JSONB.valueOf(tupleCodec.toJson(scope.peerStorageBinding()));
    Instant now = Instant.now();
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute(
              """
                  INSERT INTO recovery_control.initial_gameplay_release_operation (
                      operation_id, operation_kind, target_namespace, playable_state_namespace_id,
                      peer_storage_binding, scope_payload, evidence_ref, scope_digest,
                      maintenance_lock_identity, phase, revision, created_at, updated_at
                  ) VALUES (?, 'initial_gameplay_release', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, 'collecting', 1, CAST(? AS timestamptz), CAST(? AS timestamptz))
                  ON CONFLICT (operation_id) DO NOTHING
                  """,
              scope.operationId(),
              scope.targetNamespace(),
              scope.playableStateNamespaceId(),
              bindingPayload,
              scopePayload,
              scope.evidenceRef(),
              scopeDigest,
              controllerIssuedLockIdentity,
              toOffsetDateTime(now),
              toOffsetDateTime(now));
          Operation operation = selectOperation(tx, scope.operationId(), true);
          requireSameScope(operation.scope(), scope, scopeDigest);
          return operation;
        });
  }

  @Override
  public Optional<Operation> find(UUID operationId) {
    Objects.requireNonNull(operationId, "operationId must not be null");
    return Optional.ofNullable(selectOperationOrNull(dsl, operationId, false));
  }

  @Override
  public Operation bindVerifiedEvidence(
      Scope scope,
      String scopeDigest,
      InitialGameplayReleaseEvidence evidence,
      String evidenceDigest,
      Instant now) {
    Objects.requireNonNull(scope, "scope must not be null");
    requiredDigest(scopeDigest, "scopeDigest");
    Objects.requireNonNull(evidence, "evidence must not be null");
    requiredDigest(evidenceDigest, "evidenceDigest");
    Objects.requireNonNull(now, "now must not be null");
    evidence.validateFor(scope);
    if (!scopeDigest.equals(tupleCodec.digest(scope))
        || !evidenceDigest.equals(tupleCodec.digest(evidence))) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: evidence tuple digest changed");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Operation current = selectOperation(tx, scope.operationId(), true);
          requireSameScope(current.scope(), scope, scopeDigest);
          if (current.evidence() != null) {
            if (evidenceDigest.equals(tupleCodec.digest(current.evidence()))
                && evidence.evidenceIdentity().equals(current.evidence().evidenceIdentity())) {
              return current;
            }
            throw conflict("owner evidence changed after it was bound");
          }
          if (current.phase() != Phase.COLLECTING) {
            throw conflict("operation is not collecting owner evidence");
          }
          int changed =
              tx.execute(
                  """
                  UPDATE recovery_control.initial_gameplay_release_operation
                  SET phase = ?, evidence_snapshot = CAST(? AS jsonb), evidence_identity = ?, evidence_digest = ?,
                      revision = revision + 1, updated_at = CAST(? AS timestamptz)
                  WHERE operation_id = ? AND phase = ? AND revision = ? AND evidence_snapshot IS NULL
                  """,
                  Phase.READY_TO_REOPEN.databaseValue(),
                  JSONB.valueOf(tupleCodec.toJson(evidence)),
                  evidence.evidenceIdentity(),
                  evidenceDigest,
                  toOffsetDateTime(now),
                  scope.operationId(),
                  Phase.COLLECTING.databaseValue(),
                  current.revision());
          if (changed != 1) {
            throw conflict("owner evidence compare-and-set lost");
          }
          return selectOperation(tx, scope.operationId(), false);
        });
  }

  @Override
  public Operation transition(PublicTransitionTuple tuple, Instant now) {
    Objects.requireNonNull(tuple, "tuple must not be null");
    Objects.requireNonNull(now, "now must not be null");
    String tupleDigest = tupleCodec.digest(tuple);
    JSONB tuplePayload = JSONB.valueOf(tupleCodec.toJson(tuple));
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Operation current = selectOperation(tx, tuple.operationId(), true);
          requireTransitionMatchesOperation(tuple, current);
          Record previous =
              tx.fetchOne(
                  "SELECT tuple_digest FROM "
                      + TRANSITION_TABLE
                      + " WHERE operation_id = ? AND verb = ?",
                  tuple.operationId(),
                  tuple.verb().wireValue());
          if (previous != null) {
            if (!tupleDigest.equals(previous.get("tuple_digest", String.class))) {
              throw conflict("public transition tuple changed");
            }
            return current;
          }
          if (current.phase() != tuple.expectedPhase()) {
            throw conflict("public transition expected phase is stale");
          }
          tx.execute(
              """
                  INSERT INTO recovery_control.initial_gameplay_release_transition (
                      operation_id, verb, tuple_digest, tuple_payload, expected_phase, result_phase
                  ) VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
                  """,
              tuple.operationId(),
              tuple.verb().wireValue(),
              tupleDigest,
              tuplePayload,
              tuple.expectedPhase().databaseValue(),
              tuple.verb().resultPhase().databaseValue());
          int changed =
              tx.execute(
                  """
                  UPDATE recovery_control.initial_gameplay_release_operation
                  SET phase = ?, revision = revision + 1, updated_at = CAST(? AS timestamptz)
                  WHERE operation_id = ? AND phase = ? AND revision = ?
                  """,
                  tuple.verb().resultPhase().databaseValue(),
                  toOffsetDateTime(now),
                  tuple.operationId(),
                  tuple.expectedPhase().databaseValue(),
                  current.revision());
          if (changed != 1) {
            throw conflict("public transition compare-and-set lost");
          }
          Operation transitioned = selectOperation(tx, tuple.operationId(), false);
          if (tuple.verb() == TransitionVerb.RESUME) {
            enqueueEffect(tx, prepareEffect(transitioned), now);
          }
          return transitioned;
        });
  }

  @Override
  public Optional<Operation> findTransition(PublicTransitionTuple tuple) {
    Objects.requireNonNull(tuple, "tuple must not be null");
    Record previous =
        dsl.fetchOne(
            "SELECT o.*, t.tuple_digest AS transition_digest FROM recovery_control.initial_gameplay_release_operation o "
                + "JOIN "
                + TRANSITION_TABLE
                + " t ON t.operation_id=o.operation_id "
                + "WHERE o.operation_id=? AND t.verb=?",
            tuple.operationId(),
            tuple.verb().wireValue());
    if (previous == null) {
      return Optional.empty();
    }
    Operation operation = toOperation(previous);
    requireTransitionMatchesOperation(tuple, operation);
    if (!tupleCodec.digest(tuple).equals(previous.get("transition_digest", String.class))) {
      throw conflict("public transition tuple changed");
    }
    return Optional.of(operation);
  }

  @Override
  public Optional<OutboxEntry> findNextEffect(UUID operationId) {
    Record record =
        dsl.fetchOne(
            """
            SELECT outbox_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, state,
                   attempt_count, last_attempt_id, last_outcome_code, acknowledged_at
            FROM recovery_control.initial_gameplay_release_outbox
            WHERE operation_id = ? AND state <> 'acknowledged'
            ORDER BY created_at, outbox_id
            LIMIT 1
            """,
            operationId);
    return Optional.ofNullable(record).map(this::toOutboxEntry);
  }

  @Override
  public OutboxEntry claimEffect(UUID operationId, UUID effectId, UUID attemptId, Instant now) {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Operation operation = selectOperation(tx, operationId, true);
          OutboxEntry current = selectOutbox(tx, operationId, effectId, true);
          if (current.state() == EffectOutboxState.DISPATCHED
              || current.state() == EffectOutboxState.AMBIGUOUS
              || current.state() == EffectOutboxState.ACKNOWLEDGED) {
            return current;
          }
          requirePhaseForEffect(operation.phase(), current.effect().kind());
          int changed =
              tx.execute(
                  """
                  UPDATE recovery_control.initial_gameplay_release_outbox
                  SET state = 'dispatched', attempt_count = attempt_count + 1,
                      last_attempt_id = ?, last_dispatched_at = CAST(? AS timestamptz), last_outcome_code = NULL,
                      readback_identity = NULL, readback_payload = NULL, readback_digest = NULL,
                      updated_at = CAST(? AS timestamptz)
                  WHERE operation_id = ? AND effect_id = ? AND state IN ('pending', 'retryable')
                  """,
                  attemptId,
                  toOffsetDateTime(now),
                  toOffsetDateTime(now),
                  operationId,
                  effectId);
          if (changed != 1) {
            return selectOutbox(tx, operationId, effectId, false);
          }
          return selectOutbox(tx, operationId, effectId, false);
        });
  }

  @Override
  public OutboxEntry recordRetryableEffect(
      UUID operationId, UUID effectId, UUID attemptId, String outcomeCode, Instant now) {
    return recordNonterminalEffectOutcome(
        operationId, effectId, attemptId, outcomeCode, EffectOutboxState.RETRYABLE, now);
  }

  @Override
  public OutboxEntry recordAmbiguousEffect(
      UUID operationId, UUID effectId, UUID attemptId, String outcomeCode, Instant now) {
    return recordNonterminalEffectOutcome(
        operationId, effectId, attemptId, outcomeCode, EffectOutboxState.AMBIGUOUS, now);
  }

  private OutboxEntry recordNonterminalEffectOutcome(
      UUID operationId,
      UUID effectId,
      UUID attemptId,
      String outcomeCode,
      EffectOutboxState resultState,
      Instant now) {
    InitialGameplayReleaseModel.requiredOutcomeCode(outcomeCode);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          OutboxEntry current = selectOutbox(tx, operationId, effectId, true);
          if (current.lastAttemptId() != null && !current.lastAttemptId().equals(attemptId)) {
            throw conflict("effect attempt identity changed");
          }
          if (current.state() == resultState || current.state() == EffectOutboxState.ACKNOWLEDGED) {
            return current;
          }
          if (current.state() != EffectOutboxState.DISPATCHED
              && !(current.state() == EffectOutboxState.AMBIGUOUS
                  && resultState == EffectOutboxState.RETRYABLE)) {
            throw conflict("effect is not in a dispatchable attempt");
          }
          tx.execute(
              """
                  UPDATE recovery_control.initial_gameplay_release_outbox
                  SET state = ?, last_outcome_code = ?, updated_at = CAST(? AS timestamptz)
                  WHERE operation_id = ? AND effect_id = ? AND state IN ('dispatched', 'ambiguous')
                      AND last_attempt_id = ?
                  """,
              resultState.databaseValue(),
              outcomeCode,
              toOffsetDateTime(now),
              operationId,
              effectId,
              attemptId);
          return selectOutbox(tx, operationId, effectId, false);
        });
  }

  @Override
  public Operation recordEffectReadback(
      UUID operationId, UUID effectId, UUID attemptId, EffectReadback readback, Instant now) {
    Objects.requireNonNull(readback, "readback must not be null");
    Objects.requireNonNull(now, "now must not be null");
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Operation operation = selectOperation(tx, operationId, true);
          OutboxEntry current = selectOutbox(tx, operationId, effectId, true);
          requireReadbackMatches(readback, current, operationId, effectId);
          String readbackDigest = tupleCodec.digest(readback);
          if (current.state() == EffectOutboxState.ACKNOWLEDGED) {
            Record stored =
                tx.fetchOne(
                    "SELECT readback_digest FROM "
                        + OUTBOX_TABLE
                        + " WHERE operation_id = ? AND effect_id = ?",
                    operationId,
                    effectId);
            if (current.lastAttemptId() != null
                && current.lastAttemptId().equals(attemptId)
                && stored != null
                && readbackDigest.equals(stored.get("readback_digest", String.class))) {
              return operation;
            }
            throw conflict("effect readback changed after acknowledgement");
          }
          if (current.lastAttemptId() == null || !current.lastAttemptId().equals(attemptId)) {
            throw conflict("effect readback belongs to another attempt");
          }
          if (current.state() != EffectOutboxState.DISPATCHED
              && current.state() != EffectOutboxState.AMBIGUOUS) {
            throw conflict("effect is not awaiting readback reconciliation");
          }
          Record timestamps =
              tx.fetchOne(
                  "SELECT last_dispatched_at FROM "
                      + OUTBOX_TABLE
                      + " WHERE operation_id = ? AND effect_id = ?",
                  operationId,
                  effectId);
          if (timestamps == null) {
            throw conflict("effect dispatch timestamp is unavailable");
          }
          OffsetDateTime dispatchTime = timestamps.get("last_dispatched_at", OffsetDateTime.class);
          if (dispatchTime == null) {
            throw conflict("effect dispatch timestamp is unavailable");
          }
          Instant dispatchedAt = dispatchTime.toInstant();
          if (readback.observedAt().isBefore(dispatchedAt)) {
            throw conflict("effect readback is stale");
          }
          if (readback.state() == EffectReadbackState.UNKNOWN_OR_PARTIAL) {
            setReadbackState(
                tx, current, readback, readbackDigest, EffectOutboxState.AMBIGUOUS, now);
            return operation;
          }
          if (readback.state() == EffectReadbackState.PROVEN_NO_EFFECT) {
            setReadbackState(
                tx, current, readback, readbackDigest, EffectOutboxState.RETRYABLE, now);
            return operation;
          }
          requireSuccessfulReadbackState(readback, current.effect().kind());
          setReadbackState(
              tx, current, readback, readbackDigest, EffectOutboxState.ACKNOWLEDGED, now);
          if (current.effect().kind() == EffectKind.PREPARE_PLAYABLE_STATE_FENCED) {
            if (operation.phase() != Phase.RESUME_AUTHORIZED) {
              throw conflict("preparation effect is outside the authorized phase");
            }
            updatePhase(tx, operation, Phase.RELEASING, now, null);
            enqueueEffect(tx, handoffEffect(operation), now);
          } else {
            if (operation.phase() != Phase.RELEASING) {
              throw conflict("handoff effect is outside the releasing phase");
            }
            updatePhase(tx, operation, Phase.FINALIZED, now, now);
          }
          return selectOperation(tx, operationId, false);
        });
  }

  @Override
  public Optional<OutboxEntry> findEffect(UUID operationId, EffectKind effectKind) {
    Record record =
        dsl.fetchOne(
            """
            SELECT outbox_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, state,
                   attempt_count, last_attempt_id, last_outcome_code, acknowledged_at
            FROM recovery_control.initial_gameplay_release_outbox
            WHERE operation_id = ? AND effect_kind = ?
            """,
            operationId,
            effectKind.databaseValue());
    return Optional.ofNullable(record).map(this::toOutboxEntry);
  }

  private void setReadbackState(
      DSLContext tx,
      OutboxEntry current,
      EffectReadback readback,
      String readbackDigest,
      EffectOutboxState state,
      Instant now) {
    boolean acknowledged = state == EffectOutboxState.ACKNOWLEDGED;
    tx.execute(
        """
            UPDATE recovery_control.initial_gameplay_release_outbox
            SET state = ?, last_outcome_code = ?, readback_identity = ?, readback_payload = CAST(? AS jsonb),
                readback_digest = ?, acknowledged_at = CAST(? AS timestamptz), updated_at = CAST(? AS timestamptz)
            WHERE operation_id = ? AND effect_id = ? AND last_attempt_id = ?
            """,
        state.databaseValue(),
        readback.state().name(),
        readback.readbackIdentity(),
        JSONB.valueOf(tupleCodec.toJson(readback)),
        readbackDigest,
        acknowledged ? toOffsetDateTime(now) : null,
        toOffsetDateTime(now),
        readback.operationId(),
        readback.effectId(),
        current.lastAttemptId());
  }

  private void updatePhase(
      DSLContext tx, Operation operation, Phase result, Instant now, Instant finalizedAt) {
    int changed =
        tx.execute(
            """
            UPDATE recovery_control.initial_gameplay_release_operation
            SET phase = ?, revision = revision + 1, updated_at = CAST(? AS timestamptz), finalized_at = CAST(? AS timestamptz)
            WHERE operation_id = ? AND phase = ? AND revision = ?
            """,
            result.databaseValue(),
            toOffsetDateTime(now),
            toOffsetDateTime(finalizedAt),
            operation.scope().operationId(),
            operation.phase().databaseValue(),
            operation.revision());
    if (changed != 1) {
      throw conflict("effect phase compare-and-set lost");
    }
  }

  private void enqueueEffect(DSLContext tx, TypedEffect effect, Instant now) {
    String payload = tupleCodec.toJson(effect);
    String payloadDigest = tupleCodec.digest(effect);
    tx.execute(
        """
            INSERT INTO recovery_control.initial_gameplay_release_outbox (
                outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest,
                payload_digest, payload, state, attempt_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'pending', 0, CAST(? AS timestamptz), CAST(? AS timestamptz))
            """,
        UUID.randomUUID(),
        effect.operationId(),
        effect.effectId(),
        effect.kind().databaseValue(),
        effect.operationTupleDigest(),
        payloadDigest,
        JSONB.valueOf(payload),
        toOffsetDateTime(now),
        toOffsetDateTime(now));
  }

  private TypedEffect prepareEffect(Operation operation) {
    return new PreparePlayableStateFencedEffect(
        operation.scope().operationId(),
        UUID.randomUUID(),
        operation.maintenanceLockIdentity(),
        operation.scope(),
        operationTupleDigest(operation),
        operation.evidence().evidenceIdentity());
  }

  private TypedEffect handoffEffect(Operation operation) {
    return new CommitOwnerAuthorizedHandoffEffect(
        operation.scope().operationId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        operation.maintenanceLockIdentity(),
        operation.scope(),
        operationTupleDigest(operation),
        operation.evidence().evidenceIdentity());
  }

  private String operationTupleDigest(Operation operation) {
    return tupleCodec.operationTupleDigest(
        operation.scope(), operation.maintenanceLockIdentity(), operation.evidence());
  }

  private Operation selectOperation(DSLContext context, UUID operationId, boolean forUpdate) {
    Operation operation = selectOperationOrNull(context, operationId, forUpdate);
    if (operation == null) {
      throw new IllegalArgumentException("INITIAL_GAMEPLAY_RELEASE_NOT_FOUND");
    }
    return operation;
  }

  private Operation selectOperationOrNull(DSLContext context, UUID operationId, boolean forUpdate) {
    Record record =
        context.fetchOne(
            """
            SELECT operation_id, operation_kind, target_namespace, playable_state_namespace_id,
                   peer_storage_binding, scope_payload, evidence_ref, scope_digest,
                   maintenance_lock_identity, phase, revision, evidence_snapshot,
                   evidence_identity, evidence_digest, created_at, updated_at, finalized_at
            FROM recovery_control.initial_gameplay_release_operation
            WHERE operation_id = ?
            """
                + (forUpdate ? " FOR UPDATE" : ""),
            operationId);
    return record == null ? null : toOperation(record);
  }

  private Operation toOperation(Record record) {
    JSONB scopeJson = record.get("scope_payload", JSONB.class);
    JSONB evidenceJson = record.get("evidence_snapshot", JSONB.class);
    JSONB bindingJson = record.get("peer_storage_binding", JSONB.class);
    Scope scope = tupleCodec.readScope(scopeJson.data());
    if (!"initial_gameplay_release".equals(record.get("operation_kind", String.class))
        || !scope.targetNamespace().equals(record.get("target_namespace", String.class))
        || !scope
            .playableStateNamespaceId()
            .equals(record.get("playable_state_namespace_id", UUID.class))
        || !scope.evidenceRef().equals(record.get("evidence_ref", String.class))
        || !scope.peerStorageBinding().equals(tupleCodec.readPeerStorageBinding(bindingJson.data()))
        || !tupleCodec.digest(scope).equals(record.get("scope_digest", String.class))) {
      throw new IllegalStateException("Persisted initial gameplay release scope is inconsistent");
    }
    InitialGameplayReleaseEvidence evidence =
        evidenceJson == null ? null : tupleCodec.readEvidence(evidenceJson.data());
    if (evidence != null
        && (!evidence.evidenceIdentity().equals(record.get("evidence_identity", String.class))
            || !tupleCodec.digest(evidence).equals(record.get("evidence_digest", String.class)))) {
      throw new IllegalStateException(
          "Persisted initial gameplay release evidence is inconsistent");
    }
    return new Operation(
        scope,
        record.get("maintenance_lock_identity", UUID.class),
        Phase.fromDatabaseValue(record.get("phase", String.class)),
        record.get("revision", Long.class),
        evidence,
        toInstant(record.get("created_at", OffsetDateTime.class)),
        toInstant(record.get("updated_at", OffsetDateTime.class)),
        toInstant(record.get("finalized_at", OffsetDateTime.class)));
  }

  private OutboxEntry selectOutbox(
      DSLContext context, UUID operationId, UUID effectId, boolean forUpdate) {
    Record record =
        context.fetchOne(
            """
            SELECT outbox_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, state,
                   attempt_count, last_attempt_id, last_outcome_code, acknowledged_at
            FROM recovery_control.initial_gameplay_release_outbox
            WHERE operation_id = ? AND effect_id = ?
            """
                + (forUpdate ? " FOR UPDATE" : ""),
            operationId,
            effectId);
    if (record == null) {
      throw new IllegalArgumentException("INITIAL_GAMEPLAY_RELEASE_EFFECT_NOT_FOUND");
    }
    return toOutboxEntry(record);
  }

  private OutboxEntry toOutboxEntry(Record record) {
    JSONB payload = record.get("payload", JSONB.class);
    String effectKind = record.get("effect_kind", String.class);
    Instant acknowledgedAt = toInstant(record.get("acknowledged_at", OffsetDateTime.class));
    TypedEffect effect = tupleCodec.readEffect(effectKind, payload.data());
    if (!tupleCodec.digest(effect).equals(record.get("payload_digest", String.class))
        || !effect
            .operationTupleDigest()
            .equals(record.get("operation_tuple_digest", String.class))) {
      throw new IllegalStateException("Persisted initial gameplay release effect is inconsistent");
    }
    return new OutboxEntry(
        record.get("outbox_id", UUID.class),
        effect,
        record.get("payload_digest", String.class),
        EffectOutboxState.fromDatabaseValue(record.get("state", String.class)),
        record.get("attempt_count", Integer.class),
        record.get("last_attempt_id", UUID.class),
        record.get("last_outcome_code", String.class),
        acknowledgedAt);
  }

  private void requireSameScope(Scope actual, Scope requested, String expectedDigest) {
    if (!actual.equals(requested) || !expectedDigest.equals(tupleCodec.digest(actual))) {
      throw conflict("operation scope tuple changed");
    }
  }

  private void requireTransitionMatchesOperation(PublicTransitionTuple tuple, Operation operation) {
    if (!tuple.operationId().equals(operation.scope().operationId())
        || !tuple.scopeDigest().equals(tupleCodec.digest(operation.scope()))
        || !tuple.maintenanceLockIdentity().equals(operation.maintenanceLockIdentity())
        || !tuple.evidenceRef().equals(operation.scope().evidenceRef())
        || operation.evidence() == null
        || !tuple.evidenceIdentity().equals(operation.evidence().evidenceIdentity())
        || !tuple.evidenceDigest().equals(tupleCodec.digest(operation.evidence()))) {
      throw conflict("public transition tuple does not match the operation-owned tuple");
    }
  }

  private void requireReadbackMatches(
      EffectReadback readback, OutboxEntry outbox, UUID operationId, UUID effectId) {
    if (!readback.operationId().equals(operationId)
        || !readback.operationId().equals(outbox.effect().operationId())
        || !readback.effectId().equals(effectId)
        || !readback.attemptId().equals(outbox.lastAttemptId())
        || readback.effectKind() != outbox.effect().kind()
        || !readback.operationTupleDigest().equals(outbox.effect().operationTupleDigest())
        || !readback.payloadDigest().equals(outbox.payloadDigest())) {
      throw conflict("effect readback does not match the exact dispatched tuple");
    }
  }

  private void requireSuccessfulReadbackState(EffectReadback readback, EffectKind effectKind) {
    EffectReadbackState expected =
        effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED
            ? EffectReadbackState.PREPARED_FENCED
            : EffectReadbackState.OWNER_HANDOFF_APPLIED;
    if (readback.state() != expected) {
      throw conflict("effect readback did not prove its exact durable outcome");
    }
  }

  private void requirePhaseForEffect(Phase phase, EffectKind effectKind) {
    Phase expected =
        effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED
            ? Phase.RESUME_AUTHORIZED
            : Phase.RELEASING;
    if (phase != expected) {
      throw conflict("effect is not authorized in the current owner phase");
    }
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime toOffsetDateTime(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static InitialGameplayReleaseConflictException conflict(String reason) {
    return new InitialGameplayReleaseConflictException("IDEMPOTENCY_CONFLICT: " + reason);
  }
}
