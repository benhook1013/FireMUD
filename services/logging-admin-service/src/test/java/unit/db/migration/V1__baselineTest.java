package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V1__baselineTest {

  @Test
  void containsTheCompleteLoggingSchemaAsDirectBaselineDdl() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V1__baseline.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains(
            "CREATE TABLE moderation_actions",
            "CREATE TABLE player_reports",
            "CREATE TABLE log_events")
        .doesNotContain(
            "ALTER TABLE",
            "ADD COLUMN",
            "ADD CONSTRAINT",
            "DROP CONSTRAINT",
            "DROP INDEX",
            "UPDATE ",
            "DELETE FROM",
            "INSERT INTO",
            "NOT VALID",
            "VALIDATE CONSTRAINT",
            "IF NOT EXISTS");

    String moderationActions = tableBlock(normalized, "moderation_actions");
    assertThat(moderationActions)
        .contains(
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "account_id BIGINT NOT NULL",
            "action VARCHAR(20) NOT NULL",
            "reason VARCHAR(255)",
            "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP",
            "expires_at TIMESTAMP NULL");

    String playerReports = tableBlock(normalized, "player_reports");
    assertThat(playerReports)
        .contains(
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "reporter_account_id BIGINT NOT NULL",
            "target_account_id BIGINT",
            "type VARCHAR(20) NOT NULL",
            "description VARCHAR(255) NOT NULL",
            "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");

    String logEvents = tableBlock(normalized, "log_events");
    assertThat(logEvents)
        .contains(
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "type VARCHAR(50) NOT NULL",
            "message VARCHAR(255) NOT NULL",
            "timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP",
            "account_id BIGINT");

    assertIndexColumns(
        normalized,
        "idx_moderation_actions_tenant_account_created_at",
        "tenant_id",
        "account_id",
        "created_at DESC");
    assertIndexColumns(
        normalized, "idx_log_events_tenant_timestamp", "tenant_id", "timestamp DESC");
  }

  private static String tableBlock(String normalized, String tableName) {
    String marker = "CREATE TABLE " + tableName + " (";
    int start = normalized.indexOf(marker);
    assertThat(start).isGreaterThanOrEqualTo(0);
    int end = normalized.indexOf("CREATE TABLE ", start + marker.length());
    return normalized.substring(start, end < 0 ? normalized.length() : end);
  }

  private static void assertIndexColumns(
      String normalized, String indexName, String... expectedColumns) {
    String statement = indexStatement(normalized, indexName);
    int open = statement.indexOf('(');
    int close = statement.lastIndexOf(')');
    assertThat(open).isGreaterThanOrEqualTo(0);
    assertThat(close).isGreaterThan(open);
    assertThat(statement.substring(open + 1, close).trim())
        .isEqualTo(String.join(", ", expectedColumns));
  }

  private static String indexStatement(String normalized, String indexName) {
    int index = normalized.indexOf(indexName + " ON ");
    assertThat(index).isGreaterThanOrEqualTo(0);
    int start = normalized.lastIndexOf("CREATE ", index);
    int end = normalized.indexOf(';', index);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(index);
    return normalized.substring(start, end);
  }
}
