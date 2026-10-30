package net.firedevops.firemud.loggingadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
    flyway("1002").migrate();
    jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .endsWith("1000", "1001", "1002");
    assertThat(
            jdbc.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version = '1002' AND success",
                String.class))
        .endsWith("V1002__saga_step_table.sql");
  }

  @Test
  void emptySchemaMigratesAndKeepsExactUuidWithoutChangingPolicyFields() {
    List<String> parentHistory = migrationHistory();
    flyway("1003").migrate();
    List<String> migratedHistory = migrationHistory();
    assertThat(migratedHistory).hasSize(parentHistory.size() + 1);
    assertThat(migratedHistory.subList(0, parentHistory.size())).isEqualTo(parentHistory);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1003' "
                    + "AND script = 'V1003__moderation_account_uuid.sql' AND success",
                Integer.class))
        .isOne();
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
    assertThatThrownBy(() -> flyway("1003").migrate())
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
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1003'", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'moderation_actions' AND column_name = 'account_id'",
                String.class))
        .isEqualTo("bigint");
  }

  private List<String> migrationHistory() {
    return jdbc.queryForList(
        "SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank",
        String.class);
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
