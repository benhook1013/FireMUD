package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V15__validate_unpinned_script_pin_metadataTest {
  @Test
  void validatesTheMetadataConstraintAfterAdditiveInstallation() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V15__validate_unpinned_script_pin_metadata.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration.replaceAll("\\s+", " ").trim())
        .isEqualTo(
            "-- [jooq ignore start] ALTER TABLE game_instances VALIDATE CONSTRAINT game_instances_unpinned_script_pin_metadata_coherent; -- [jooq ignore stop]");
  }
}
