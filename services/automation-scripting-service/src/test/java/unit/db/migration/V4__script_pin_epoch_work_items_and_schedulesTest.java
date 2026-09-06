package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V4__script_pin_epoch_work_items_and_schedulesTest {
  @Test
  void normalizesNullableUnpinnedRequestIdInWorkItemIdentity() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V4__script_pin_epoch_work_items_and_schedules.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "CREATE UNIQUE INDEX uq_script_work_item_trigger_identity ON script_work_items",
            "ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "ADD COLUMN target_scope_type VARCHAR(32) NOT NULL DEFAULT ''",
            "ADD COLUMN target_scope_id VARCHAR(128) NOT NULL DEFAULT ''",
            "WHERE script_pin_epoch > 0",
            "CREATE UNIQUE INDEX uq_script_work_item_trigger_identity_unpinned",
            "WHERE script_pin_epoch = 0",
            "NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NULL",
            "ADD CONSTRAINT ck_script_work_items_pin_tuple CHECK",
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL",
            "/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */")
        .doesNotContain("ADD CONSTRAINT uq_script_work_item_trigger_identity UNIQUE");

    String normalized = migration.replaceAll("\\s+", " ");
    int scheduleAlter = normalized.indexOf("ALTER TABLE script_schedule_instances");
    assertThat(scheduleAlter).isGreaterThanOrEqualTo(0);
    int scheduleConstraint =
        normalized.indexOf(
            "ADD CONSTRAINT ck_script_schedule_instances_pin_tuple CHECK", scheduleAlter);
    int scheduleConstraintEnd = normalized.indexOf(';', scheduleConstraint);
    assertThat(scheduleConstraint).isGreaterThan(scheduleAlter);
    assertThat(scheduleConstraintEnd).isGreaterThan(scheduleConstraint);
    String scheduleBlock = normalized.substring(scheduleAlter, scheduleConstraintEnd);
    assertThat(scheduleBlock)
        .contains(
            "UPDATE script_schedule_instances",
            "ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "DROP CONSTRAINT uq_script_schedule_instance_scope",
            "ADD CONSTRAINT uq_script_schedule_instance_scope UNIQUE",
            "plugin_version_id, binding_id, target_scope_type",
            "SET last_observed_control_plane_request_id = ''",
            "ADD CONSTRAINT ck_script_schedule_instances_pin_tuple CHECK",
            "script_pin_epoch = 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL",
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL",
            "NOT VALID");

    int scheduleUpdate = migration.indexOf("UPDATE script_schedule_instances");
    int scheduleConstraintInMigration =
        migration.indexOf("ADD CONSTRAINT ck_script_schedule_instances_pin_tuple CHECK");
    assertThat(scheduleUpdate).isGreaterThanOrEqualTo(0);
    assertThat(scheduleConstraintInMigration).isGreaterThan(scheduleUpdate);

    int unpinnedStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_work_item_trigger_identity_unpinned");
    assertThat(unpinnedStart).isGreaterThanOrEqualTo(0);
    int unpinnedEnd = migration.indexOf(") WHERE", unpinnedStart);
    assertThat(unpinnedEnd).isGreaterThan(unpinnedStart);
    String unpinnedIndex = migration.substring(unpinnedStart, unpinnedEnd);
    assertThat(unpinnedIndex)
        .contains("script_event_id", "dry_run")
        .doesNotContain("script_pin_control_plane_request_id");

    int pinnedStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_work_item_trigger_identity ON");
    assertThat(pinnedStart).isGreaterThanOrEqualTo(0);
    int pinnedEnd = migration.indexOf(") WHERE", pinnedStart);
    assertThat(pinnedEnd).isGreaterThan(pinnedStart);
    assertThat(migration.substring(pinnedStart, pinnedEnd))
        .contains("script_pin_epoch", "script_event_id", "dry_run")
        .doesNotContain("script_pin_control_plane_request_id");
  }
}
