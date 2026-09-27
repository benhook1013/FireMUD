package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V31__retain_exact_script_patch_base_provenanceTest {
  @Test
  void retainsExactBaseAcrossOwnerAndReplayRowsWithoutInventingHistoricalEvidence()
      throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V3.1__retain_exact_script_patch_base_provenance.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains("CREATE TABLE script_patch_base_bindings")
        .contains("PRIMARY KEY (tenant_id, script_patch_version)")
        .contains("base_version_id BIGINT NOT NULL")
        .contains("ADD COLUMN base_version_id BIGINT")
        .contains("ADD COLUMN script_patch_base_version_id BIGINT")
        .contains("ADD COLUMN pinned_script_patch_base_version_id BIGINT")
        .contains("ck_script_patch_base_bindings_positive_base")
        .contains("ck_script_schedule_instances_positive_patch_base")
        .doesNotContain("UPDATE scripts")
        .doesNotContain("UPDATE script_event_bindings")
        .doesNotContain("UPDATE script_schedule_instances");
  }
}
