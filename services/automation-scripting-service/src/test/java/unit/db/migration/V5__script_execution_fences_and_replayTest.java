package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V5__script_execution_fences_and_replayTest {
  @Test
  void bindsReplayEvidenceToTenantQualifiedOwners() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V5__script_execution_fences_and_replay.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("UNIQUE (tenant_id, id)")
        .contains("FOREIGN KEY (tenant_id, replay_request_id)")
        .contains("REFERENCES script_dead_letter_replay_requests (tenant_id, id)")
        .contains("FOREIGN KEY (tenant_id, work_item_id)")
        .contains("REFERENCES script_work_items (tenant_id, id)")
        .doesNotContain("replay_request_id BIGINT NOT NULL REFERENCES")
        .containsPattern(
            "(?s)ALTER TABLE script_work_items\\s+"
                + "ADD COLUMN failure_generation BIGINT NOT NULL DEFAULT 1;")
        .containsPattern(
            "(?s)CREATE INDEX idx_script_work_items_execution_fences\\s+"
                + "ON script_work_items\\s*\\(\\s*"
                + "tenant_id,\\s*game_instance_id,\\s*script_patch_version,\\s*"
                + "script_pin_epoch,\\s*plugin_id,\\s*plugin_activation_epoch,\\s*"
                + "lifecycle_revision,\\s*status\\s*\\);");
  }
}
