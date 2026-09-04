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

    assertThat(migration)
        .contains(
            "ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0",
            "ADD COLUMN last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT ''",
            "ADD CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple CHECK",
            "ADD CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple CHECK",
            "script_pin_epoch = 0",
            "last_observed_control_plane_request_id = ''",
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL");

    int projectionConstraint =
        migration.indexOf(
            "ADD CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple CHECK");
    int eventConstraint =
        migration.indexOf("ADD CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple CHECK");
    assertThat(projectionConstraint).isGreaterThanOrEqualTo(0);
    assertThat(eventConstraint).isGreaterThan(projectionConstraint);
  }
}
