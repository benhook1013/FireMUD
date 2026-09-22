package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V3__persist_plugin_lifecycle_fencesTest {
  @Test
  void requestHistoryIdentityIsTupleWideAndRetainsOperationAsAuditData() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V3__persist_plugin_lifecycle_fences.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains("operation VARCHAR(64) NOT NULL")
        .contains(
            "CONSTRAINT uq_plugin_runtime_request_history_identity UNIQUE ( tenant_id, game_instance_id, plugin_id, control_plane_request_id )")
        .doesNotContain(
            "UNIQUE ( tenant_id, game_instance_id, plugin_id, operation, control_plane_request_id )");
  }

  @Test
  void retainedRuntimeBackfillIsFoundingOnlyAndDoesNotBlessHistoricalWork() throws IOException {
    String migration = readMigration();
    String normalized = migration.replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("plugin_state IN ('ENABLED', 'DRAINING')")
        .contains(
            "V3 cannot establish plugin lifecycle fence for executable runtime state without active plugin version")
        .contains(
            "SET plugin_activation_epoch = 1, lifecycle_revision = 1 WHERE NULLIF(BTRIM(active_plugin_version_id), '') IS NOT NULL")
        .contains("UPDATE script_schedule_instances schedule_instance")
        .contains("schedule_instance.plugin_version_id = runtime_state.active_plugin_version_id")
        .contains("NULLIF(BTRIM(schedule_instance.runtime_region_id), '') IS NOT NULL")
        .contains("NULLIF(BTRIM(runtime_state.runtime_region_id), '') IS NOT NULL")
        .contains("schedule_instance.runtime_region_id = runtime_state.runtime_region_id")
        .contains("schedule_instance.runtime_region_epoch > 0")
        .contains("runtime_state.runtime_region_epoch > 0")
        .contains("schedule_instance.runtime_region_epoch = runtime_state.runtime_region_epoch")
        .contains("SET plugin_activation_epoch = runtime_state.plugin_activation_epoch")
        .contains("lifecycle_revision = runtime_state.lifecycle_revision")
        .contains("their winning admission fence cannot be reconstructed from retention")
        .contains("NULLIF(BTRIM(active_plugin_version_id), '') IS NULL")
        .contains("CONSTRAINT ck_plugin_runtime_states_plugin_fence CHECK")
        .contains("CONSTRAINT ck_plugin_runtime_request_history_plugin_fence CHECK");

    assertThat(normalized.indexOf("UPDATE plugin_runtime_states"))
        .isLessThan(normalized.indexOf("UPDATE script_schedule_instances"));
    assertThat(normalized.indexOf("UPDATE script_schedule_instances"))
        .isLessThan(normalized.indexOf("CREATE TABLE plugin_runtime_request_history"));
    assertThat(normalized)
        .doesNotContain("UPDATE script_work_items")
        .doesNotContain("UPDATE script_event_audit")
        .doesNotContain("UPDATE script_event_ingress_audit")
        .doesNotContain("UPDATE script_handoff_events");
  }

  private String readMigration() throws IOException {
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V3__persist_plugin_lifecycle_fences.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
