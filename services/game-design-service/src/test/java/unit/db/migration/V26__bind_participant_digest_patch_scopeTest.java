package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V26__bind_participant_digest_patch_scopeTest {
  @Test
  void scopesAttemptEvidenceFromItsExactParentAttempt() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains(
            "UPDATE publish_attempt_participant_digest AS participant SET base_version_id = ( SELECT attempt.base_version_id FROM publish_attempt AS attempt")
        .contains("attempt.id = participant.publish_attempt_id")
        .contains("attempt.publish_type = 'SCRIPT_PATCH'")
        .contains("participant.base_version_id IS NULL")
        .contains("attempt.base_version_id IS NOT NULL")
        .contains("attempt.base_version_id > 0");
  }

  @Test
  void scopesRecordedEvidenceOnlyFromOnePublishedScriptPatchBase() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains(
            "UPDATE publish_recorded_participant_digest AS recorded SET base_version_id = ( SELECT MIN(version_row.base_version_id) FROM version AS version_row")
        .contains("version_row.tenant_id = recorded.tenant_id")
        .contains("version_row.script_patch_version = recorded.scope_value")
        .contains("version_row.is_script_only = TRUE")
        .contains("version_row.version_state = 'PUBLISHED'")
        .contains("version_row.base_version_id > 0")
        .contains("HAVING COUNT(DISTINCT version_row.base_version_id) = 1")
        .doesNotContain("DELETE FROM publish_recorded_participant_digest");
  }

  @Test
  void failsClosedForUnscopedPatchEvidenceButPreservesFullVersionNulls() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("SELECT CAST(NULLIF( CASE WHEN EXISTS (")
        .contains("attempt.publish_type = 'SCRIPT_PATCH'")
        .contains("participant.base_version_id IS NULL")
        .contains("recorded.publish_type = 'SCRIPT_PATCH'")
        .contains("recorded.base_version_id IS NULL")
        .contains("THEN 'V26 unresolved SCRIPT_PATCH attempt participant evidence'")
        .contains("THEN 'V26 unresolved SCRIPT_PATCH recorded participant evidence'")
        .contains("chk_recorded_participant_digest_patch_scope")
        .contains("CHECK (publish_type <> 'SCRIPT_PATCH' OR base_version_id IS NOT NULL)")
        .contains("chk_recorded_participant_digest_full_scope")
        .contains("CHECK (publish_type <> 'FULL_VERSION' OR base_version_id IS NULL)")
        .contains("Full-version rows")
        .doesNotContain("WHERE recorded.base_version_id IS NULL THEN DELETE");
  }

  private String readMigration() throws IOException {
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V26__bind_participant_digest_patch_scope.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
