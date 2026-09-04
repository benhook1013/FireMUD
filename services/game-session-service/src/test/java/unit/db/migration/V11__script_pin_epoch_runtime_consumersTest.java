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
        .contains("PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id)");
  }
}
