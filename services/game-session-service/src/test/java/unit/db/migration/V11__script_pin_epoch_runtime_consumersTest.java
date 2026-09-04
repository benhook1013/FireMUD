package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V11__script_pin_epoch_runtime_consumersTest {
  @Test
  void addsDurablePinOperationLedger() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V11__script_pin_epoch_runtime_consumers.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("CREATE TABLE script_pin_operation")
        .contains("mutation_digest")
        .contains("committed_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP")
        .contains("PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id)")
        .contains("previous_script_patch_version IS NULL AND previous_script_pin_epoch IS NULL")
        .contains("resulting_script_patch_version IS NULL AND resulting_script_pin_epoch IS NULL")
        .contains("expected_pin_kind = 'EXPECT_UNPINNED' AND expected_script_pin_epoch IS NULL")
        .contains(
            "expected_pin_kind = 'EXPECT_EPOCH' AND expected_script_pin_epoch IS NOT NULL AND expected_script_pin_epoch > 0")
        .contains("expected_pin_kind = 'UNCONDITIONAL' AND expected_script_pin_epoch IS NULL")
        .contains("CREATE INDEX idx_script_pin_operation_instance")
        .contains(
            "ON script_pin_operation (tenant_id, game_instance_id, committed_at, control_plane_request_id)");
    assertThat(migration.split("CHECK \\(", -1)).hasSize(4);
  }
}
