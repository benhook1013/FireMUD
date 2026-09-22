package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V28__quarantine_legacy_unattested_full_versionsTest {
  @Test
  void quarantinesOnlyUnattestedFullVersionsWithTheV13Marker() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains(
            "UPDATE version AS version_row SET version_state = 'FAILED', version_state_epoch = version_row.version_state_epoch + 1, updated_at = CURRENT_TIMESTAMP")
        .contains("version_row.is_script_only = FALSE")
        .contains("version_row.version_state = 'PUBLISHED'")
        .contains("version_row.version_state_epoch = 1")
        .contains("AND NOT EXISTS ( SELECT 1 FROM published_release_bundle AS bundle")
        .contains("bundle.tenant_id = version_row.tenant_id")
        .contains("bundle.version_id = version_row.id")
        .doesNotContain("DELETE FROM version");
  }

  @Test
  void preservesBundledAndScriptOnlyRowsAndIsIdempotent() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("version_row.is_script_only = FALSE")
        .contains("version_row.version_state_epoch = 1")
        .contains("version_state_epoch = version_row.version_state_epoch + 1")
        .contains("NOT EXISTS ( SELECT 1 FROM published_release_bundle AS bundle")
        .doesNotContain("version_row.is_script_only = TRUE")
        .doesNotContain("DELETE FROM published_release_bundle")
        .doesNotContain("DELETE FROM version");
  }

  private String readMigration() throws IOException {
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V28__quarantine_legacy_unattested_full_versions.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
