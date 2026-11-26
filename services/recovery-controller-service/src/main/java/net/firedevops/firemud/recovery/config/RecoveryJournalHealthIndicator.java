package net.firedevops.firemud.recovery.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
final class RecoveryJournalHealthIndicator implements HealthIndicator {
  private static final String CHECK =
      """
      SELECT
        current_setting('transaction_read_only') = 'off'
        AND (SELECT count(DISTINCT version) = 5
         FROM recovery_control.flyway_schema_history_recovery_control
         WHERE success AND (
           (version = '1' AND script = 'V1__initial_gameplay_release_journal.sql')
           OR (version = '2' AND script = 'V2__retain_initial_gameplay_release_attempt_closure.sql')
           OR (version = '3'
               AND script = 'V3__require_dispatch_attempt_for_non_pending_effects.sql')
           OR (version = '4'
               AND script = 'V4__retain_typed_retry_provenance.sql')
           OR (version = '5'
               AND script = 'V5__retain_local_dispatch_uncertainty.sql')))
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
          SELECT 1 FROM pg_catalog.pg_attribute
          WHERE attrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
            AND attname = 'send_outcome_uncertain' AND NOT attisdropped
            AND atttypid = 'pg_catalog.bool'::regtype)
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
      """;

  private final DataSource dataSource;

  RecoveryJournalHealthIndicator(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Health health() {
    // Resolve the journal shape without reading journal rows or changing the database.
    try (var connection = dataSource.getConnection()) {
      if (!connection.getAutoCommit()) {
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
