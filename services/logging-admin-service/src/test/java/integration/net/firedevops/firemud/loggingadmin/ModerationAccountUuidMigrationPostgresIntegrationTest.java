package net.firedevops.firemud.loggingadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class ModerationAccountUuidMigrationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;

  @BeforeEach
  void legacySchema() {
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
    flyway("4").migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void emptySchemaMigratesAndKeepsExactUuidWithoutChangingPolicyFields() {
    flyway("5").migrate();
    String account = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";
    jdbc.update(
        "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (1, ?::uuid, 'chat_mute', 'abuse')",
        account);
    assertThat(jdbc.queryForObject("SELECT account_id::text FROM moderation_actions", String.class))
        .isEqualTo(account);
    assertThat(
            jdbc.queryForObject(
                "SELECT action || ':' || reason FROM moderation_actions", String.class))
        .isEqualTo("chat_mute:abuse");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO moderation_actions (tenant_id, account_id, action) VALUES (1, '00000000-0000-0000-0000-000000000000', 'ban')"))
        .hasMessageContaining("moderation_actions_account_id_non_nil");
  }

  @Test
  void populatedLegacyRowsBlockMigrationWithoutChangingRowsOrMigrationHistory() {
    jdbc.update(
        "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (1, 9007199254740993, 'ban', 'retain exactly')");
    String before =
        jdbc.queryForObject(
            "SELECT md5(row_to_json(m)::text) FROM moderation_actions m", String.class);
    String history =
        jdbc.queryForObject(
            "SELECT md5(string_agg(row_to_json(h)::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history h",
            String.class);
    assertThatThrownBy(() -> flyway("5").migrate())
        .hasStackTraceContaining("Cannot migrate populated legacy moderation Account references");
    assertThat(
            jdbc.queryForObject(
                "SELECT md5(row_to_json(m)::text) FROM moderation_actions m", String.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT md5(string_agg(row_to_json(h)::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history h",
                String.class))
        .isEqualTo(history);
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'moderation_actions' AND column_name = 'account_id'",
                String.class))
        .isEqualTo("bigint");
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", "public"))
        .target(MigrationVersion.fromVersion(target))
        .load();
  }
}
