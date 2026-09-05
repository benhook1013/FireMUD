package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V7__retention_disposition_for_script_evidenceTest {
  @Test
  void addsTenantQualifiedHoldAndRetentionIndexesToEveryHighChurnFamily() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V7__retention_disposition_for_script_evidence.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalizedMigration = migration.replaceAll("\\s+", " ").trim();

    assertThat(normalizedMigration)
        .contains("script_event_audit ADD COLUMN retention_hold_until TIMESTAMPTZ NULL")
        .contains("script_handoff_events ADD COLUMN retention_hold_until TIMESTAMPTZ NULL")
        .contains(
            "script_dead_letter_replay_requests ADD COLUMN retention_hold_until TIMESTAMPTZ NULL")
        .contains(
            "script_dead_letter_replay_results ADD COLUMN retention_hold_until TIMESTAMPTZ NULL")
        .contains("idx_script_event_audit_retention")
        .contains("idx_script_handoff_events_retention")
        .contains("idx_script_dead_letter_replay_requests_retention")
        .contains("idx_script_dead_letter_replay_results_retention")
        .contains("ON script_event_audit (updated_at, retention_hold_until, tenant_id)")
        .contains("ON script_handoff_events (observed_at, retention_hold_until, tenant_id)")
        .contains(
            "ON script_dead_letter_replay_requests (updated_at, retention_hold_until, tenant_id)")
        .contains(
            "ON script_dead_letter_replay_results (created_at, retention_hold_until, tenant_id)")
        .contains("tenant_id")
        .doesNotContain("retention_hold_until TIMESTAMP NULL");
  }
}
