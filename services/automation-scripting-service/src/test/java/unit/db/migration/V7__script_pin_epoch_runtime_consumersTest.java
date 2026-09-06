package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V7__script_pin_epoch_runtime_consumersTest {
  @Test
  void constrainsProjectionAndEventPinTuples() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V7__script_pin_epoch_runtime_consumers.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ");
    assertThat(normalized)
        .contains(
            "ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "ADD COLUMN last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT ''",
            "ADD CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple CHECK",
            "ADD CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple CHECK",
            "script_pin_epoch = 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL",
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL",
            "/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */");
    String notValidMarker = "/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */";
    int firstNotValid = normalized.indexOf(notValidMarker);
    int secondNotValid = normalized.indexOf(notValidMarker, firstNotValid + 1);
    assertThat(firstNotValid).isGreaterThanOrEqualTo(0);
    assertThat(secondNotValid).isGreaterThan(firstNotValid);

    int projectionConstraint =
        normalized.indexOf(
            "ADD CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple CHECK");
    int eventConstraint =
        normalized.indexOf(
            "ADD CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple CHECK");
    assertThat(projectionConstraint).isGreaterThanOrEqualTo(0);
    assertThat(eventConstraint).isGreaterThan(projectionConstraint);
    int eventAlter = normalized.indexOf("ALTER TABLE script_patch_instance_rollout_events");
    assertThat(eventAlter).isGreaterThan(projectionConstraint);
    String projectionBlock = normalized.substring(0, eventAlter);
    String projectionCheck = normalized.substring(projectionConstraint, eventAlter);
    String eventCheck = normalized.substring(eventAlter);
    assertThat(projectionBlock)
        .contains(
            "ALTER TABLE script_patch_instance_rollout_projections ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "ALTER TABLE script_patch_instance_rollout_projections ADD COLUMN last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT ''");
    assertThat(projectionCheck).contains("ck_script_patch_instance_rollout_projections_pin_tuple");
    assertThat(projectionCheck)
        .contains(
            "script_pin_epoch = 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL")
        .contains(
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL");
    assertThat(eventCheck)
        .contains(
            "ALTER TABLE script_patch_instance_rollout_events ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "ALTER TABLE script_patch_instance_rollout_events ADD COLUMN last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT ''",
            "ck_script_patch_instance_rollout_events_pin_tuple")
        .contains(
            "script_pin_epoch = 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL")
        .contains(
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL");
  }
}
