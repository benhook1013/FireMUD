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
        AND (SELECT count(DISTINCT version) = 4
         FROM recovery_control.flyway_schema_history_recovery_control
         WHERE success AND (
           (version = '1' AND script = 'V1__initial_gameplay_release_journal.sql')
           OR (version = '2' AND script = 'V2__retain_initial_gameplay_release_attempt_closure.sql')
           OR (version = '3'
               AND script = 'V3__require_dispatch_attempt_for_non_pending_effects.sql')
           OR (version = '4'
               AND script = 'V4__retain_typed_retry_provenance.sql')))
        AND has_column_privilege(
          'recovery_control.initial_gameplay_release_outbox', 'retry_provenance', 'UPDATE')
        AND has_table_privilege(
          'recovery_control.initial_gameplay_release_attempt_local_no_send', 'INSERT')
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
                 last_outcome_code, retry_provenance, readback_identity, readback_payload, readback_digest,
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
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(CHECK)) {
      statement.setQueryTimeout(2);
      try (var rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1) ? Health.up().build() : Health.down().build();
      }
    } catch (SQLException unavailable) {
      // The driver exception can contain private configuration.
      return Health.down().build();
    }
  }
}
