package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V10__validate_script_pin_epoch_constraintsTest {
  @Test
  void validatesAllPinEpochConstraintsInASeparateMigration() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V10__validate_script_pin_epoch_constraints.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "/* [jooq ignore start] */",
            "VALIDATE CONSTRAINT ck_script_patch_pin_projections_pin_tuple",
            "VALIDATE CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple",
            "VALIDATE CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple",
            "/* [jooq ignore stop] */")
        .doesNotContain("NOT VALID");

    int projectionValidation =
        migration.indexOf("VALIDATE CONSTRAINT ck_script_patch_pin_projections_pin_tuple");
    int rolloutProjectionValidation =
        migration.indexOf(
            "VALIDATE CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple");
    int rolloutEventValidation =
        migration.indexOf("VALIDATE CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple");
    assertThat(projectionValidation).isGreaterThanOrEqualTo(0);
    assertThat(rolloutProjectionValidation).isGreaterThan(projectionValidation);
    assertThat(rolloutEventValidation).isGreaterThan(rolloutProjectionValidation);
  }
}
