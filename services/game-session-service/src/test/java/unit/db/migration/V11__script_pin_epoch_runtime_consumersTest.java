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
      migration =
          new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    assertThat(migration)
        .contains("CREATE TABLE script_pin_operation")
        .contains("operation_kind IN ('SET', 'ROLLBACK', 'REPIN')")
        .contains("mutation_digest")
        .contains("committed_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP")
        .contains("PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id)")
        .contains("CONSTRAINT ck_script_pin_operation_previous_tuple CHECK")
        .contains("CONSTRAINT ck_script_pin_operation_resulting_tuple CHECK")
        .contains("CONSTRAINT ck_script_pin_operation_expected_pin CHECK")
        .contains("CONSTRAINT ck_script_pin_operation_outcome_error CHECK")
        .contains("outcome = 'COMMITTED' AND error_code IS NULL")
        .contains("outcome = 'FAILED' AND error_code IS NOT NULL AND BTRIM(error_code) <> ''")
        .contains("previous_script_patch_version IS NULL AND previous_script_pin_epoch IS NULL")
        .contains("resulting_script_patch_version IS NULL AND resulting_script_pin_epoch IS NULL")
        .contains("expected_pin_kind = 'EXPECT_UNPINNED' AND expected_script_pin_epoch IS NULL")
        .contains(
            "expected_pin_kind = 'EXPECT_EPOCH' AND expected_script_pin_epoch IS NOT NULL AND expected_script_pin_epoch > 0")
        .contains("expected_pin_kind = 'UNCONDITIONAL' AND expected_script_pin_epoch IS NULL")
        .doesNotContain("expected_pin_kind NOT IN")
        .contains("CREATE INDEX idx_script_pin_operation_instance")
        .contains(
            "ON script_pin_operation (tenant_id, game_instance_id, committed_at, control_plane_request_id)");
    assertThat(migration.split("CHECK \\(", -1)).hasSize(6);
  }
}
