package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Physical PostgreSQL proof that the V73-to-source-foundation uplift does not enroll retained
 * Accounts.
 */
class AccountSourceFoundationV73UpgradePostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void retainedPreV40AccountAtV73GetsSerializationLocksButNoInventedAuthoritySource() {
    String schema = "account_source_v73_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("39")
        .load()
        .migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String suffix = UUID.randomUUID().toString();
    Long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash, role) "
                    + "VALUES (?, ?, ?, 'player') RETURNING id",
                Long.class,
                "retained-source-" + suffix.replace("-", ""),
                suffix + "@example.test",
                "retained-password-" + suffix));
    UUID accountUuid =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT account_uuid FROM accounts WHERE id = ?", UUID.class, accountId));

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("73")
        .load()
        .migrate();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_authority_source_records "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                Long.class,
                accountUuid))
        .isZero();

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success AND version = '97'",
                String.class))
        .isEqualTo("97");
    for (String relation :
        new String[] {
          "account_game_logic_intake_source_read_reservations",
          "account_game_logic_intake_source_read_sources",
          "account_game_logic_intake_source_read_aborts"
        }) {
      assertThat(jdbc.queryForObject("SELECT to_regclass(?)::TEXT", String.class, relation))
          .as("forward relation %s", relation)
          .isNotNull();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regprocedure(?)::TEXT",
                String.class,
                "account_game_logic_intake_source_read_is_pending(uuid)"))
        .isNotNull();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_authority_source_records "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                Long.class,
                accountUuid))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_global_role_sources WHERE account_uuid = ?",
                Long.class,
                accountUuid))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_platform_restriction_births WHERE account_uuid = ?",
                Long.class,
                accountUuid))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_draft_authorization_source_locks "
                    + "WHERE source_key IN (?, ?)",
                Long.class,
                "ACCOUNT:" + accountUuid,
                "GLOBAL_ROLES:" + accountUuid))
        .isEqualTo(2L);
  }
}
