package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14__script_pin_epochTest {
  @Test
  void backfillsOnlyMissingOrNonPositiveEpochsFromPatchPresence() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V14__script_pin_epoch.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ADD COLUMN script_pin_epoch bigint")
        .contains("WHEN NULLIF(BTRIM(script_patch_version), '') IS NULL THEN NULL")
        .contains("ELSE 1")
        .contains("WHERE script_pin_epoch IS NULL OR script_pin_epoch <= 0")
        .contains("CHECK (script_pin_epoch IS NULL OR script_pin_epoch > 0)");
  }
}
