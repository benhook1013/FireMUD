package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V9__script_pin_epoch_runtime_consumersTest {
  @Test
  void addsEpochToDurableGameplayAndRemoteConsumers() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V9__script_pin_epoch_runtime_consumers.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ALTER TABLE gameplay_command")
        .contains("ALTER TABLE remote_command_coordinator")
        .contains("ALTER TABLE remote_followup")
        .contains("ADD COLUMN script_pin_epoch bigint");
  }
}
