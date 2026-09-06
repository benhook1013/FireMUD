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
            "CREATE TABLE log_events",
            "id BIGSERIAL PRIMARY KEY",
            "tenant_id BIGINT NOT NULL",
            "reporter_account_id BIGINT NOT NULL",
            "description VARCHAR(255) NOT NULL",
            "message VARCHAR(255) NOT NULL",
            "timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP")
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
  }
}
