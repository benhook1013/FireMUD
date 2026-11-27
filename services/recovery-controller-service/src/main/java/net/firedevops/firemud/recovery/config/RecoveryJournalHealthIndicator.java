package net.firedevops.firemud.recovery.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
final class RecoveryJournalHealthIndicator implements HealthIndicator {
  private static final String CHECK =
      """
      SELECT
        current_setting('transaction_read_only') = 'off'
        AND current_setting('log_error_verbosity') = 'terse'
        AND current_setting('log_parameter_max_length_on_error') = '0'
        AND current_setting('log_parameter_max_length') = '0'
        AND NOT EXISTS (
          SELECT 1
          FROM (VALUES
            ('recovery_control.initial_gameplay_release_operation'::regclass),
            ('recovery_control.initial_gameplay_release_transition'::regclass),
            ('recovery_control.initial_gameplay_release_outbox'::regclass),
            ('recovery_control.initial_gameplay_release_attempt_closure'::regclass),
            ('recovery_control.initial_gameplay_release_attempt_rejection'::regclass),
            ('recovery_control.initial_gameplay_release_attempt_local_no_send'::regclass),
            ('recovery_control.initial_gameplay_release_preparation_receipt'::regclass)
          ) AS journal_relation(relation)
          WHERE pg_catalog.row_security_active(relation))
        AND (SELECT count(DISTINCT version) = 7
         FROM recovery_control.flyway_schema_history_recovery_control
         WHERE success AND (
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
               AND script = 'V7__retain_preparation_receipt_identities.sql')))
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
            AND i.indisvalid AND i.indimmediate
            AND cardinality(c.conkey) = 2
            AND c.conkey = ARRAY(
              SELECT a.attnum
              FROM pg_catalog.pg_attribute a
              WHERE a.attrelid = c.conrelid AND NOT a.attisdropped
                AND ((a.attname = 'outbox_id' AND a.atttypid = 'pg_catalog.uuid'::regtype)
                  OR (a.attname = 'identity_digest'
                    AND a.atttypid = 'pg_catalog.bpchar'::regtype AND a.atttypmod = 68))
              ORDER BY CASE a.attname WHEN 'outbox_id' THEN 0 ELSE 1 END))
        AND EXISTS (
          SELECT 1
          FROM pg_catalog.pg_constraint c
          JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid
          WHERE c.conrelid = 'recovery_control.initial_gameplay_release_attempt_rejection'::regclass
            AND c.contype = 'p' AND c.convalidated AND NOT c.condeferrable
            AND i.indisvalid AND i.indimmediate
            AND c.conkey = ARRAY(
              SELECT a.attnum
              FROM pg_catalog.pg_attribute a
              WHERE a.attrelid = c.conrelid AND NOT a.attisdropped
                AND a.attname IN ('outbox_id', 'attempt_id')
              ORDER BY CASE a.attname WHEN 'outbox_id' THEN 0 ELSE 1 END))
        AND EXISTS (
          SELECT 1
          FROM pg_catalog.pg_constraint c
          JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid
          WHERE c.conrelid = 'recovery_control.initial_gameplay_release_attempt_local_no_send'::regclass
            AND c.contype = 'p' AND c.convalidated AND NOT c.condeferrable
            AND i.indisvalid AND i.indimmediate
            AND c.conkey = ARRAY(
              SELECT a.attnum
              FROM pg_catalog.pg_attribute a
              WHERE a.attrelid = c.conrelid AND NOT a.attisdropped
                AND a.attname IN ('outbox_id', 'attempt_id', 'attempt_ordinal')
              ORDER BY CASE a.attname WHEN 'outbox_id' THEN 0 WHEN 'attempt_id' THEN 1 ELSE 2 END))
        AND (SELECT count(DISTINCT a.attname) = 2
          FROM pg_catalog.pg_index i
          JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid
             AND a.attnum = i.indkey[0] AND NOT a.attisdropped
          WHERE i.indrelid = 'recovery_control.initial_gameplay_release_operation'::regclass
            AND i.indisunique AND i.indisvalid AND i.indimmediate
            AND i.indpred IS NULL AND i.indnkeyatts = 1
            AND a.atttypid = 'pg_catalog.uuid'::regtype AND a.attnotnull
            AND a.attname IN ('operation_id', 'maintenance_lock_identity'))
        AND EXISTS (
          SELECT 1
          FROM pg_catalog.pg_index i
          JOIN pg_catalog.pg_attribute op ON op.attrelid = i.indrelid
             AND op.attnum = i.indkey[0] AND NOT op.attisdropped
          JOIN pg_catalog.pg_attribute kind ON kind.attrelid = i.indrelid
             AND kind.attnum = i.indkey[1] AND NOT kind.attisdropped
          WHERE i.indrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND i.indisunique AND i.indisvalid AND i.indimmediate
            AND i.indpred IS NULL AND i.indnkeyatts = 2
            AND op.attname = 'operation_id' AND op.attnotnull
            AND op.atttypid = 'pg_catalog.uuid'::regtype
            AND kind.attname = 'effect_kind' AND kind.attnotnull
            AND kind.atttypid = 'pg_catalog.varchar'::regtype AND kind.atttypmod = 52)
        AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND attname = 'effect_id' AND NOT attisdropped AND attnotnull
            AND atttypid = 'pg_catalog.uuid'::regtype)
        AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND attname = 'payload_digest' AND NOT attisdropped AND attnotnull
            AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)
        AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_attempt_closure'::regclass
            AND attname = 'readback_digest' AND NOT attisdropped AND attnotnull
            AND atttypid = 'pg_catalog.bpchar'::regtype AND atttypmod = 68)
        AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND attname = 'last_outcome_code' AND NOT attisdropped AND NOT attnotnull
            AND (atttypid = 'pg_catalog.text'::regtype
              OR (atttypid = 'pg_catalog.varchar'::regtype
                -- varchar typmod includes the four-byte header. Outcome codes allow 64 characters.
                AND (atttypmod = -1 OR atttypmod - 4 >= 64))))
        AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND attname = 'retry_provenance' AND NOT attisdropped AND NOT attnotnull
            AND atttypid = 'pg_catalog.text'::regtype)
        AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND attname = 'send_outcome_uncertain' AND NOT attisdropped AND NOT attnotnull
            AND atttypid = 'pg_catalog.bool'::regtype)
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
            AND attname IN ('acknowledged_at', 'last_dispatched_at')
            AND NOT attisdropped AND NOT attnotnull
            AND atttypid = 'pg_catalog.timestamptz'::regtype AND atttypmod = -1)
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
        AND NOT EXISTS (
          SELECT outbox_id, attempt_id, attempt_ordinal
          FROM recovery_control.initial_gameplay_release_attempt_rejection WHERE FALSE)
        AND NOT EXISTS (
          SELECT outbox_id, attempt_id, attempt_ordinal, dispatched_at, recorded_at
          FROM recovery_control.initial_gameplay_release_attempt_local_no_send WHERE FALSE)
        AND NOT EXISTS (
          SELECT outbox_id, identity_digest
          FROM recovery_control.initial_gameplay_release_preparation_receipt WHERE FALSE)
      """;

  private final DataSource dataSource;

  RecoveryJournalHealthIndicator(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Health health() {
    if (!JooqInitialGameplayReleaseJournal.driverLoggingIsRedacted()) {
      return Health.down().build();
    }
    // Resolve the journal shape without reading journal rows or changing the database.
    try (var connection = dataSource.getConnection()) {
      if (!connection.getAutoCommit() || connection.isReadOnly()) {
        return Health.down().build();
      }
      try (var statement = connection.prepareStatement(CHECK)) {
        statement.setQueryTimeout(2);
        try (var rows = statement.executeQuery()) {
          return rows.next() && rows.getBoolean(1) ? Health.up().build() : Health.down().build();
        }
      }
    } catch (SQLException unavailable) {
      // The driver exception can contain private configuration.
      return Health.down().build();
    }
  }
}
