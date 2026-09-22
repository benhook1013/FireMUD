package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V25__persist_script_patch_attempt_scopeTest {
  @Test
  void backfillsOnlyExactScriptPatchVersionEvidence() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("UPDATE publish_attempt AS attempt SET base_version_id = (")
        .contains("SELECT version_row.base_version_id FROM version AS version_row")
        .contains("attempt.publish_type = 'SCRIPT_PATCH'")
        .contains("attempt.base_version_id IS NULL")
        .contains("attempt.version_id IS NOT NULL")
        .contains("attempt.script_patch_version IS NOT NULL")
        .contains("version_row.id = attempt.version_id")
        .contains("version_row.tenant_id = attempt.tenant_id")
        .contains("version_row.is_script_only = TRUE")
        .contains("version_row.script_patch_version = attempt.script_patch_version")
        .contains("version_row.base_version_id IS NOT NULL")
        .contains("EXISTS ( SELECT 1 FROM version AS version_row")
        .doesNotContain("SET request_digest");
  }

  @Test
  void quarantinesUnboundPendingPatchEvidenceWithoutDeletingRows() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("SET status = 'FAILED'")
        .contains("failure_code = 'LEGACY_REQUEST_IDENTITY_UNAVAILABLE'")
        .contains(
            "failure_message = 'legacy script-patch attempt lacks a stable publish request identity'")
        .contains("completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)")
        .contains("status = 'PENDING'")
        .contains("request_digest IS NULL")
        .contains("publish_workflow_id IS NULL")
        .contains("publish_workflow_id !~ '^publish-script-patch:[^:]+:publish-request:[^:]+$'")
        .doesNotContain("DELETE FROM publish_attempt")
        .doesNotContain("DELETE FROM version");
  }

  private String readMigration() throws IOException {
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V25__persist_script_patch_attempt_scope.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
