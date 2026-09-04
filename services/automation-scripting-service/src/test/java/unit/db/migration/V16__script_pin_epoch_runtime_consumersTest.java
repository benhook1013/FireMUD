package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V16__script_pin_epoch_runtime_consumersTest {
  @Test
  void addsFailClosedEpochToRolloutProjectionRows() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V16__script_pin_epoch_runtime_consumers.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "ALTER TABLE script_patch_instance_rollout_projections",
            "ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;");
  }
}
