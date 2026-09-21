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
}
