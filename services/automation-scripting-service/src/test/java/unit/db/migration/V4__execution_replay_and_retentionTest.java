package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V4__execution_replay_and_retentionTest {

  @Test
  void declaresTheTenantScopedExecutionReplayAndRetentionContract() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V4__execution_replay_and_retention.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains(
            "ADD COLUMN failure_generation BIGINT NOT NULL DEFAULT 0",
            "ADD COLUMN authority_unavailable_since TIMESTAMP",
            "ADD COLUMN authority_unavailable_count INTEGER NOT NULL DEFAULT 0",
            "ADD COLUMN next_eligible_at TIMESTAMP",
            "CREATE TABLE script_dead_letter_replay_requests",
            "CREATE TABLE script_dead_letter_replay_results",
            "requested_work_item_id BIGINT NOT NULL",
            "work_item_id BIGINT",
            "original_failure_stage VARCHAR(64) NOT NULL DEFAULT ''",
            "original_failure_reason VARCHAR(256) NOT NULL DEFAULT ''",
            "failure_reason VARCHAR(256) NOT NULL DEFAULT ''",
            "FOREIGN KEY (tenant_id, replay_request_id) REFERENCES script_dead_letter_replay_requests (tenant_id, id)",
            "FOREIGN KEY (tenant_id, work_item_id) REFERENCES script_work_items (tenant_id, id)",
            "UNIQUE (replay_request_id, requested_work_item_id)",
            "CONSTRAINT ck_script_dead_letter_replay_request_fingerprint CHECK ( request_fingerprint ~ '^[0-9a-f]{64}$' )",
            "CONSTRAINT ck_script_dead_letter_replay_request_status CHECK ( status IN ('RUNNING', 'COMPLETED') )",
            "CONSTRAINT ck_script_dead_letter_replay_request_counts CHECK ( replayed_count >= 0 AND rejected_count >= 0 )",
            "CONSTRAINT ck_script_dead_letter_replay_result_plugin_fence CHECK ( (plugin_activation_epoch = 0 AND lifecycle_revision = 0) OR (plugin_activation_epoch > 0 AND lifecycle_revision > 0) )",
            "CONSTRAINT ck_script_dead_letter_replay_result_nonnegative_evidence CHECK ( script_pin_epoch >= 0 AND failure_generation >= 0 )",
            "ADD COLUMN retention_hold_until TIMESTAMPTZ NULL",
            "idx_script_work_items_execution_fences",
            "idx_script_work_items_retry_eligibility",
            "idx_script_dead_letter_replay_results_request",
            "idx_script_dead_letter_replay_results_work_item",
            "idx_script_event_audit_retention",
            "idx_script_handoff_events_retention",
            "idx_script_dead_letter_replay_requests_retention",
            "idx_script_dead_letter_replay_results_retention")
        .doesNotContain("IF NOT EXISTS", "DROP TABLE", "DROP COLUMN", "DELETE FROM");

    assertThat(normalized)
        .contains(
            "UPDATE script_work_items SET failure_generation = 1 WHERE status = 'DEAD_LETTERED' AND failure_generation = 0");

    assertThat(normalized)
        .contains(
            "CONSTRAINT uq_script_dead_letter_replay_request UNIQUE ( tenant_id, control_plane_request_id )",
            "CONSTRAINT uq_script_dead_letter_replay_request_tenant_id UNIQUE (tenant_id, id)");

    String resultTable = tableBlock(normalized, "script_dead_letter_replay_results");
    assertThat(resultTable)
        .contains(
            "requested_work_item_id BIGINT NOT NULL",
            "work_item_id BIGINT",
            "CONSTRAINT uq_script_dead_letter_replay_result UNIQUE (replay_request_id, requested_work_item_id)")
        .doesNotContain(" work_item_id BIGINT NOT NULL");
  }

  private static String tableBlock(String normalized, String tableName) {
    String marker = "CREATE TABLE " + tableName + " (";
    int start = normalized.indexOf(marker);
    assertThat(start).isGreaterThanOrEqualTo(0);
    int end = normalized.indexOf("CREATE TABLE ", start + marker.length());
    return normalized.substring(start, end < 0 ? normalized.length() : end);
  }
}
