package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V4__retain_gameplay_command_script_patch_base_provenanceTest {

  @Test
  void addsTheDurableGameplayCommandScriptPatchBaseContract() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V4__retain_gameplay_command_script_patch_base_provenance.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains(
            "ALTER TABLE gameplay_command ADD COLUMN script_patch_base_version_id bigint",
            "ADD CONSTRAINT ck_gameplay_command_script_patch_base_version_positive CHECK",
            "script_patch_base_version_id IS NULL",
            "script_patch_base_version_id > 0",
            "script_patch_version IS NOT NULL",
            "NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL")
        .doesNotContain("DROP TABLE", "DROP COLUMN", "UPDATE ", "NOT VALID");
  }
}
