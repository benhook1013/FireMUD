package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
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
  void scopesRecordedEvidenceOnlyFromOneRetainedScriptPatchBase() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains(
            "UPDATE publish_recorded_participant_digest AS recorded SET base_version_id = ( SELECT MIN(version_row.base_version_id) FROM version AS version_row")
        .doesNotContain("version_row.version_state")
        .contains("version_row.base_version_id > 0")
        .contains("HAVING COUNT(DISTINCT version_row.base_version_id) = 1")
        .contains("V26 unresolved SCRIPT_PATCH recorded participant evidence")
        .doesNotContain("DELETE FROM publish_recorded_participant_digest");

    assertSqlOccurrences(normalized, "version_row.tenant_id = recorded.tenant_id", 2);
    assertSqlOccurrences(normalized, "version_row.script_patch_version = recorded.scope_value", 2);
    assertSqlOccurrences(normalized, "version_row.is_script_only = TRUE", 2);
    assertSqlOccurrences(normalized, "version_row.base_version_id IS NOT NULL", 2);
    assertSqlOccurrences(normalized, "version_row.base_version_id > 0", 2);
    assertSqlOccurrences(normalized, "COUNT(DISTINCT version_row.base_version_id)", 2);
  }

  private void assertSqlOccurrences(String sql, String fragment, int expectedCount) {
    assertThat(sql.split(Pattern.quote(fragment), -1)).hasSize(expectedCount + 1);
  }

  @Test
  void failsClosedForUnscopedPatchEvidenceButPreservesFullVersionNulls() throws IOException {
    String normalized = readMigration().replaceAll("\\s+", " ").trim();

    assertThat(normalized)
        .contains("SELECT CAST(NULLIF( CASE WHEN EXISTS (")
        .contains("attempt.publish_type = 'SCRIPT_PATCH'")
        .contains(
            "WHERE attempt.publish_type = 'SCRIPT_PATCH' AND attempt.status <> 'FAILED' AND participant.base_version_id IS NULL")
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

  @Test
  void ignoresFailedAttemptEvidenceButRejectsPendingAndSucceededUnscopedAttempts()
      throws IOException, SQLException {
    String guard = extractAttemptScopeGuard(readMigration());

    try (Connection connection =
        DriverManager.getConnection("jdbc:h2:mem:v26_attempt_scope_guard")) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE publish_attempt (id BIGINT, publish_type VARCHAR(32), status VARCHAR(16))");
        statement.execute(
            "CREATE TABLE publish_attempt_participant_digest (publish_attempt_id BIGINT, "
                + "base_version_id BIGINT)");
        statement.execute(
            "CREATE TABLE publish_recorded_participant_digest (publish_type VARCHAR(32), base_version_id BIGINT)");
        statement.execute("INSERT INTO publish_attempt VALUES (1, 'SCRIPT_PATCH', 'FAILED')");
        statement.execute("INSERT INTO publish_attempt_participant_digest VALUES (1, NULL)");

        assertAttemptScopeGuardPasses(statement, guard);

        statement.execute("UPDATE publish_attempt SET status = 'PENDING' WHERE id = 1");
        assertAttemptScopeGuardFails(statement, guard);

        statement.execute("UPDATE publish_attempt SET status = 'SUCCEEDED' WHERE id = 1");
        assertAttemptScopeGuardFails(statement, guard);
      }
    }
  }

  private String extractAttemptScopeGuard(String migration) {
    String marker = "THEN 'V26 unresolved SCRIPT_PATCH attempt participant evidence'";
    int markerIndex = migration.indexOf(marker);
    int statementStart = migration.lastIndexOf("SELECT CAST(NULLIF(", markerIndex);
    int statementEnd = migration.indexOf(';', markerIndex);

    assertThat(markerIndex).isGreaterThanOrEqualTo(0);
    assertThat(statementStart).isGreaterThanOrEqualTo(0);
    assertThat(statementEnd).isGreaterThan(markerIndex);
    return migration.substring(statementStart, statementEnd);
  }

  private void assertAttemptScopeGuardPasses(Statement statement, String guard)
      throws SQLException {
    try (ResultSet result = statement.executeQuery(guard)) {
      assertThat(result.next()).isTrue();
      assertThat(result.getObject(1)).isNull();
    }
  }

  private void assertAttemptScopeGuardFails(Statement statement, String guard) {
    assertThatThrownBy(
            () -> {
              try (ResultSet ignored = statement.executeQuery(guard)) {
                ignored.next();
                ignored.getObject(1);
              }
            })
        .isInstanceOf(SQLException.class);
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
