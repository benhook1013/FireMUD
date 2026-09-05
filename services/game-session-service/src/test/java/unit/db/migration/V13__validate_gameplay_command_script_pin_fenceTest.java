package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V13__validate_gameplay_command_script_pin_fenceTest {
  @Test
  void validatesTheGameplayCommandScriptPinFenceInItsOwnMigration() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V13__validate_gameplay_command_script_pin_fence.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration.replaceAll("\\s+", " ").trim())
        .isEqualTo(
            "-- [jooq ignore start] ALTER TABLE gameplay_command VALIDATE CONSTRAINT gameplay_command_script_pin_tuple_coherent; -- [jooq ignore stop]");
  }
}
