package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V1__baselineTest {

  @Test
  void containsTheFinalAutomationSchemaAsDirectBaselineDdl() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V1__baseline.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains(
            "CREATE TABLE scripts",
            "CREATE TABLE script_event_ingress_audit",
            "CREATE TABLE script_event_bindings",
            "CREATE TABLE script_work_items",
            "CREATE TABLE script_event_audit",
            "CREATE TABLE plugin_runtime_states",
            "CREATE TABLE script_patch_pin_projections",
            "CREATE TABLE script_patch_instance_rollout_projections",
            "CREATE TABLE automation_admission_states",
            "CREATE TABLE script_patch_instance_rollout_events",
            "CREATE TABLE plugin_runtime_events",
            "CREATE TABLE script_handoff_events",
            "CREATE TABLE script_schedule_definitions",
            "CREATE TABLE script_schedule_instances",
            "CREATE TABLE script_patch_readiness_projections")
        .doesNotContain(
            "ALTER TABLE",
            "ADD COLUMN",
            "ADD CONSTRAINT",
            "DROP CONSTRAINT",
            "DROP INDEX",
            "UPDATE ",
            "DELETE FROM",
            "INSERT INTO",
            "NOT VALID",
            "VALIDATE CONSTRAINT",
            "IF NOT EXISTS",
            "uq_script_event_ingress_audit_runtime_unpinned_identity");

    String ingress = tableBlock(normalized, "script_event_ingress_audit");
    assertThat(ingress)
        .contains(
            "playable_state_scope VARCHAR(32) DEFAULT ''",
            "world_slug VARCHAR(64) DEFAULT ''",
            "realm_slug VARCHAR(64) DEFAULT ''",
            "pointer_version VARCHAR(64) DEFAULT ''",
            "quota_class VARCHAR(64) NOT NULL DEFAULT 'STANDARD_RUNTIME'",
            "script_pin_epoch BIGINT",
            "script_pin_control_plane_request_id VARCHAR(256)",
            "request_digest VARCHAR(64) NOT NULL DEFAULT ''",
            "claim_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP",
            "CONSTRAINT ck_script_event_ingress_audit_request_digest CHECK ( request_digest = '' OR request_digest ~ '^[0-9a-f]{64}$' )",
            "CONSTRAINT ck_script_event_ingress_audit_pin_tuple CHECK ( (script_pin_epoch IS NULL AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NULL) OR (script_pin_epoch > 0 AND game_instance_id IS NOT NULL AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL) )");

    assertIndexColumns(
        normalized,
        "uq_script_event_ingress_audit_runtime_identity",
        "tenant_id",
        "game_instance_id",
        "region_id",
        "region_epoch",
        "entity_id",
        "playable_state_scope",
        "event_type",
        "event_schema_version",
        "script_patch_version",
        "script_pin_epoch",
        "script_event_id",
        "dry_run",
        "source_service");
    assertThat(indexStatement(normalized, "uq_script_event_ingress_audit_runtime_identity"))
        .endsWith(") WHERE game_instance_id IS NOT NULL AND script_pin_epoch IS NOT NULL");
    assertIndexColumns(
        normalized,
        "uq_script_event_ingress_audit_onload_identity",
        "tenant_id",
        "script_id",
        "event_type",
        "event_schema_version",
        "script_patch_version",
        "script_event_id",
        "dry_run",
        "source_service");
    assertThat(indexStatement(normalized, "uq_script_event_ingress_audit_onload_identity"))
        .endsWith(
            ") NULLS NOT DISTINCT WHERE game_instance_id IS NULL AND script_pin_epoch IS NULL");

    String bindings = tableBlock(normalized, "script_event_bindings");
    assertThat(bindings)
        .contains(
            "binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "CONSTRAINT uq_script_event_binding UNIQUE ( tenant_id, script_patch_version, event_type, event_schema_version, script_id, binding_id, target_scope_type, target_scope_id )");
    assertIndexColumns(
        normalized,
        "idx_script_event_bindings_resolution",
        "tenant_id",
        "script_patch_version",
        "event_type",
        "event_schema_version",
        "enabled",
        "priority",
        "script_id",
        "binding_id",
        "id");

    String workItems = tableBlock(normalized, "script_work_items");
    assertThat(workItems)
        .contains(
            "plugin_id VARCHAR(128) NOT NULL DEFAULT ''",
            "plugin_version_id VARCHAR(128) NOT NULL DEFAULT ''",
            "quota_class VARCHAR(64) NOT NULL DEFAULT 'STANDARD_RUNTIME'",
            "script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "script_pin_control_plane_request_id VARCHAR(256)",
            "binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "target_scope_type VARCHAR(32) NOT NULL DEFAULT ''",
            "target_scope_id VARCHAR(128) NOT NULL DEFAULT ''",
            "CONSTRAINT ck_script_work_items_pin_tuple CHECK ( (script_pin_epoch = 0 AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NULL) OR (script_pin_epoch > 0 AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL) )");
    assertIndexColumns(
        normalized,
        "uq_script_work_item_trigger_identity",
        "tenant_id",
        "game_instance_id",
        "region_id",
        "region_epoch",
        "entity_id",
        "playable_state_scope",
        "world_slug",
        "realm_slug",
        "pointer_version",
        "script_id",
        "plugin_id",
        "plugin_version_id",
        "binding_id",
        "event_type",
        "event_schema_version",
        "script_patch_version",
        "script_pin_epoch",
        "script_event_id",
        "dry_run");
    assertThat(indexStatement(normalized, "uq_script_work_item_trigger_identity"))
        .endsWith(") WHERE script_pin_epoch > 0");
    assertIndexColumns(
        normalized,
        "uq_script_work_item_trigger_identity_unpinned",
        "tenant_id",
        "game_instance_id",
        "region_id",
        "region_epoch",
        "entity_id",
        "playable_state_scope",
        "world_slug",
        "realm_slug",
        "pointer_version",
        "script_id",
        "plugin_id",
        "plugin_version_id",
        "binding_id",
        "event_type",
        "event_schema_version",
        "script_patch_version",
        "script_event_id",
        "dry_run");
    assertThat(indexStatement(normalized, "uq_script_work_item_trigger_identity_unpinned"))
        .endsWith(") WHERE script_pin_epoch = 0");

    String eventAudit = tableBlock(normalized, "script_event_audit");
    assertThat(eventAudit)
        .contains(
            "script_pin_epoch BIGINT",
            "script_pin_control_plane_request_id VARCHAR(256)",
            "binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "target_scope_type VARCHAR(32) NOT NULL DEFAULT ''",
            "work_item_id BIGINT REFERENCES script_work_items(id)",
            "CONSTRAINT ck_script_event_audit_pin_tuple CHECK ( (script_pin_epoch IS NULL AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NULL) OR (script_pin_epoch > 0 AND game_instance_id IS NOT NULL AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL) )");
    assertThat(indexStatement(normalized, "uq_script_event_audit_handler_identity"))
        .doesNotContain("script_pin_control_plane_request_id")
        .endsWith(") WHERE script_pin_epoch > 0");
    assertThat(indexStatement(normalized, "uq_script_event_audit_handler_identity_unpinned"))
        .endsWith(") WHERE script_pin_epoch IS NULL");

    String pinProjection = tableBlock(normalized, "script_patch_pin_projections");
    assertThat(pinProjection)
        .contains(
            "script_pin_epoch BIGINT",
            "CONSTRAINT ck_script_patch_pin_projections_pin_tuple CHECK ( ((script_pin_epoch IS NULL) AND NULLIF(BTRIM(observed_pinned_script_patch_version), '') IS NULL AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL) OR (script_pin_epoch IS NOT NULL AND script_pin_epoch > 0 AND NULLIF(BTRIM(observed_pinned_script_patch_version), '') IS NOT NULL AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL) )")
        .doesNotContain("script_pin_epoch = 0");

    assertThat(tableBlock(normalized, "script_patch_instance_rollout_projections"))
        .contains(
            "script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT ''",
            "CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple CHECK");
    assertThat(tableBlock(normalized, "script_patch_instance_rollout_events"))
        .contains(
            "script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT ''",
            "CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple CHECK");

    String handoffs = tableBlock(normalized, "script_handoff_events");
    assertThat(handoffs)
        .contains(
            "plugin_id VARCHAR(128) NOT NULL DEFAULT ''",
            "plugin_version_id VARCHAR(128) NOT NULL DEFAULT ''",
            "script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "script_pin_control_plane_request_id VARCHAR(256)",
            "binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "work_item_id BIGINT NOT NULL REFERENCES script_work_items(id)",
            "CONSTRAINT ck_script_handoff_events_pin_tuple CHECK");

    String schedules = tableBlock(normalized, "script_schedule_instances");
    assertThat(schedules)
        .contains(
            "plugin_id VARCHAR(128) NOT NULL DEFAULT ''",
            "plugin_version_id VARCHAR(128) NOT NULL DEFAULT ''",
            "target_scope_type VARCHAR(32) NOT NULL DEFAULT ''",
            "target_scope_id VARCHAR(128) NOT NULL DEFAULT ''",
            "script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "last_observed_control_plane_request_id VARCHAR(128) NOT NULL DEFAULT ''",
            "CONSTRAINT uq_script_schedule_instance_scope UNIQUE ( tenant_id, game_instance_id, playable_state_scope, plugin_id, plugin_version_id, binding_id, target_scope_type, target_scope_id, schedule_definition_id )",
            "CONSTRAINT ck_script_schedule_instances_pin_tuple CHECK");
  }

  private static String tableBlock(String normalized, String tableName) {
    String marker = "CREATE TABLE " + tableName + " (";
    int start = normalized.indexOf(marker);
    assertThat(start).isGreaterThanOrEqualTo(0);
    int end = normalized.indexOf("CREATE TABLE ", start + marker.length());
    return normalized.substring(start, end < 0 ? normalized.length() : end);
  }

  private static void assertIndexColumns(
      String normalized, String indexName, String... expectedColumns) {
    String statement = indexStatement(normalized, indexName);
    int open = statement.indexOf('(');
    int close = statement.lastIndexOf(')');
    assertThat(open).isGreaterThanOrEqualTo(0);
    assertThat(close).isGreaterThan(open);
    assertThat(statement.substring(open + 1, close).trim())
        .isEqualTo(String.join(", ", expectedColumns));
  }

  private static String indexStatement(String normalized, String indexName) {
    int index = normalized.indexOf(indexName);
    assertThat(index).isGreaterThanOrEqualTo(0);
    int start = normalized.lastIndexOf("CREATE ", index);
    int end = normalized.indexOf(';', index);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(index);
    return normalized.substring(start, end);
  }
}
