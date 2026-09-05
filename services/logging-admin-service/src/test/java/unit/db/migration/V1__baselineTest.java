package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V1__baselineTest {

  @Test
  void containsTheCompleteLoggingAdminBaselineContractWithoutUpgradeDdl() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V1__baseline.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    String moderationActions = tableBlock(normalized, "moderation_actions");
    String playerReports = tableBlock(normalized, "player_reports");
    String logEvents = tableBlock(normalized, "log_events");

    assertThat(moderationActions)
        .contains(
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "account_id BIGINT NOT NULL",
            "action VARCHAR(20) NOT NULL",
            "reason VARCHAR(255)",
            "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP",
            "expires_at TIMESTAMP NULL");
    assertThat(playerReports)
        .contains(
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "reporter_account_id BIGINT NOT NULL",
            "target_account_id BIGINT",
            "type VARCHAR(20) NOT NULL",
            "description VARCHAR(255) NOT NULL",
            "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
    assertThat(logEvents)
        .contains(
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "type VARCHAR(50) NOT NULL",
            "message VARCHAR(255) NOT NULL",
            "timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP",
            "account_id BIGINT");

    assertThat(normalized)
        .doesNotContain(
            "ALTER TABLE",
            "ADD COLUMN",
            "ADD CONSTRAINT",
            "DROP COLUMN",
            "DROP CONSTRAINT",
            "DROP INDEX",
            "UPDATE ",
            "DELETE FROM",
            "INSERT INTO",
            "NOT VALID",
            "VALIDATE CONSTRAINT");
    assertThat(normalized.indexOf("CREATE TABLE moderation_actions"))
        .isLessThan(normalized.indexOf("CREATE TABLE player_reports"));
    assertThat(normalized.indexOf("CREATE TABLE player_reports"))
        .isLessThan(normalized.indexOf("CREATE TABLE log_events"));
  }

  private static String tableBlock(String normalized, String tableName) {
    String marker = "CREATE TABLE " + tableName + " (";
    int start = normalized.indexOf(marker);
    assertThat(start).isGreaterThanOrEqualTo(0);
    int end = normalized.indexOf("CREATE TABLE ", start + marker.length());
    return normalized.substring(start, end < 0 ? normalized.length() : end);
  }
}
