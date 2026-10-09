package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Disposable-image feasibility probe for the pg_walinspect module and a restricted wrapper reader.
 *
 * <p>This verifies only server-major, packaged module, installation, and role-permission behavior
 * in the selected images. It does not establish transaction durability, recover an original
 * acknowledgement, or prove failover behavior.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class AccountPostgresWalModuleFeasibilityIntegrationTest {
  private static final String POSTGRES_18_IMAGE =
      "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";

  @Container
  static final PostgreSQLContainer<?> POSTGRES_16 = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final PostgreSQLContainer<?> POSTGRES_18 = new PostgreSQLContainer<>(POSTGRES_18_IMAGE);

  @Test
  void postgres16AlpineHasInstallableWalInspectAndRestrictedReaderWrapper() throws SQLException {
    assertImageFeasibility(POSTGRES_16, 16);
  }

  @Test
  void pinnedPostgres18HasInstallableWalInspectAndRestrictedReaderWrapper() throws SQLException {
    assertImageFeasibility(POSTGRES_18, 18);
  }

  @SuppressFBWarnings(
      value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      justification =
          "Only a canonical UUID generated for this disposable fixture is interpolated as its ephemeral reader password.")
  private static void assertImageFeasibility(PostgreSQLContainer<?> postgres, int expectedMajor)
      throws SQLException {
    String lowerLsn;
    String upperLsn;
    String readerPassword = UUID.randomUUID().toString();
    try (Connection admin = connect(postgres, postgres.getUsername(), postgres.getPassword())) {
      assertThat(integer(admin, "SELECT current_setting('server_version_num')::integer"))
          .isBetween(expectedMajor * 10_000, expectedMajor * 10_000 + 9_999);
      assertThat(
              string(
                  admin,
                  "SELECT default_version FROM pg_available_extensions "
                      + "WHERE name = 'pg_walinspect'"))
          .isNotBlank();
      assertThat(
              integer(
                  admin,
                  "SELECT count(*) FROM pg_available_extension_versions "
                      + "WHERE name = 'pg_walinspect' AND version = '1.1'"))
          .isEqualTo(1);

      execute(
          admin,
          "CREATE ROLE wal_feasibility_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
      execute(
          admin,
          "CREATE ROLE wal_feasibility_reader LOGIN PASSWORD '"
              + readerPassword
              + "' "
              + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
      execute(admin, "CREATE SCHEMA wal_proof_extension");
      execute(admin, "REVOKE ALL ON SCHEMA wal_proof_extension FROM PUBLIC");
      execute(admin, "CREATE SCHEMA wal_feasibility");
      execute(admin, "GRANT USAGE, CREATE ON SCHEMA wal_feasibility TO wal_feasibility_owner");
      execute(admin, "GRANT USAGE ON SCHEMA wal_proof_extension TO wal_feasibility_owner");
      execute(
          admin, "CREATE EXTENSION pg_walinspect WITH SCHEMA wal_proof_extension VERSION '1.1'");
      execute(
          admin,
          "REVOKE ALL ON FUNCTION "
              + "wal_proof_extension.pg_get_wal_records_info(pg_lsn, pg_lsn) FROM PUBLIC");
      execute(
          admin,
          "GRANT EXECUTE ON FUNCTION "
              + "wal_proof_extension.pg_get_wal_records_info(pg_lsn, pg_lsn) "
              + "TO wal_feasibility_owner");
      assertThat(
              string(
                  admin,
                  "SELECT extversion FROM pg_extension e JOIN pg_namespace n "
                      + "ON n.oid = e.extnamespace WHERE e.extname = 'pg_walinspect' "
                      + "AND n.nspname = 'wal_proof_extension'"))
          .isEqualTo("1.1");

      execute(admin, "CREATE TABLE wal_feasibility.probe (value text NOT NULL)");
      lowerLsn = string(admin, "SELECT pg_current_wal_insert_lsn()::text");
      execute(admin, "INSERT INTO wal_feasibility.probe VALUES ('module-feasibility-probe')");
      upperLsn = string(admin, "SELECT pg_current_wal_insert_lsn()::text");
      assertThat(bool(admin, "SELECT ?::pg_lsn < ?::pg_lsn", lowerLsn, upperLsn)).isTrue();

      execute(
          admin,
          "CREATE FUNCTION wal_feasibility.read_records(start_lsn pg_lsn, end_lsn pg_lsn) "
              + "RETURNS bigint LANGUAGE SQL SECURITY DEFINER SET search_path = pg_catalog "
              + "AS $$ SELECT count(*) FROM "
              + "wal_proof_extension.pg_get_wal_records_info(start_lsn, end_lsn) $$");
      execute(
          admin,
          "ALTER FUNCTION wal_feasibility.read_records(pg_lsn, pg_lsn) "
              + "OWNER TO wal_feasibility_owner");
      execute(
          admin, "REVOKE ALL ON FUNCTION wal_feasibility.read_records(pg_lsn, pg_lsn) FROM PUBLIC");
      execute(
          admin,
          "GRANT EXECUTE ON FUNCTION wal_feasibility.read_records(pg_lsn, pg_lsn) "
              + "TO wal_feasibility_reader");
      execute(admin, "GRANT USAGE ON SCHEMA wal_feasibility TO wal_feasibility_reader");
      assertThat(
              bool(
                  admin,
                  "SELECT has_function_privilege('wal_feasibility_reader', "
                      + "'wal_proof_extension.pg_get_wal_records_info(pg_lsn,pg_lsn)', 'EXECUTE')"))
          .isFalse();
      assertThat(
              bool(
                  admin,
                  "SELECT has_schema_privilege('wal_feasibility_reader', "
                      + "'wal_proof_extension', 'USAGE')"))
          .isFalse();
      execute(admin, "GRANT USAGE ON SCHEMA wal_proof_extension TO wal_feasibility_reader");
    }

    try (Connection reader = connect(postgres, "wal_feasibility_reader", readerPassword)) {
      assertThat(bool(reader, "SELECT pg_has_role(current_user, 'pg_read_server_files', 'MEMBER')"))
          .isFalse();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  integer(
                      reader,
                      "SELECT count(*) FROM "
                          + "wal_proof_extension.pg_get_wal_records_info(?::pg_lsn, ?::pg_lsn)",
                      lowerLsn,
                      upperLsn))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for function");
      assertThat(
              integer(
                  reader,
                  "SELECT wal_feasibility.read_records(?::pg_lsn, ?::pg_lsn)::integer",
                  lowerLsn,
                  upperLsn))
          .isGreaterThan(0);
    }
  }

  private static Connection connect(PostgreSQLContainer<?> postgres, String user, String password)
      throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String string(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("Expected one PostgreSQL result row");
        return result.getString(1);
      }
    }
  }

  private static int integer(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("Expected one PostgreSQL result row");
        return result.getInt(1);
      }
    }
  }

  private static boolean bool(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("Expected one PostgreSQL result row");
        return result.getBoolean(1);
      }
    }
  }
}
