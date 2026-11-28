package net.firedevops.firemud.recovery.config;

import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
final class RecoveryJournalHealthIndicator implements HealthIndicator {
  private static final int PROBE_TIMEOUT_SECONDS = 2;
  private static final long PROBE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS);
  private static final String CHECK =
      """
      SELECT
        current_setting('transaction_read_only') = 'off'
        AND current_setting('fsync') = 'on'
        AND current_setting('server_encoding') = 'UTF8'
        AND current_setting('synchronous_commit') IN ('on', 'local', 'remote_write', 'remote_apply')
        AND current_setting('log_error_verbosity') = 'terse'
        AND current_setting('log_parameter_max_length_on_error') = '0'
        AND current_setting('log_parameter_max_length') = '0'
        AND
      """
          + JooqInitialGameplayReleaseJournal.DURABLE_JOURNAL_RELATIONS
          + """
            AND (SELECT count(*) = 11 AND count(DISTINCT version) FILTER (WHERE success AND (
               (version = '1' AND script = 'V1__initial_gameplay_release_journal.sql')
               OR (version = '2' AND script = 'V2__retain_initial_gameplay_release_attempt_closure.sql')
               OR (version = '3'
                   AND script = 'V3__require_dispatch_attempt_for_non_pending_effects.sql')
               OR (version = '4'
                   AND script = 'V4__retain_typed_retry_provenance.sql')
               OR (version = '5'
                   AND script = 'V5__retain_local_dispatch_uncertainty.sql')
               OR (version = '6'
                   AND script = 'V6__retain_latest_preparation_observation.sql')
               OR (version = '7'
                   AND script = 'V7__retain_preparation_receipt_identities.sql')
               OR (version = '8'
                   AND script = 'V8__require_pending_effect_without_dispatch_timestamp.sql')
               OR (version = '9'
                   AND script = 'V9__require_pristine_pending_attempt.sql')
               OR (version = '10'
                   AND script = 'V10__refuse_pending_effects_with_retained_attempts.sql')
               OR (version = '11'
                   AND script = 'V11__retain_owner_rejection_refresh_floor.sql'))) = 11
             FROM recovery_control.flyway_schema_history_recovery_control
             WHERE version IS NOT NULL OR type <> 'SCHEMA')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_attempt_local_no_send', 'INSERT')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_operation', 'INSERT')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_operation', 'UPDATE')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_transition', 'INSERT')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_outbox', 'INSERT')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_outbox', 'UPDATE')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_attempt_closure', 'INSERT')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_attempt_rejection', 'INSERT')
            AND has_table_privilege(
              'recovery_control.initial_gameplay_release_preparation_receipt', 'INSERT')
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_constraint c
              JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_preparation_receipt'::regclass
                AND c.contype = 'p' AND c.convalidated AND NOT c.condeferrable
                AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND cardinality(c.conkey) = 2
                AND c.conkey = ARRAY(
                  SELECT a.attnum
                  FROM pg_catalog.pg_attribute a
                  WHERE a.attrelid = c.conrelid AND NOT a.attisdropped
                    AND ((a.attname = 'outbox_id' AND a.atttypid = 'pg_catalog.uuid'::regtype)
                      OR (a.attname = 'identity_digest'
                        AND a.atttypid = 'pg_catalog.bpchar'::regtype AND a.atttypmod = 68))
                  ORDER BY CASE a.attname WHEN 'outbox_id' THEN 0 ELSE 1 END))
            AND NOT EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
                 AND a.attnum = i.indkey[0] AND NOT a.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_preparation_receipt'::regclass
                AND i.indisunique AND i.indisready AND i.indislive AND i.indnkeyatts = 1
                AND a.attname IN ('outbox_id', 'identity_digest'))
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_constraint c
              JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_attempt_rejection'::regclass
                AND c.contype = 'p' AND c.convalidated AND NOT c.condeferrable
                AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND c.conkey = ARRAY(
                  SELECT a.attnum
                  FROM pg_catalog.pg_attribute a
                  WHERE a.attrelid = c.conrelid AND NOT a.attisdropped
                    AND a.attname IN ('outbox_id', 'attempt_id')
                  ORDER BY CASE a.attname WHEN 'outbox_id' THEN 0 ELSE 1 END))
            AND NOT EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
                 AND a.attnum = i.indkey[0] AND NOT a.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_attempt_rejection'::regclass
                AND i.indisunique AND i.indisready AND i.indislive AND i.indnkeyatts = 1
                AND a.attname = 'outbox_id')
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_constraint c
              JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_attempt_local_no_send'::regclass
                AND c.contype = 'p' AND c.convalidated AND NOT c.condeferrable
                AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND c.conkey = ARRAY(
                  SELECT a.attnum
                  FROM pg_catalog.pg_attribute a
                  WHERE a.attrelid = c.conrelid AND NOT a.attisdropped
                    AND a.attname IN ('outbox_id', 'attempt_id', 'attempt_ordinal')
                  ORDER BY CASE a.attname WHEN 'outbox_id' THEN 0 WHEN 'attempt_id' THEN 1 ELSE 2 END))
            AND NOT EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
                AND a.attnum = i.indkey[0] AND NOT a.attisdropped
              LEFT JOIN pg_catalog.pg_attribute b ON b.attrelid = i.indrelid
                AND b.attnum = i.indkey[1] AND NOT b.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_attempt_local_no_send'::regclass
                AND i.indisunique AND i.indisready AND i.indislive
                AND a.attname IN ('outbox_id', 'attempt_id')
                AND (i.indnkeyatts = 1 OR (i.indnkeyatts = 2
                  AND b.attname IN ('outbox_id', 'attempt_id'))))
            AND NOT EXISTS (
              SELECT 1
              FROM (VALUES
                ('operation_id', 'pg_catalog.uuid'::regtype, -1, true),
                ('operation_kind', 'pg_catalog.varchar'::regtype, 44, true),
                ('target_namespace', 'pg_catalog.varchar'::regtype, 257, true),
                ('playable_state_namespace_id', 'pg_catalog.uuid'::regtype, -1, true),
                ('peer_storage_binding', 'pg_catalog.jsonb'::regtype, -1, true),
                ('scope_payload', 'pg_catalog.jsonb'::regtype, -1, true),
                ('evidence_ref', 'pg_catalog.text'::regtype, -1, true),
                ('scope_digest', 'pg_catalog.bpchar'::regtype, 68, true),
                ('maintenance_lock_identity', 'pg_catalog.uuid'::regtype, -1, true),
                ('phase', 'pg_catalog.varchar'::regtype, 36, true),
                ('evidence_snapshot', 'pg_catalog.jsonb'::regtype, -1, false),
                ('evidence_identity', 'pg_catalog.text'::regtype, -1, false),
                ('evidence_digest', 'pg_catalog.bpchar'::regtype, 68, false),
                ('revision', 'pg_catalog.int8'::regtype, -1, true),
                ('created_at', 'pg_catalog.timestamptz'::regtype, -1, true),
                ('updated_at', 'pg_catalog.timestamptz'::regtype, -1, true),
                ('finalized_at', 'pg_catalog.timestamptz'::regtype, -1, false)
              ) AS required(column_name, column_type, type_modifier, required_not_null)
              LEFT JOIN pg_catalog.pg_attribute a
                ON a.attrelid = 'recovery_control.initial_gameplay_release_operation'::regclass
                AND a.attname = required.column_name AND NOT a.attisdropped
                AND a.atttypid = required.column_type AND a.atttypmod = required.type_modifier
                AND a.attnotnull = required.required_not_null
                AND (a.attname <> 'finalized_at' OR NOT a.atthasdef)
              WHERE a.attnum IS NULL)
            AND (SELECT count(DISTINCT pg_catalog.pg_get_expr(c.conbin, c.conrelid)) = 3
              FROM pg_catalog.pg_constraint c
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_operation'::regclass
                AND c.contype = 'c' AND c.convalidated AND c.conenforced
                AND pg_catalog.pg_get_expr(c.conbin, c.conrelid) IN (
                  '(revision > 0)',
                  '(((phase)::text = ''finalized''::text) = (finalized_at IS NOT NULL))',
                  '(((evidence_snapshot IS NULL) AND (evidence_identity IS NULL)'
                  || ' AND (evidence_digest IS NULL)) OR ((evidence_snapshot IS NOT NULL)'
                  || ' AND (evidence_identity IS NOT NULL) AND (evidence_digest IS NOT NULL)))'))
            AND (SELECT count(DISTINCT a.attname) = 2
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
                 AND a.attnum = i.indkey[0] AND NOT a.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_operation'::regclass
                AND i.indisunique AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND i.indpred IS NULL AND i.indnkeyatts = 1
                AND a.atttypid = 'pg_catalog.uuid'::regtype AND a.attnotnull
                AND a.attname IN ('operation_id', 'maintenance_lock_identity'))
            AND (SELECT count(DISTINCT effect.attname) = 2
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute op ON op.attrelid = i.indrelid
                 AND op.attnum = i.indkey[0] AND NOT op.attisdropped
              JOIN pg_catalog.pg_attribute effect ON effect.attrelid = i.indrelid
                 AND effect.attnum = i.indkey[1] AND NOT effect.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND i.indisunique AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND i.indpred IS NULL AND i.indnkeyatts = 2
                AND op.attname = 'operation_id' AND op.attnotnull
                AND op.atttypid = 'pg_catalog.uuid'::regtype
                AND effect.attnotnull
                AND ((effect.attname = 'effect_kind'
                      AND effect.atttypid = 'pg_catalog.varchar'::regtype AND effect.atttypmod = 52)
                  OR (effect.attname = 'effect_id' AND effect.atttypid = 'pg_catalog.uuid'::regtype)))
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute operation ON operation.attrelid = i.indrelid
                AND operation.attnum IN (i.indkey[0], i.indkey[1]) AND NOT operation.attisdropped
              JOIN pg_catalog.pg_attribute verb ON verb.attrelid = i.indrelid
                AND verb.attnum IN (i.indkey[0], i.indkey[1]) AND NOT verb.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_transition'::regclass
                AND i.indisunique AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND i.indpred IS NULL AND i.indnkeyatts = 2
                AND operation.attname = 'operation_id' AND operation.attnotnull
                AND operation.atttypid = 'pg_catalog.uuid'::regtype
                AND verb.attname = 'verb' AND verb.attnotnull
                AND verb.atttypid = 'pg_catalog.varchar'::regtype AND verb.atttypmod = 28)
            AND NOT EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
                AND a.attnum = i.indkey[0] AND NOT a.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND i.indisunique AND i.indisready AND i.indislive AND i.indnkeyatts = 1
                AND a.attname IN ('operation_id', 'effect_kind'))
            AND NOT EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
                AND a.attnum = i.indkey[0] AND NOT a.attisdropped
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_transition'::regclass
                AND i.indisunique AND i.indisready AND i.indislive AND i.indnkeyatts = 1
                AND a.attname IN ('operation_id', 'verb'))
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_transition'::regclass
                AND attname = 'tuple_digest' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'effect_id' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.uuid'::regtype)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'state' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.varchar'::regtype AND atttypmod = 24)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'readback_identity' AND NOT attisdropped AND NOT attnotnull
                AND NOT atthasdef
                AND atttypid = 'pg_catalog.text'::regtype)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'payload' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.jsonb'::regtype)
            AND (SELECT count(*) = 2 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname IN ('operation_tuple_digest', 'payload_digest')
                AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'readback_digest' AND NOT attisdropped AND NOT attnotnull AND NOT atthasdef
                AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_attempt_closure'::regclass
                AND attname = 'attempt_id' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.uuid'::regtype)
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute outbox
                ON outbox.attrelid = i.indrelid AND outbox.attnum = i.indkey[0]
              JOIN pg_catalog.pg_attribute attempt
                ON attempt.attrelid = i.indrelid AND attempt.attnum = i.indkey[1]
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_attempt_closure'::regclass
                AND i.indisprimary AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                AND i.indpred IS NULL AND i.indnkeyatts = 2
                AND outbox.attname = 'outbox_id' AND NOT outbox.attisdropped
                AND outbox.attnotnull AND outbox.atttypid = 'pg_catalog.uuid'::regtype
                AND attempt.attname = 'attempt_id')
            AND NOT EXISTS (
              SELECT 1
              FROM pg_catalog.pg_index i
              JOIN pg_catalog.pg_attribute outbox
                ON outbox.attrelid = i.indrelid AND outbox.attnum = i.indkey[0]
              WHERE i.indrelid = 'recovery_control.initial_gameplay_release_attempt_closure'::regclass
                AND i.indisunique AND i.indisready AND i.indislive AND i.indnkeyatts = 1
                AND outbox.attname = 'outbox_id' AND NOT outbox.attisdropped)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_attempt_closure'::regclass
                AND attname = 'readback_digest' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'last_outcome_code' AND NOT attisdropped AND NOT attnotnull AND NOT atthasdef
                AND (atttypid = 'pg_catalog.text'::regtype
                  OR (atttypid = 'pg_catalog.varchar'::regtype
                    -- varchar typmod includes the four-byte header. Outcome codes allow 64 characters.
                    AND (atttypmod = -1 OR atttypmod - 4 >= 64))))
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'retry_provenance' AND NOT attisdropped AND NOT attnotnull
                AND NOT atthasdef
                AND atttypid = 'pg_catalog.text'::regtype)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'send_outcome_uncertain' AND NOT attisdropped AND NOT attnotnull
                AND NOT atthasdef AND atttypid = 'pg_catalog.bool'::regtype)
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_constraint c
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND c.conname = 'ck_release_outbox_non_pending_attempt'
                AND c.contype = 'c' AND c.convalidated AND c.conenforced
                AND pg_catalog.pg_get_expr(c.conbin, c.conrelid) =
                  '(((state)::text = ''pending''::text) OR ((attempt_count > 0)'
                  || ' AND (last_attempt_id IS NOT NULL)'
                  || ' AND (last_attempt_id <> ''00000000-0000-0000-0000-000000000000''::uuid)'
                  || ' AND (last_dispatched_at IS NOT NULL)))')
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND NOT attisdropped
                AND ((attname = 'attempt_count'
                      AND attnotnull AND atttypid = 'pg_catalog.int4'::regtype)
                  OR (attname = 'last_attempt_id'
                      AND NOT attnotnull AND NOT atthasdef AND atttypid = 'pg_catalog.uuid'::regtype)))
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_constraint c
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND c.conname = 'ck_release_outbox_pending_dispatch_timestamp'
                AND c.contype = 'c' AND c.convalidated AND c.conenforced
                AND pg_catalog.pg_get_expr(c.conbin, c.conrelid) =
                  '(((state)::text <> ''pending''::text) OR ((attempt_count = 0)'
                  || ' AND (last_attempt_id IS NULL) AND (last_dispatched_at IS NULL)))')
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_attempt_closure'::regclass
                AND attname IN ('dispatched_at', 'recorded_at') AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.timestamptz'::regtype AND atttypmod IN (-1, 6))
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'readback_payload' AND NOT attisdropped AND NOT attnotnull AND NOT atthasdef
                AND atttypid = 'pg_catalog.jsonb'::regtype)
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_attempt_local_no_send'::regclass
                AND attname IN ('dispatched_at', 'recorded_at') AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.timestamptz'::regtype AND atttypmod IN (-1, 6))
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname IN ('acknowledged_at', 'last_dispatched_at')
                AND NOT attisdropped AND NOT attnotnull AND NOT atthasdef
                AND atttypid = 'pg_catalog.timestamptz'::regtype AND atttypmod = -1)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND attname = 'updated_at' AND NOT attisdropped AND attnotnull
                AND atttypid = 'pg_catalog.timestamptz'::regtype AND atttypmod IN (-1, 6))
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND NOT attisdropped AND NOT attnotnull AND NOT atthasdef
                AND ((attname = 'preparation_observation_payload'
                      AND atttypid = 'pg_catalog.jsonb'::regtype)
                  OR (attname = 'preparation_observation_digest'
                      AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)))
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_constraint c
              WHERE c.conrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
                AND c.contype = 'c' AND c.convalidated AND c.conenforced
                AND ((c.conname = 'ck_release_outbox_pending_outcome'
                      AND pg_catalog.pg_get_expr(c.conbin, c.conrelid) =
                        '(((state)::text <> ''pending''::text) OR ((last_outcome_code IS NULL)'
                        || ' AND (send_outcome_uncertain IS NOT TRUE)))')
                  OR (c.conname = 'ck_release_outbox_preparation_observation'
                      AND pg_catalog.pg_get_expr(c.conbin, c.conrelid) =
                        '(((preparation_observation_payload IS NULL)'
                        || ' AND (preparation_observation_digest IS NULL))'
                        || ' OR ((preparation_observation_payload IS NOT NULL)'
                        || ' AND (preparation_observation_digest IS NOT NULL)'
                        || ' AND ((effect_kind)::text = ''prepare_playable_state_fenced''::text)'
                        || ' AND ((state)::text = ''acknowledged''::text)))')))
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_attribute a
              JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
              WHERE a.attrelid = 'recovery_control.initial_gameplay_release_transition'::regclass
                AND a.attname = 'created_at' AND NOT a.attisdropped AND a.attnotnull
                AND a.atttypid = 'pg_catalog.timestamptz'::regtype AND a.atttypmod = -1
                AND pg_catalog.pg_get_expr(d.adbin, d.adrelid) = 'CURRENT_TIMESTAMP')
            AND (SELECT count(*) = 2
              FROM pg_catalog.pg_attribute a
              WHERE a.attrelid = 'recovery_control.initial_gameplay_release_transition'::regclass
                AND a.attname IN ('expected_phase', 'result_phase') AND NOT a.attisdropped
                AND a.attnotnull AND a.atttypid = 'pg_catalog.varchar'::regtype AND a.atttypmod = 36)
            AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_attribute a
              WHERE a.attrelid = 'recovery_control.initial_gameplay_release_transition'::regclass
                AND a.attname = 'tuple_payload' AND NOT a.attisdropped AND a.attnotnull
                AND a.atttypid = 'pg_catalog.jsonb'::regtype AND a.atttypmod = -1)
            AND NOT EXISTS (
              SELECT operation_id, operation_kind, target_namespace, playable_state_namespace_id,
                     peer_storage_binding, scope_payload, evidence_ref, scope_digest,
                     maintenance_lock_identity, phase, evidence_snapshot, evidence_identity,
                     evidence_digest, revision, created_at, updated_at, finalized_at
              FROM recovery_control.initial_gameplay_release_operation WHERE FALSE)
            AND NOT EXISTS (
              SELECT operation_id, verb, tuple_digest, tuple_payload, expected_phase, result_phase,
                     created_at
              FROM recovery_control.initial_gameplay_release_transition WHERE FALSE)
            AND NOT EXISTS (
              SELECT outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest,
                     payload_digest, payload, state, attempt_count, last_attempt_id, last_dispatched_at,
                     last_outcome_code, retry_provenance, send_outcome_uncertain,
                     readback_identity, readback_payload, readback_digest,
                     preparation_observation_payload, preparation_observation_digest,
                     created_at, updated_at, acknowledged_at
              FROM recovery_control.initial_gameplay_release_outbox WHERE FALSE)
            AND NOT EXISTS (
              SELECT outbox_id, attempt_id, attempt_ordinal, readback_payload, readback_digest,
                     dispatched_at, recorded_at
              FROM recovery_control.initial_gameplay_release_attempt_closure WHERE FALSE)
            AND EXISTS (
              SELECT 1 FROM pg_catalog.pg_attribute
              WHERE attrelid = 'recovery_control.initial_gameplay_release_attempt_rejection'::regclass
                AND attname = 'preparation_refresh_after' AND NOT attisdropped AND attnotnull
                AND NOT atthasdef AND atttypid = 'pg_catalog.timestamptz'::regtype AND atttypmod = -1)
            AND NOT EXISTS (
              SELECT outbox_id, attempt_id, attempt_ordinal, preparation_refresh_after
              FROM recovery_control.initial_gameplay_release_attempt_rejection WHERE FALSE)
            AND NOT EXISTS (
              SELECT outbox_id, attempt_id, attempt_ordinal, dispatched_at, recorded_at
              FROM recovery_control.initial_gameplay_release_attempt_local_no_send WHERE FALSE)
            AND NOT EXISTS (
              SELECT outbox_id, identity_digest
              FROM recovery_control.initial_gameplay_release_preparation_receipt WHERE FALSE)
          """;

  private final DataSource dataSource;
  private final boolean startupMigrationEnabled;
  private final long probeTimeoutNanos;
  private final AtomicReference<CompletableFuture<Boolean>> inFlightProbe = new AtomicReference<>();
  private final ThreadPoolExecutor probes =
      new ThreadPoolExecutor(
          0,
          1,
          PROBE_TIMEOUT_SECONDS,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          task -> {
            Thread worker = new Thread(task, "recovery-journal-readiness");
            worker.setDaemon(true);
            return worker;
          });

  @Autowired
  RecoveryJournalHealthIndicator(
      DataSource dataSource,
      @Value("${spring.flyway.enabled:true}") boolean startupMigrationEnabled) {
    this(dataSource, PROBE_TIMEOUT_NANOS, startupMigrationEnabled);
  }

  RecoveryJournalHealthIndicator(DataSource dataSource) {
    this(dataSource, PROBE_TIMEOUT_NANOS);
  }

  RecoveryJournalHealthIndicator(DataSource dataSource, long probeTimeoutNanos) {
    this(dataSource, probeTimeoutNanos, true);
  }

  private RecoveryJournalHealthIndicator(
      DataSource dataSource, long probeTimeoutNanos, boolean startupMigrationEnabled) {
    this.dataSource = dataSource;
    this.startupMigrationEnabled = startupMigrationEnabled;
    if (probeTimeoutNanos <= 0) {
      throw new IllegalArgumentException("Probe timeout must be positive");
    }
    this.probeTimeoutNanos = probeTimeoutNanos;
  }

  @Override
  public Health health() {
    if (!startupMigrationEnabled || !JooqInitialGameplayReleaseJournal.driverLoggingIsRedacted()) {
      return Health.down().build();
    }
    long requestDeadline = System.nanoTime() + probeTimeoutNanos;
    CompletableFuture<Boolean> probe = currentProbe();
    try {
      long remaining = requestDeadline - System.nanoTime();
      return remaining > 0
              && probe.get(remaining, TimeUnit.NANOSECONDS)
              && JooqInitialGameplayReleaseJournal.driverLoggingIsRedacted()
              && requestDeadline - System.nanoTime() > 0
          ? Health.up().build()
          : Health.down().build();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Health.down().build();
    } catch (ExecutionException | TimeoutException unavailable) {
      return Health.down().build();
    }
  }

  private CompletableFuture<Boolean> currentProbe() {
    while (true) {
      CompletableFuture<Boolean> current = inFlightProbe.get();
      if (current != null) {
        return current;
      }
      CompletableFuture<Boolean> candidate = new CompletableFuture<>();
      if (!inFlightProbe.compareAndSet(null, candidate)) {
        continue;
      }
      try {
        probes.execute(() -> runProbe(candidate));
      } catch (RejectedExecutionException unavailable) {
        inFlightProbe.compareAndSet(candidate, null);
        candidate.complete(false);
      }
      return candidate;
    }
  }

  private void runProbe(CompletableFuture<Boolean> probe) {
    boolean ready = false;
    try {
      ready = checkJournal(System.nanoTime() + probeTimeoutNanos);
    } catch (SQLException | RuntimeException unavailable) {
      ready = false;
    } finally {
      // checkJournal returns only after JDBC resources have been closed. Publish only
      // after relinquishing the shared probe slot so a new caller cannot borrow early.
      inFlightProbe.compareAndSet(probe, null);
      probe.complete(ready);
    }
  }

  @PreDestroy
  void stopProbes() {
    probes.shutdownNow();
  }

  private boolean checkJournal(long deadline) throws SQLException {
    // The worker retains custody of the connection even after the caller's deadline expires.
    // No queued probe can accumulate behind an in-flight acquisition or JDBC cleanup.
    if (deadline - System.nanoTime() <= 0) {
      return false;
    }
    boolean ready;
    try (var connection = dataSource.getConnection()) {
      if (!connection.getAutoCommit() || connection.isReadOnly()) {
        return false;
      }
      int originalTimeout = connection.getNetworkTimeout();
      int remainingMillis = (int) TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remainingMillis <= 0) {
        return false;
      }
      int probeTimeout =
          originalTimeout > 0 ? Math.min(originalTimeout, remainingMillis) : remainingMillis;
      connection.setNetworkTimeout(Runnable::run, probeTimeout);
      try (var statement = connection.prepareStatement(CHECK)) {
        statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
        try (var rows = statement.executeQuery()) {
          ready = rows.next() && rows.getBoolean(1);
        }
      } finally {
        connection.setNetworkTimeout(Runnable::run, originalTimeout);
      }
    }
    return ready && deadline - System.nanoTime() > 0;
  }
}
