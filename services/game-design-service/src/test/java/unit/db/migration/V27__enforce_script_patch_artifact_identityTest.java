package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V27__enforce_script_patch_artifact_identityTest {
  @Test
  void failsClosedOnIncompleteOrDuplicateRetainedScriptPatchIdentity() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("is_script_only = TRUE")
        .contains("base_version_id IS NULL")
        .contains("base_version_id <= 0")
        .contains("script_patch_version IS NULL")
        .contains("script_patch_version = ''")
        .contains("GROUP BY tenant_id, base_version_id, script_patch_version")
        .contains("HAVING COUNT(*) > 1")
        .contains("V27 duplicate retained script-only effective artifact identity")
        .contains("V27 script-only version has incomplete effective artifact identity")
        .doesNotContain("DELETE FROM version")
        .doesNotContain("DELETE FROM publish_attempt");
  }

  @Test
  void preservesFullVersionRowsWhileAddingPartialUniqueIndex() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("chk_script_only_effective_artifact_identity")
        .contains("is_script_only = FALSE")
        .contains("base_version_id > 0")
        .contains("script_patch_version <> ''")
        .contains("uq_version_script_patch_effective_artifact")
        .contains(
            "ON version (tenant_id, base_version_id, script_patch_version) WHERE is_script_only = TRUE");
  }

  private String readMigration() throws IOException {
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V27__enforce_script_patch_artifact_identity.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
