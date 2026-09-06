package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V16__validate_game_instance_script_pin_tupleTest {
  @Test
  void validatesTheGameInstanceScriptPinTupleAfterAdditiveInstallation() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V16__validate_game_instance_script_pin_tuple.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration.replaceAll("\\s+", " ").trim())
        .isEqualTo(
            "-- [jooq ignore start] ALTER TABLE game_instances VALIDATE CONSTRAINT game_instances_script_pin_tuple_coherent; -- [jooq ignore stop]");
  }
}
