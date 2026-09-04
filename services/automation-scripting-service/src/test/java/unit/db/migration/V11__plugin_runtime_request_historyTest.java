package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V11__plugin_runtime_request_historyTest {
  @Test
  void createsImmutableScopedPluginRequestHistory() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V11__plugin_runtime_request_history.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("CREATE TABLE plugin_runtime_request_history")
        .contains("request_fingerprint VARCHAR(64) NOT NULL")
        .containsPattern(
            "(?s)CONSTRAINT uq_plugin_runtime_request_history_identity\\s+"
                + "UNIQUE \\(\\s*tenant_id,\\s*game_instance_id,\\s*plugin_id,\\s*"
                + "operation,\\s*control_plane_request_id\\s*\\)");
  }
}
