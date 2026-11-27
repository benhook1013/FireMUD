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
import static net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionResult;
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
import org.jooq.conf.ParamType;
import org.jooq.conf.Settings;
import org.jooq.conf.StatementType;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    Objects.requireNonNull(dsl, "dsl must not be null");
    Settings journalSettings = (Settings) dsl.configuration().settings().clone();
    journalSettings.setExecuteLogging(false);
    journalSettings.setStatementType(StatementType.PREPARED_STATEMENT);
    journalSettings.setParamType(ParamType.INDEXED);
    this.dsl = DSL.using(dsl.configuration().derive(journalSettings));
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
    try {
      return dsl.transactionResult(configuration -> {
        DSLContext tx = DSL.using(configuration);
        requireRedactedDatabaseLogging(tx);
        tx.execute(
            """
            INSERT INTO recovery_control.initial_gameplay_release_operation (
                operation_id, operation_kind, target_namespace, playable_state_namespace_id,
                peer_storage_binding, scope_payload, evidence_ref, scope_digest,
                maintenance_lock_identity, phase, revision, created_at, updated_at
            ) VALUES (?, 'initial_gameplay_release', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, 'collecting', 1, CAST(? AS timestamptz), CAST(? AS timestamptz))
            ON CONFLICT DO NOTHING
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
        Operation operation = selectOperationOrNull(tx, scope.operationId(), true);
        if (operation == null) {
          throw conflict("maintenance-lock identity is already bound");
        }
        requireSameScope(operation.scope(), scope, scopeDigest);
        return requireFinalizedHandoff(tx, operation);
      });
    } catch (DuplicateKeyException failure) {
      // Auto-configured jOOQ may translate the same violation at the Spring boundary.
      throw conflict("maintenance-lock identity is already bound");
    } catch (DataAccessException failure) {
      if ("23505".equals(failure.sqlState())) {
        // PostgreSQL's unique-key detail contains the credential. Never retain its cause.
        throw conflict("maintenance-lock identity is already bound");
      }
      // Other database failures can include the rejected row as well.
      throw new IllegalStateException("Unable to create the recovery journal operation");
    } catch (org.springframework.dao.DataAccessException failure) {
      throw new IllegalStateException("Unable to create the recovery journal operation");
    }
  }

  private static void requireRedactedDatabaseLogging(DSLContext tx) {
    // Check the same PostgreSQL session before any statement binds the lock credential.
    Record settings =
        tx.fetchOne(
            """
            SELECT current_setting('log_error_verbosity') = 'terse'
               AND current_setting('log_parameter_max_length_on_error') = '0'
               AND current_setting('log_parameter_max_length') = '0' AS redacted
            """);
    if (settings == null || !Boolean.TRUE.equals(settings.get("redacted", Boolean.class))) {
      throw new IllegalStateException("Recovery journal requires redacted PostgreSQL logs");
    }
  }

  @Override
  public Optional<Operation> find(UUID operationId) {
    Objects.requireNonNull(operationId, "operationId must not be null");
    return Optional.ofNullable(selectOperationOrNull(dsl, operationId, false))
        .map(operation -> requireFinalizedHandoff(dsl, operation));
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
    return dsl.transactionResult(configuration -> {
      DSLContext tx = DSL.using(configuration);
      Operation current = selectOperation(tx, scope.operationId(), true);
      requireSameScope(current.scope(), scope, scopeDigest);
      if (current.evidence() != null) {
        if (evidenceDigest.equals(tupleCodec.digest(current.evidence()))
            && evidence.evidenceIdentity().equals(current.evidence().evidenceIdentity())) {
          return requireFinalizedHandoff(tx, current);
        }
        throw conflict("owner evidence changed after it was bound");
      }
      if (current.phase() != Phase.COLLECTING) {
        throw conflict("operation is not collecting owner evidence");
      }
      requireRedactedDatabaseLogging(tx);
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
  public PublicTransitionResult transition(PublicTransitionTuple tuple, Instant now) {
    Objects.requireNonNull(tuple, "tuple must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireIndependentTransaction("public transition");
    String tupleDigest = tupleCodec.digest(tuple);
    JSONB tuplePayload = JSONB.valueOf(tupleCodec.toJson(tuple));
    var result = new PublicTransitionResult(tuple.operationId(), tuple.verb().resultPhase());
    return dsl.transactionResult(configuration -> {
      DSLContext tx = DSL.using(configuration);
      Operation current = selectOperation(tx, tuple.operationId(), true);
      requireTransitionMatchesOperation(tuple, current);
      Record previous =
          tx.fetchOne(
              "SELECT tuple_digest, tuple_payload, expected_phase, result_phase FROM "
                  + TRANSITION_TABLE
                  + " WHERE operation_id = ? AND verb = ?",
              tuple.operationId(),
              tuple.verb().wireValue());
      if (previous != null) {
        requireRetainedTransition(previous, tuple, tupleDigest);
        requireFinalizedHandoff(tx, current);
        return result;
      }
      if (current.phase() != tuple.expectedPhase()) {
        throw conflict("public transition expected phase is stale");
      }
      requireRedactedDatabaseLogging(tx);
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
      return result;
    });
  }

  @Override
  public Optional<PublicTransitionResult> findTransition(PublicTransitionTuple tuple) {
    Objects.requireNonNull(tuple, "tuple must not be null");
    Record previous =
        dsl.fetchOne(
            "SELECT o.*, t.tuple_digest, t.tuple_payload, t.expected_phase, t.result_phase FROM recovery_control.initial_gameplay_release_operation o "
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
    requireRetainedTransition(previous, tuple, tupleCodec.digest(tuple));
    requireFinalizedHandoff(dsl, operation);
    return Optional.of(new PublicTransitionResult(tuple.operationId(), tuple.verb().resultPhase()));
  }

  @Override
  public Optional<OutboxEntry> findNextEffect(UUID operationId) {
    // Surface an incomplete retained dispatch to the typed row validator before normal work.
    // The operation key bounds this selection; valid acknowledged rows still do not queue work.
    // Reconcile an existing handoff before a preparation hold. A hold can block the next
    // claim but cannot withdraw an existing claim or override the handoff's owner outcome.
    Record record =
        dsl.fetchOne(
            """
            WITH outbox AS (
              SELECT e.*,
                     CASE
                       WHEN e.state = 'retryable' AND (e.retry_provenance IS NULL OR
                            (e.retry_provenance = 'local_no_send' AND e.send_outcome_uncertain IS NOT FALSE))
                         THEN 'ambiguous'
                       WHEN o.phase = 'releasing' AND e.state = 'acknowledged'
                            AND e.effect_kind = 'prepare_playable_state_fenced'
                            AND e.preparation_observation_payload ->> 'state' = 'UNKNOWN_OR_PARTIAL'
                         THEN 'ambiguous'
                       ELSE e.state
                     END AS reconciliation_state
              FROM recovery_control.initial_gameplay_release_outbox e
              JOIN recovery_control.initial_gameplay_release_operation o USING (operation_id)
              WHERE e.operation_id = ?
            )
            SELECT outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, reconciliation_state AS state, attempt_count, last_attempt_id,
                   last_outcome_code, last_dispatched_at,
                   CASE WHEN reconciliation_state = 'acknowledged'
                        THEN acknowledged_at ELSE NULL END AS acknowledged_at
            FROM outbox
            WHERE reconciliation_state <> 'acknowledged' OR last_dispatched_at IS NULL
               OR attempt_count = 0 OR last_attempt_id IS NULL
               OR last_attempt_id = '00000000-0000-0000-0000-000000000000'::uuid
            ORDER BY CASE
                       WHEN reconciliation_state <> 'pending' AND
                            (last_dispatched_at IS NULL OR attempt_count = 0 OR last_attempt_id IS NULL
                             OR last_attempt_id = '00000000-0000-0000-0000-000000000000'::uuid)
                         THEN 0
                       WHEN effect_kind = 'commit_owner_authorized_handoff'
                            AND reconciliation_state IN ('dispatched', 'ambiguous')
                         THEN 1
                       WHEN effect_kind = 'prepare_playable_state_fenced'
                            AND reconciliation_state = 'ambiguous'
                         THEN 2
                       ELSE 3
                     END,
                     created_at, outbox_id
            LIMIT 1
            """,
            operationId);
    if (record == null) {
      // A finalized operation must still have an exact, durable handoff receipt.
      find(operationId);
      return Optional.empty();
    }
    return Optional.of(toOutboxEntry(record));
  }

  @Override
  public OutboxEntry claimEffect(UUID operationId, UUID effectId, UUID attemptId, Instant now) {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireIndependentTransaction("effect dispatch claim");
    return dsl.transactionResult(configuration -> {
      DSLContext tx = DSL.using(configuration);
      Operation operation = selectOperation(tx, operationId, true);
      OutboxEntry current = selectOutbox(tx, operationId, effectId, true);
      tupleCodec.requireEffectMatchesOperation(current.effect(), operation);
      if (hasRetainedClosure(tx, current, attemptId, null)
          || hasRetainedOwnerRejection(tx, current, attemptId, null)) {
        throw conflict("a retry must use a new effect attempt identity");
      }
      if (current.state() == EffectOutboxState.DISPATCHED
          && attemptId.equals(current.lastAttemptId())) {
        throw conflict("effect attempt is already claimed; read back the existing dispatch");
      }
      if (current.state() == EffectOutboxState.DISPATCHED
          || current.state() == EffectOutboxState.AMBIGUOUS
          || current.state() == EffectOutboxState.ACKNOWLEDGED) {
        return current;
      }
      if (current.state() == EffectOutboxState.RETRYABLE) {
        requireExactClosureBeforeRetry(tx, current);
      }
      requirePhaseForEffect(operation.phase(), current.effect().kind());
      if (current.effect().kind() == EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF) {
        requireAcknowledgedEffect(
            tx, operation, EffectKind.PREPARE_PLAYABLE_STATE_FENCED, now, true);
      }
      requireRedactedDatabaseLogging(tx);
      int changed =
          tx.execute(
              """
              UPDATE recovery_control.initial_gameplay_release_outbox
              SET state = 'dispatched', attempt_count = attempt_count + 1,
                  last_attempt_id = ?, last_dispatched_at = CAST(? AS timestamptz), last_outcome_code = NULL,
                  readback_identity = NULL, readback_payload = NULL, readback_digest = NULL,
                  retry_provenance = NULL, send_outcome_uncertain = false,
                  updated_at = CAST(? AS timestamptz)
              WHERE operation_id = ? AND effect_id = ? AND state IN ('pending', 'retryable')
              """,
              attemptId,
              // PostgreSQL stores microseconds; truncate explicitly rather than rounding
              // the dispatch floor upward beyond an observation of this same instant.
              toOffsetDateTime(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
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
  public boolean recordRetryableEffectForDispatch(
      OutboxEntry dispatch, RetrySource source, String outcomeCode, Instant now) {
    Objects.requireNonNull(source, "retry source must not be null");
    return recordNonterminalEffectOutcome(
        dispatch, outcomeCode, EffectOutboxState.RETRYABLE, source, now);
  }

  @Override
  public boolean recordAmbiguousEffectForDispatch(
      OutboxEntry dispatch, String outcomeCode, Instant now) {
    return recordNonterminalEffectOutcome(
        dispatch, outcomeCode, EffectOutboxState.AMBIGUOUS, null, now);
  }

  private boolean recordNonterminalEffectOutcome(
      OutboxEntry dispatch,
      String outcomeCode,
      EffectOutboxState resultState,
      RetrySource retrySource,
      Instant now) {
    Objects.requireNonNull(dispatch, "dispatch must not be null");
    InitialGameplayReleaseModel.requiredOutcomeCode(outcomeCode);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    UUID attemptId = dispatch.lastAttemptId();
    return dsl.transactionResult(configuration -> {
      DSLContext tx = DSL.using(configuration);
      Operation operation = selectOperation(tx, operationId, true);
      OutboxEntry current = selectOutbox(tx, operationId, effectId, true);
      tupleCodec.requireEffectMatchesOperation(current.effect(), operation);
      if (dispatch.state() != EffectOutboxState.DISPATCHED
          || attemptId == null
          || !sameEffect(dispatch, current)) {
        throw conflict("effect dispatch tuple changed");
      }
      if (!Objects.equals(current.lastAttemptId(), attemptId)) {
        if (hasRetainedClosure(tx, current, attemptId, dispatch.attemptCount())
            || hasRetainedOwnerRejection(tx, current, attemptId, dispatch.attemptCount())) {
          return false;
        }
        throw conflict("effect attempt identity changed");
      }
      if (dispatch.attemptCount() != current.attemptCount()) {
        throw conflict("effect dispatch generation changed");
      }
      if (current.state() == EffectOutboxState.ACKNOWLEDGED
          || isPreparationHeldBeforeHandoff(operation, current)
          || (current.lastAttemptId() != null
              && current.state() == EffectOutboxState.RETRYABLE
              && resultState == EffectOutboxState.AMBIGUOUS)) {
        return true;
      }
      if (current.state() != resultState
          && current.state() != EffectOutboxState.DISPATCHED
          && !(current.state() == EffectOutboxState.AMBIGUOUS
              && resultState == EffectOutboxState.RETRYABLE)) {
        throw conflict("effect is not in a dispatchable attempt");
      }
      if (current.state() == resultState) {
        if (resultState == EffectOutboxState.AMBIGUOUS) {
          requireRedactedDatabaseLogging(tx);
          tx.execute(
              "UPDATE " + OUTBOX_TABLE + " SET send_outcome_uncertain = true WHERE outbox_id = ?",
              current.outboxId());
        }
        if (retrySource != null) {
          Record origin =
              tx.fetchOne(
                  "SELECT retry_provenance FROM " + OUTBOX_TABLE + " WHERE outbox_id = ?",
                  current.outboxId());
          String retainedSource =
              origin == null ? null : origin.get("retry_provenance", String.class);
          if ("owner_closure".equals(retainedSource)) {
            requireExactClosureBeforeRetry(tx, current);
            return false;
          }
          if (!retryProvenance(retrySource).equals(retainedSource)) {
            throw conflict("effect retry source changed for the exact dispatch");
          }
          requireExactClosureBeforeRetry(tx, current);
        }
        return true;
      }
      requireRedactedDatabaseLogging(tx);
      OffsetDateTime recordedAt = toOffsetDateTime(now);
      if (retrySource == RetrySource.NOT_DISPATCHED) {
        Record stored =
            tx.fetchOne(
                "SELECT send_outcome_uncertain, last_dispatched_at,"
                    + " readback_payload, readback_digest, readback_identity"
                    + " FROM "
                    + OUTBOX_TABLE
                    + " WHERE outbox_id = ?",
                current.outboxId());
        if (stored == null
            || !Boolean.FALSE.equals(stored.get("send_outcome_uncertain", Boolean.class))
            || (current.state() == EffectOutboxState.AMBIGUOUS
                ? !hasValidatedUnknownReadback(
                    stored, stored.get("readback_digest", String.class), current)
                : stored.get("readback_payload", JSONB.class) != null
                    || stored.get("readback_digest", String.class) != null
                    || stored.get("readback_identity", String.class) != null)) {
          throw conflict("local no-send proof requires the exact currently dispatched attempt");
        }
        OffsetDateTime dispatchedAt = stored.get("last_dispatched_at", OffsetDateTime.class);
        if (dispatchedAt == null) {
          throw conflict("effect dispatch timestamp is unavailable");
        }
        if (recordedAt.isBefore(dispatchedAt)) {
          recordedAt = dispatchedAt;
        }
        int retained =
            tx.execute(
                """
                INSERT INTO recovery_control.initial_gameplay_release_attempt_local_no_send
                    (outbox_id, attempt_id, attempt_ordinal, dispatched_at, recorded_at)
                SELECT outbox_id, last_attempt_id, attempt_count, last_dispatched_at,
                       CAST(? AS timestamptz)
                FROM recovery_control.initial_gameplay_release_outbox
                WHERE outbox_id = ? AND state IN ('dispatched', 'ambiguous') AND last_attempt_id = ?
                  AND attempt_count = ? AND last_dispatched_at <= CAST(? AS timestamptz)
                  AND send_outcome_uncertain = false
                """,
                recordedAt,
                current.outboxId(),
                attemptId,
                current.attemptCount(),
                recordedAt);
        if (retained != 1) {
          throw conflict("local no-send proof did not match the exact dispatch");
        }
      }
      if (retrySource == RetrySource.OWNER_REJECTED) {
        tx.execute(
            """
            INSERT INTO recovery_control.initial_gameplay_release_attempt_rejection
                (outbox_id, attempt_id, attempt_ordinal)
            VALUES (?, ?, ?)
            ON CONFLICT (outbox_id, attempt_id) DO NOTHING
            """,
            current.outboxId(),
            attemptId,
            dispatch.attemptCount());
      }
      int changed =
          tx.execute(
              """
              UPDATE recovery_control.initial_gameplay_release_outbox
              SET state = ?, last_outcome_code = ?, retry_provenance = ?,
                  send_outcome_uncertain = CASE WHEN ? THEN true ELSE send_outcome_uncertain END,
                  updated_at = CAST(? AS timestamptz)
              WHERE operation_id = ? AND effect_id = ? AND state IN ('dispatched', 'ambiguous')
                  AND last_attempt_id = ?
              """,
              resultState.databaseValue(),
              outcomeCode,
              retrySource == null ? null : retryProvenance(retrySource),
              resultState == EffectOutboxState.AMBIGUOUS,
              recordedAt,
              operationId,
              effectId,
              attemptId);
      if (changed != 1) {
        throw conflict("effect outcome compare-and-set lost");
      }
      selectOutbox(tx, operationId, effectId, false);
      return true;
    });
  }

  private static String retryProvenance(RetrySource source) {
    return switch (source) {
      case NOT_DISPATCHED -> "local_no_send";
      case OWNER_REJECTED -> "owner_rejected";
    };
  }

  @Override
  public Operation recordEffectReadback(
      OutboxEntry dispatch, EffectReadback readback, Instant now) {
    Objects.requireNonNull(dispatch, "dispatch must not be null");
    Objects.requireNonNull(readback, "readback must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireIndependentTransaction("effect readback");
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    return dsl.transactionResult(configuration -> {
      DSLContext tx = DSL.using(configuration);
      Operation operation = requireFinalizedHandoff(tx, selectOperation(tx, operationId, true));
      OutboxEntry current = selectOutbox(tx, operationId, effectId, true);
      tupleCodec.requireEffectMatchesOperation(current.effect(), operation);
      if (!sameEffect(dispatch, current)
          || (dispatch.state() != EffectOutboxState.DISPATCHED
              && dispatch.state() != EffectOutboxState.AMBIGUOUS)) {
        throw conflict("effect readback dispatch tuple changed");
      }
      if (dispatch.lastAttemptId() == null) {
        throw conflict("effect readback belongs to another attempt");
      }
      if (!dispatch.lastAttemptId().equals(current.lastAttemptId())) {
        RetainedClosure retainedClosure =
            findRetainedClosure(tx, current, dispatch.lastAttemptId(), dispatch.attemptCount());
        if (retainedClosure == null) {
          throw conflict("effect readback belongs to another attempt");
        }
        if (!sameRetainedOutcome(retainedClosure.readback(), readback)
            || readback.observedAt().isAfter(now)) {
          throw conflict("effect readback changed after definitive no effect");
        }
        if (readback.observedAt().isBefore(retainedClosure.dispatchedAt())) {
          throw conflict("effect readback is stale");
        }
        return operation;
      }
      String readbackDigest = tupleCodec.digest(readback);
      if (dispatch.attemptCount() != current.attemptCount()) {
        throw conflict("effect readback dispatch generation changed");
      }
      requireReadbackMatches(readback, current, operationId, effectId);
      if (current.state() == EffectOutboxState.ACKNOWLEDGED
          || current.state() == EffectOutboxState.RETRYABLE) {
        Record stored =
            tx.fetchOne(
                "SELECT readback_digest, readback_payload, readback_identity, last_dispatched_at,"
                    + " retry_provenance, updated_at, acknowledged_at, send_outcome_uncertain,"
                    + " preparation_observation_payload, preparation_observation_digest FROM "
                    + OUTBOX_TABLE
                    + " WHERE operation_id = ? AND effect_id = ?",
                operationId,
                effectId);
        String storedDigest = stored == null ? null : stored.get("readback_digest", String.class);
        if (current.state() == EffectOutboxState.ACKNOWLEDGED) {
          if (readbackDigest.equals(storedDigest)) {
            requireSuccessfulReadbackState(readback, current.effect().kind());
            requireValidExactStoredReadback(readback, stored, storedDigest, current);
            requireRetainedReadbackRecordingTime(readback, stored, "acknowledged_at");
            requireTerminalReplayWithinAttempt(readback, stored, now);
            return operation;
          }
          if (operation.phase() == Phase.RELEASING
              && current.effect().kind() == EffectKind.PREPARE_PLAYABLE_STATE_FENCED) {
            retainPreparationObservation(tx, current, stored, readback, readbackDigest, now);
            return operation;
          }
          if (operation.phase() == Phase.FINALIZED
              && current.effect().kind() == EffectKind.PREPARE_PLAYABLE_STATE_FENCED
              && readback.state() == EffectReadbackState.PREPARED_FENCED
              && stored != null
              && readback.equals(
                  latestPreparationObservation(
                      tx, stored, current, requireReadbackReceipt(stored, current)))) {
            requireTerminalReplayWithinAttempt(readback, stored, now);
            return operation;
          }
          if (current.effect().kind() == EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF
              && stored != null
              && sameRetainedOutcome(readback, stored, storedDigest, "acknowledged_at")) {
            requireSuccessfulReadbackState(readback, current.effect().kind());
            requireTerminalReplayWithinAttempt(readback, stored, now);
            return operation;
          }
          throw conflict("effect readback changed after acknowledgement");
        }
        if (readback.state() == EffectReadbackState.PROVEN_NO_EFFECT
            && readbackDigest.equals(storedDigest)) {
          requireValidExactStoredReadback(readback, stored, storedDigest, current);
          requireRetainedClosureMatchesStoredReadback(tx, current, stored, storedDigest);
          requireTerminalReplayWithinAttempt(readback, stored, now);
          recoverOwnerClosureProvenance(tx, current);
          return operation;
        }
        if (readback.state() == EffectReadbackState.PROVEN_NO_EFFECT
            && stored != null
            && sameRetainedOutcome(readback, stored, storedDigest, "updated_at")) {
          requireRetainedClosureMatchesStoredReadback(tx, current, stored, storedDigest);
          requireTerminalReplayWithinAttempt(readback, stored, now);
          recoverOwnerClosureProvenance(tx, current);
          return operation;
        }
        String provenance = stored == null ? null : stored.get("retry_provenance", String.class);
        boolean heldUntyped = stored != null && provenance == null;
        boolean typedOwnerRejection = "owner_rejected".equals(provenance);
        boolean partial = readback.state() == EffectReadbackState.UNKNOWN_OR_PARTIAL;
        boolean typedLocalNoSend = "local_no_send".equals(provenance);
        boolean heldLocalNoSend =
            partial
                && heldUntyped
                && hasRetainedLocalNoSend(
                    tx, current, stored.get("last_dispatched_at", OffsetDateTime.class));
        boolean partialAfterLocalNoSend = partial && (typedLocalNoSend || heldLocalNoSend);
        boolean partialAfterOwnerRejection =
            partial
                && (typedOwnerRejection
                    || (heldUntyped
                        && hasRetainedOwnerRejection(
                            tx, current, readback.attemptId(), current.attemptCount())));
        if (partialAfterLocalNoSend || partialAfterOwnerRejection) {
          requireTerminalReplayWithinAttempt(readback, stored, now);
          if (typedOwnerRejection
              || (typedLocalNoSend
                  && Boolean.FALSE.equals(stored.get("send_outcome_uncertain", Boolean.class)))) {
            requireExactClosureBeforeRetry(tx, current);
          } else if (hasRetainedClosure(
              tx, current, readback.attemptId(), current.attemptCount())) {
            throw conflict("effect readback changed after definitive no effect");
          }
          requireNonRegressingReadback(readback, stored, current);
          if (!readbackDigest.equals(storedDigest)) {
            requireRedactedDatabaseLogging(tx);
            // Keep the observation floor without changing the original retry provenance or hold.
            tx.execute(
                """
                UPDATE recovery_control.initial_gameplay_release_outbox
                SET readback_identity = ?, readback_payload = CAST(? AS jsonb),
                    readback_digest = ?, updated_at = GREATEST(updated_at, CAST(? AS timestamptz))
                WHERE outbox_id = ?
                """,
                readback.readbackIdentity(),
                JSONB.valueOf(tupleCodec.toJson(readback)),
                readbackDigest,
                toOffsetDateTime(now),
                current.outboxId());
          }
          return operation;
        }
        boolean hasStoredReadback =
            stored != null
                && (storedDigest != null
                    || stored.get("readback_payload", JSONB.class) != null
                    || stored.get("readback_identity", String.class) != null);
        boolean conflictsWithRetainedOutcome =
            hasStoredReadback && !hasValidatedUnknownReadback(stored, storedDigest, current);
        // A V4 local no-send carried exact typed proof. V5's unknown transport status
        // holds reuse but does not make a contradictory applied outcome admissible.
        if (conflictsWithRetainedOutcome
            || (readback.state() != EffectReadbackState.PROVEN_NO_EFFECT
                && (!heldUntyped
                    || hasRetainedClosure(tx, current, readback.attemptId(), null)
                    || hasRetainedOwnerRejection(tx, current, readback.attemptId(), null)))) {
          throw conflict("effect readback changed after definitive no effect");
        }
        // A held retry keeps the same attempt and observation fences as an ambiguous dispatch.
        // Record partial evidence so a delayed definitive observation cannot advance it.
      }
      if (readback.observedAt().isAfter(now)) {
        throw conflict("effect readback observation is in the future");
      }
      if (current.state() != EffectOutboxState.DISPATCHED
          && current.state() != EffectOutboxState.AMBIGUOUS
          && current.state() != EffectOutboxState.RETRYABLE) {
        throw conflict("effect is not awaiting readback reconciliation");
      }
      Record timestamps =
          tx.fetchOne(
              "SELECT last_dispatched_at, readback_payload, readback_digest, readback_identity FROM"
                  + " "
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
      if (readback.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL
          && readback.state() != EffectReadbackState.PROVEN_NO_EFFECT
          && hasRetainedLocalNoSend(tx, current, dispatchTime)) {
        throw conflict("effect readback changed after definitive no effect");
      }
      EffectReadback previous = requireNonRegressingReadback(readback, timestamps, current);
      requireRedactedDatabaseLogging(tx);
      if (isPreparationHeldBeforeHandoff(operation, current)) {
        if (previous == null || previous.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL) {
          throw conflict("held preparation has no retained partial readback");
        }
        if (readback.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL) {
          requireSuccessfulReadbackState(readback, current.effect().kind());
          setReadbackState(
              tx, current, readback, readbackDigest, EffectOutboxState.ACKNOWLEDGED, now);
          return operation;
        }
      }
      if (readback.state() == EffectReadbackState.UNKNOWN_OR_PARTIAL) {
        setReadbackState(tx, current, readback, readbackDigest, EffectOutboxState.AMBIGUOUS, now);
        return operation;
      }
      if (readback.state() == EffectReadbackState.PROVEN_NO_EFFECT) {
        if (hasRetainedClosure(tx, current, readback.attemptId(), null)) {
          throw conflict("effect attempt closure already exists outside exact replay");
        }
        tx.execute(
            """
            INSERT INTO recovery_control.initial_gameplay_release_attempt_closure
                (outbox_id, attempt_id, attempt_ordinal, readback_payload, readback_digest, dispatched_at, recorded_at)
            VALUES (?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS timestamptz), CAST(? AS timestamptz))
            """,
            current.outboxId(),
            readback.attemptId(),
            current.attemptCount(),
            JSONB.valueOf(tupleCodec.toJson(readback)),
            readbackDigest,
            dispatchTime,
            toOffsetDateTime(now));
        setReadbackState(tx, current, readback, readbackDigest, EffectOutboxState.RETRYABLE, now);
        return operation;
      }
      requireSuccessfulReadbackState(readback, current.effect().kind());
      setReadbackState(tx, current, readback, readbackDigest, EffectOutboxState.ACKNOWLEDGED, now);
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

  private static boolean sameEffect(OutboxEntry dispatch, OutboxEntry current) {
    return dispatch.outboxId().equals(current.outboxId())
        && dispatch.effect().equals(current.effect())
        && dispatch.payloadDigest().equals(current.payloadDigest());
  }

  private void recoverOwnerClosureProvenance(DSLContext tx, OutboxEntry current) {
    requireRedactedDatabaseLogging(tx);
    tx.execute(
        "UPDATE "
            + OUTBOX_TABLE
            + " SET retry_provenance = 'owner_closure'"
            + " WHERE outbox_id = ? AND retry_provenance IS NULL",
        current.outboxId());
  }

  @Override
  public Optional<OutboxEntry> findEffect(UUID operationId, EffectKind effectKind) {
    Record record =
        dsl.fetchOne(
            """
            SELECT outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, state,
                   attempt_count, last_attempt_id, last_outcome_code, acknowledged_at, last_dispatched_at
            FROM recovery_control.initial_gameplay_release_outbox
            WHERE operation_id = ? AND effect_kind = ?
            """,
            operationId,
            effectKind.databaseValue());
    return Optional.ofNullable(record).map(this::toOutboxEntry);
  }

  private static boolean isPreparationHeldBeforeHandoff(Operation operation, OutboxEntry effect) {
    return operation.phase() == Phase.RELEASING
        && effect.effect().kind() == EffectKind.PREPARE_PLAYABLE_STATE_FENCED
        && effect.state() == EffectOutboxState.AMBIGUOUS;
  }

  // The operation lock serializes the observation floor with new handoff claims.
  private void retainPreparationObservation(
      DSLContext tx,
      OutboxEntry preparation,
      Record stored,
      EffectReadback readback,
      String readbackDigest,
      Instant now) {
    EffectReadback acknowledgement = requireReadbackReceipt(stored, preparation);
    EffectReadback previous =
        latestPreparationObservation(tx, stored, preparation, acknowledgement);
    requireTerminalReplayWithinAttempt(readback, stored, now);
    if (readback.equals(previous)) {
      return;
    }
    if (!readback.observedAt().isAfter(previous.observedAt())) {
      throw conflict(
          "preparation readback must be newer than the retained same-attempt observation");
    }
    requireRedactedDatabaseLogging(tx);
    if (readback.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL) {
      requireSuccessfulReadbackState(readback, preparation.effect().kind());
      if (acknowledgement.readbackIdentity().equals(readback.readbackIdentity())
          || tx.execute(
                  "INSERT INTO recovery_control.initial_gameplay_release_preparation_receipt"
                      + " (outbox_id, identity_digest) VALUES (?, ?) ON CONFLICT DO NOTHING",
                  preparation.outboxId(),
                  tupleCodec.digest(readback.readbackIdentity()))
              != 1) {
        throw conflict("preparation readback identity already has an observation");
      }
    }
    tx.execute(
        """
        UPDATE recovery_control.initial_gameplay_release_outbox
        SET preparation_observation_payload = CAST(? AS jsonb), preparation_observation_digest = ?,
            updated_at = GREATEST(updated_at, CAST(? AS timestamptz))
        WHERE outbox_id = ?
        """,
        JSONB.valueOf(tupleCodec.toJson(readback)),
        readbackDigest,
        toOffsetDateTime(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
        preparation.outboxId());
  }

  private EffectReadback latestPreparationObservation(
      DSLContext context, Record stored, OutboxEntry preparation, EffectReadback acknowledgement) {
    JSONB payload = stored.get("preparation_observation_payload", JSONB.class);
    String digest = stored.get("preparation_observation_digest", String.class);
    if (payload == null && digest == null) {
      return acknowledgement;
    }
    if (payload == null || digest == null) {
      throw conflict("retained preparation observation is incomplete");
    }
    EffectReadback observation = tupleCodec.readEffectReadback(payload.data());
    if (!digest.equals(tupleCodec.digest(observation))
        || !observation.observedAt().isAfter(acknowledgement.observedAt())) {
      throw conflict("retained preparation observation is inconsistent");
    }
    requireReadbackMatches(
        observation,
        preparation,
        preparation.effect().operationId(),
        preparation.effect().effectId());
    requireRetainedReadbackRecordingTime(observation, stored, "updated_at");
    if (observation.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL) {
      requireSuccessfulReadbackState(observation, preparation.effect().kind());
      if (acknowledgement.readbackIdentity().equals(observation.readbackIdentity())
          || context.fetchOne(
                  """
                  SELECT 1 FROM recovery_control.initial_gameplay_release_preparation_receipt
                  WHERE outbox_id = ? AND identity_digest = ?
                  """,
                  preparation.outboxId(),
                  tupleCodec.digest(observation.readbackIdentity()))
              == null) {
        throw conflict("retained preparation readback has no exact historical identity");
      }
    }
    return observation;
  }

  private void requireIndependentTransaction(String operation) {
    // A journal bound to a caller's transaction can release only a savepoint.
    // These entry points report authorization, dispatch custody, or a phase after commit.
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || !dsl.connectionResult(connection -> connection.getAutoCommit())) {
      throw conflict(operation + " requires its own transaction");
    }
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
        SET state = ?, last_outcome_code = ?, retry_provenance = ?, readback_identity = ?,
            readback_payload = CAST(? AS jsonb),
            readback_digest = ?, acknowledged_at = CAST(? AS timestamptz), updated_at = CAST(? AS timestamptz)
        WHERE operation_id = ? AND effect_id = ? AND last_attempt_id = ?
        """,
        state.databaseValue(),
        readback.state().name(),
        readback.state() == EffectReadbackState.PROVEN_NO_EFFECT ? "owner_closure" : null,
        readback.readbackIdentity(),
        JSONB.valueOf(tupleCodec.toJson(readback)),
        readbackDigest,
        acknowledged
            ? toOffsetDateTime(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS))
            : null,
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

  // The transition or readback has already qualified this transaction's PostgreSQL session.
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
        tupleCodec.operationTupleDigest(operation),
        operation.evidence().evidenceIdentity());
  }

  private TypedEffect handoffEffect(Operation operation) {
    return new CommitOwnerAuthorizedHandoffEffect(
        operation.scope().operationId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        operation.maintenanceLockIdentity(),
        operation.scope(),
        tupleCodec.operationTupleDigest(operation),
        operation.evidence().evidenceIdentity());
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
        || !scope.operationId().equals(record.get("operation_id", UUID.class))
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
    if (evidenceJson != null && evidence == null) {
      throw new IllegalStateException(
          "Persisted initial gameplay release evidence is inconsistent");
    }
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
            SELECT outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, state, send_outcome_uncertain,
                   attempt_count, last_attempt_id, last_outcome_code, acknowledged_at, last_dispatched_at,
                   (readback_identity IS NOT NULL OR readback_payload IS NOT NULL
                     OR readback_digest IS NOT NULL) AS has_readback
            FROM recovery_control.initial_gameplay_release_outbox
            WHERE operation_id = ? AND effect_id = ?
            """
                + (forUpdate ? " FOR UPDATE" : ""),
            operationId,
            effectId);
    if (record == null) {
      throw new IllegalArgumentException("INITIAL_GAMEPLAY_RELEASE_EFFECT_NOT_FOUND");
    }
    OutboxEntry entry = toOutboxEntry(record);
    if (entry.state() == EffectOutboxState.PENDING) {
      if (entry.lastOutcomeCode() != null
          || Boolean.TRUE.equals(record.get("send_outcome_uncertain", Boolean.class))) {
        throw conflict("retained pending effect has a dispatch outcome");
      }
      if (record.get("has_readback", boolean.class)) {
        throw conflict("retained pending effect has readback evidence");
      }
    }
    return entry;
  }

  private OutboxEntry toOutboxEntry(Record record) {
    JSONB payload = record.get("payload", JSONB.class);
    String effectKind = record.get("effect_kind", String.class);
    Instant acknowledgedAt = toInstant(record.get("acknowledged_at", OffsetDateTime.class));
    EffectOutboxState state =
        EffectOutboxState.fromDatabaseValue(record.get("state", String.class));
    boolean hasDispatchTimestamp = record.get("last_dispatched_at") != null;
    if (state == EffectOutboxState.PENDING && hasDispatchTimestamp) {
      throw conflict("retained pending effect has a dispatch timestamp");
    }
    if (state != EffectOutboxState.PENDING && !hasDispatchTimestamp) {
      throw conflict("retained effect has no dispatch timestamp");
    }
    TypedEffect effect = tupleCodec.readEffect(effectKind, payload.data());
    if (!effect.operationId().equals(record.get("operation_id", UUID.class))
        || !effect.effectId().equals(record.get("effect_id", UUID.class))
        || !tupleCodec.digest(effect).equals(record.get("payload_digest", String.class))
        || !effect
            .operationTupleDigest()
            .equals(record.get("operation_tuple_digest", String.class))) {
      throw new IllegalStateException("Persisted initial gameplay release effect is inconsistent");
    }
    return new OutboxEntry(
        record.get("outbox_id", UUID.class),
        effect,
        record.get("payload_digest", String.class),
        state,
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

  private void requireRetainedTransition(
      Record row, PublicTransitionTuple requested, String digest) {
    JSONB payload = row.get("tuple_payload", JSONB.class);
    PublicTransitionTuple retained;
    try {
      retained = payload == null ? null : tupleCodec.readPublicTransition(payload.data());
    } catch (IllegalStateException corrupted) {
      throw conflict("retained public transition tuple changed");
    }
    if (retained == null
        || !requested.equals(retained)
        || !digest.equals(row.get("tuple_digest", String.class))
        || !digest.equals(tupleCodec.digest(retained))
        || !retained.expectedPhase().databaseValue().equals(row.get("expected_phase", String.class))
        || !retained
            .verb()
            .resultPhase()
            .databaseValue()
            .equals(row.get("result_phase", String.class))) {
      throw conflict("retained public transition tuple changed");
    }
  }

  private void requireAcknowledgedEffect(
      DSLContext context,
      Operation operation,
      EffectKind kind,
      Instant upperBound,
      boolean forUpdate) {
    UUID operationId = operation.scope().operationId();
    String label = kind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED ? "preparation" : "handoff";
    Record stored =
        context.fetchOne(
            """
            SELECT outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest, payload,
                   payload_digest, state, attempt_count, last_attempt_id, last_outcome_code,
                   acknowledged_at, last_dispatched_at, updated_at, readback_identity, readback_payload,
                   readback_digest, preparation_observation_payload, preparation_observation_digest
            FROM recovery_control.initial_gameplay_release_outbox
            WHERE operation_id = ? AND effect_kind = ?
            """
                + (forUpdate ? " FOR UPDATE" : ""),
            operationId,
            kind.databaseValue());
    if (stored == null) throw conflict("retained " + label + " is missing");
    OutboxEntry effect = toOutboxEntry(stored);
    tupleCodec.requireEffectMatchesOperation(effect.effect(), operation);
    if (effect.state() != EffectOutboxState.ACKNOWLEDGED
        || effect.acknowledgedAt().isAfter(upperBound)) {
      throw conflict("retained " + label + " acknowledgement is unavailable");
    }
    EffectReadback acknowledgement = requireReadbackReceipt(stored, effect);
    if (kind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED) {
      EffectReadback observation =
          latestPreparationObservation(context, stored, effect, acknowledgement);
      if (observation.state() != EffectReadbackState.PREPARED_FENCED
          || observation.observedAt().isAfter(upperBound)) {
        throw conflict("retained preparation acknowledgement is unavailable");
      }
    }
  }

  private EffectReadback requireReadbackReceipt(Record stored, OutboxEntry effect) {
    EffectKind kind = effect.effect().kind();
    String label = kind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED ? "preparation" : "handoff";
    JSONB payload = stored.get("readback_payload", JSONB.class);
    String digest = stored.get("readback_digest", String.class);
    if (payload == null || digest == null) {
      throw conflict("retained " + label + " readback is incomplete");
    }
    EffectReadback readback = tupleCodec.readEffectReadback(payload.data());
    if (!digest.equals(tupleCodec.digest(readback))) {
      throw conflict("retained " + label + " readback digest does not match");
    }
    requireStoredReadbackIdentity(stored, readback);
    requireReadbackMatches(
        readback, effect, effect.effect().operationId(), effect.effect().effectId());
    requireSuccessfulReadbackState(readback, kind);
    requireRetainedReadbackRecordingTime(readback, stored, "acknowledged_at");
    return readback;
  }

  private Operation requireFinalizedHandoff(DSLContext context, Operation operation) {
    if (operation.phase() == Phase.FINALIZED) {
      requireAcknowledgedEffect(
          context,
          operation,
          EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF,
          operation.finalizedAt(),
          false);
    }
    return operation;
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

  private boolean hasRetainedClosure(
      DSLContext tx, OutboxEntry current, UUID attemptId, Integer expectedOrdinal) {
    return findRetainedClosure(tx, current, attemptId, expectedOrdinal) != null;
  }

  private record RetainedClosure(EffectReadback readback, Instant dispatchedAt) {}

  private RetainedClosure findRetainedClosure(
      DSLContext tx, OutboxEntry current, UUID attemptId, Integer expectedOrdinal) {
    Record row =
        tx.fetchOne(
            """
            SELECT c.attempt_ordinal, c.readback_payload, c.readback_digest, c.dispatched_at,
                   c.recorded_at, o.last_dispatched_at
            FROM recovery_control.initial_gameplay_release_attempt_closure c
            JOIN recovery_control.initial_gameplay_release_outbox o ON o.outbox_id=c.outbox_id
            WHERE c.outbox_id=? AND c.attempt_id=?
            """,
            current.outboxId(),
            attemptId);
    if (row == null) return null;
    Integer ordinal = row.get("attempt_ordinal", Integer.class);
    OffsetDateTime dispatchedAt = row.get("dispatched_at", OffsetDateTime.class);
    OffsetDateTime recordedAt = row.get("recorded_at", OffsetDateTime.class);
    if (ordinal == null
        || ordinal < 1
        || ordinal > current.attemptCount()
        || (expectedOrdinal != null && !expectedOrdinal.equals(ordinal))
        || dispatchedAt == null
        || recordedAt == null) {
      throw conflict("retained effect dispatch generation changed");
    }
    if (attemptId.equals(current.lastAttemptId())) {
      OffsetDateTime currentDispatchedAt = row.get("last_dispatched_at", OffsetDateTime.class);
      if (currentDispatchedAt == null
          || !currentDispatchedAt.toInstant().equals(dispatchedAt.toInstant())) {
        throw conflict("retained attempt closure dispatch time differs from the current attempt");
      }
    }
    EffectReadback retained =
        tupleCodec.readEffectReadback(row.get("readback_payload", JSONB.class).data());
    if (retained.state() != EffectReadbackState.PROVEN_NO_EFFECT
        || !current.effect().operationId().equals(retained.operationId())
        || !current.effect().effectId().equals(retained.effectId())
        || !attemptId.equals(retained.attemptId())
        || retained.attemptOrdinal() != ordinal
        || current.effect().kind() != retained.effectKind()
        || !current.effect().operationTupleDigest().equals(retained.operationTupleDigest())
        || !current.payloadDigest().equals(retained.payloadDigest())
        || !tupleCodec.digest(retained).equals(row.get("readback_digest", String.class))
        || retained.observedAt().isBefore(dispatchedAt.toInstant())
        || retained
            .observedAt()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            .isAfter(recordedAt.toInstant())) {
      throw conflict("retained attempt closure does not match the exact dispatched tuple");
    }
    return new RetainedClosure(retained, dispatchedAt.toInstant());
  }

  private static boolean hasRetainedOwnerRejection(
      DSLContext tx, OutboxEntry current, UUID attemptId, Integer expectedOrdinal) {
    Record row =
        tx.fetchOne(
            """
            SELECT attempt_ordinal FROM recovery_control.initial_gameplay_release_attempt_rejection
            WHERE outbox_id = ? AND attempt_id = ?
            """,
            current.outboxId(),
            attemptId);
    if (row == null) {
      return false;
    }
    Integer ordinal = row.get("attempt_ordinal", Integer.class);
    if (ordinal == null
        || ordinal < 1
        || ordinal > current.attemptCount()
        || (expectedOrdinal != null && !expectedOrdinal.equals(ordinal))) {
      throw conflict("retained owner rejection dispatch generation changed");
    }
    return true;
  }

  private static boolean hasRetainedLocalNoSend(
      DSLContext tx, OutboxEntry current, OffsetDateTime dispatchedAt) {
    Record receipt =
        tx.fetchOne(
            """
            SELECT dispatched_at FROM recovery_control.initial_gameplay_release_attempt_local_no_send
            WHERE outbox_id = ? AND attempt_id = ? AND attempt_ordinal = ?
            """,
            current.outboxId(),
            current.lastAttemptId(),
            current.attemptCount());
    if (receipt == null) {
      return false;
    }
    OffsetDateTime retained = receipt.get("dispatched_at", OffsetDateTime.class);
    if (dispatchedAt == null
        || retained == null
        || !retained.toInstant().equals(dispatchedAt.toInstant())) {
      throw conflict("retained local no-send proof does not match the exact dispatch");
    }
    return true;
  }

  private void requireExactClosureBeforeRetry(DSLContext tx, OutboxEntry current) {
    Record stored =
        tx.fetchOne(
            "SELECT readback_payload, readback_digest, readback_identity, retry_provenance,"
                + " last_dispatched_at, send_outcome_uncertain FROM "
                + OUTBOX_TABLE
                + " WHERE operation_id = ? AND effect_id = ?",
            current.effect().operationId(),
            current.effect().effectId());
    if (stored == null) {
      throw conflict("effect outbox row disappeared while checking retained readback");
    }
    JSONB payload = stored.get("readback_payload", JSONB.class);
    String digest = stored.get("readback_digest", String.class);
    String provenance = stored.get("retry_provenance", String.class);
    boolean emptyReadback =
        payload == null && digest == null && stored.get("readback_identity", String.class) == null;
    if ("local_no_send".equals(provenance)) {
      OffsetDateTime dispatchedAt = stored.get("last_dispatched_at", OffsetDateTime.class);
      if (!Boolean.FALSE.equals(stored.get("send_outcome_uncertain", Boolean.class))
          || (!emptyReadback && !hasValidatedUnknownReadback(stored, digest, current))
          || !hasRetainedLocalNoSend(tx, current, dispatchedAt)
          || hasRetainedClosure(tx, current, current.lastAttemptId(), current.attemptCount())
          || hasRetainedOwnerRejection(
              tx, current, current.lastAttemptId(), current.attemptCount())) {
        throw conflict("retained local no-send proof does not match the exact dispatch");
      }
      return;
    }
    if ("owner_rejected".equals(provenance)) {
      if (!hasRetainedOwnerRejection(tx, current, current.lastAttemptId(), current.attemptCount())
          || hasRetainedClosure(tx, current, current.lastAttemptId(), current.attemptCount())
          || (!emptyReadback && !hasValidatedUnknownReadback(stored, digest, current))) {
        throw conflict("retained owner rejection does not match the exact dispatch");
      }
      return;
    }
    if (!"owner_closure".equals(provenance) || payload == null || digest == null) {
      throw conflict("retained retry has no exact durable provenance");
    }
    EffectReadback saved = tupleCodec.readEffectReadback(payload.data());
    if (!digest.equals(tupleCodec.digest(saved))) {
      throw conflict("retained effect readback digest does not match");
    }
    requireStoredReadbackIdentity(stored, saved);
    requireReadbackMatches(
        saved, current, current.effect().operationId(), current.effect().effectId());
    if (saved.state() != EffectReadbackState.PROVEN_NO_EFFECT) {
      throw conflict("retained retry provenance does not describe definitive closure");
    }
    requireRetainedClosureMatchesStoredReadback(tx, current, stored, digest);
  }

  private void requireValidExactStoredReadback(
      EffectReadback replay, Record stored, String digest, OutboxEntry current) {
    JSONB payload = stored.get("readback_payload", JSONB.class);
    if (payload == null) {
      throw conflict("retained effect readback is incomplete");
    }
    EffectReadback saved = tupleCodec.readEffectReadback(payload.data());
    if (!digest.equals(tupleCodec.digest(saved)) || !saved.equals(replay)) {
      throw conflict("retained effect readback does not match exact replay");
    }
    requireStoredReadbackIdentity(stored, saved);
    requireReadbackMatches(
        saved, current, current.effect().operationId(), current.effect().effectId());
  }

  private void requireRetainedClosureMatchesStoredReadback(
      DSLContext tx, OutboxEntry current, Record stored, String storedDigest) {
    RetainedClosure retained =
        findRetainedClosure(tx, current, current.lastAttemptId(), current.attemptCount());
    if (retained == null) {
      throw conflict("durable owner closure has no retained attempt identity");
    }
    // Compare the first durable receipt, not a replay's possibly newer observation time.
    requireValidExactStoredReadback(retained.readback(), stored, storedDigest, current);
  }

  private EffectReadback requireNonRegressingReadback(
      EffectReadback readback, Record stored, OutboxEntry current) {
    JSONB payload = stored.get("readback_payload", JSONB.class);
    String digest = stored.get("readback_digest", String.class);
    if (payload == null && digest == null) {
      return null;
    }
    if (payload == null || digest == null) {
      throw conflict("retained effect readback is incomplete");
    }
    EffectReadback previous = tupleCodec.readEffectReadback(payload.data());
    if (!digest.equals(tupleCodec.digest(previous))) {
      throw conflict("retained effect readback digest does not match");
    }
    requireStoredReadbackIdentity(stored, previous);
    requireReadbackMatches(
        previous, current, current.effect().operationId(), current.effect().effectId());
    requireTerminalReplayAfterDispatch(previous, stored);
    if (readback.observedAt().isBefore(previous.observedAt())) {
      throw conflict("effect readback predates the retained same-attempt observation");
    }
    if (previous.state() == EffectReadbackState.UNKNOWN_OR_PARTIAL
        && readback.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL
        && !readback.observedAt().isAfter(previous.observedAt())) {
      throw conflict("definitive effect readback must be newer than retained partial evidence");
    }
    return previous;
  }

  private boolean hasValidatedUnknownReadback(
      Record stored, String storedDigest, OutboxEntry current) {
    JSONB payload = stored == null ? null : stored.get("readback_payload", JSONB.class);
    if (payload == null || storedDigest == null) {
      return false;
    }
    EffectReadback previous = tupleCodec.readEffectReadback(payload.data());
    if (!storedDigest.equals(tupleCodec.digest(previous))
        || previous.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL) {
      return false;
    }
    requireStoredReadbackIdentity(stored, previous);
    requireReadbackMatches(
        previous, current, current.effect().operationId(), current.effect().effectId());
    requireTerminalReplayAfterDispatch(previous, stored);
    return true;
  }

  private void requireStoredReadbackIdentity(Record stored, EffectReadback readback) {
    if (!readback.readbackIdentity().equals(stored.get("readback_identity", String.class))) {
      throw conflict("retained effect readback identity does not match");
    }
  }

  private void requireTerminalReplayWithinAttempt(
      EffectReadback readback, Record stored, Instant now) {
    requireTerminalReplayAfterDispatch(readback, stored);
    if (readback.observedAt().isAfter(now)) {
      throw conflict("effect readback observation is in the future");
    }
  }

  private void requireTerminalReplayAfterDispatch(EffectReadback readback, Record stored) {
    OffsetDateTime dispatchTime = stored.get("last_dispatched_at", OffsetDateTime.class);
    if (dispatchTime == null) {
      throw conflict("effect dispatch timestamp is unavailable");
    }
    if (readback.observedAt().isBefore(dispatchTime.toInstant())) {
      throw conflict("effect readback is stale");
    }
  }

  private boolean sameRetainedOutcome(
      EffectReadback readback, Record stored, String storedDigest, String recordedAtColumn) {
    JSONB payload = stored.get("readback_payload", JSONB.class);
    if (payload == null || storedDigest == null) {
      return false;
    }
    EffectReadback acknowledged = tupleCodec.readEffectReadback(payload.data());
    if (!storedDigest.equals(tupleCodec.digest(acknowledged))) {
      return false;
    }
    requireStoredReadbackIdentity(stored, acknowledged);
    requireRetainedReadbackRecordingTime(acknowledged, stored, recordedAtColumn);
    return sameRetainedOutcome(acknowledged, readback);
  }

  private static boolean sameRetainedOutcome(EffectReadback acknowledged, EffectReadback readback) {
    // The authenticated outcome identifies the durable closure, not the read clock.
    // A valid new observation time never replaces the first durable receipt.
    return acknowledged.equals(
        new EffectReadback(
            readback.operationId(),
            readback.effectId(),
            readback.attemptId(),
            readback.attemptOrdinal(),
            readback.effectKind(),
            readback.operationTupleDigest(),
            readback.payloadDigest(),
            readback.state(),
            readback.authority(),
            readback.readbackIdentity(),
            acknowledged.observedAt()));
  }

  private void requireRetainedReadbackRecordingTime(
      EffectReadback readback, Record stored, String recordedAtColumn) {
    OffsetDateTime recordedAt = stored.get(recordedAtColumn, OffsetDateTime.class);
    if (recordedAt == null) {
      throw conflict("retained effect readback recording time is unavailable");
    }
    requireTerminalReplayAfterDispatch(readback, stored);
    if (readback
        .observedAt()
        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        .isAfter(recordedAt.toInstant())) {
      throw conflict("retained effect readback observation is after its recording time");
    }
  }

  private void requireReadbackMatches(
      EffectReadback readback, OutboxEntry outbox, UUID operationId, UUID effectId) {
    if (readback.attemptOrdinal() != outbox.attemptCount()) {
      throw conflict("effect readback dispatch generation changed");
    }
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
