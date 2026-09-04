package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V8__script_pin_epochTest {
  @Test
  void addsEpochAndEnforcesCompleteTuple() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V8__script_pin_epoch.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ADD COLUMN script_pin_epoch bigint")
        .contains("ADD CONSTRAINT game_instances_script_pin_tuple_coherent")
        .contains("script_pin_epoch IS NULL")
        .contains("script_pin_epoch IS NOT NULL")
        .contains("script_pin_epoch > 0")
        .doesNotContain("NOT VALID")
        .doesNotContain("UPDATE game_instances");
  }
}
