package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountTenantCreationProvenancePostgresIntegrationTest {
  private static final String HISTORY_TABLE = "flyway_schema_history_tenant_creation_provenance";
  private static final String DIGEST = "sha256:" + "1".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void forwardMigrationPreservesRetainedStateAndAllowsOnlyControlOnlyCreatorShape()
      throws Exception {
    Database database = newDatabase();
    flyway(database, "25").migrate();

    AccountRow retained = insertAccount(database.dataSource(), "retained");
    long retainedMembershipId = insertLegacyMembership(database.dataSource(), retained.id(), 41L);
    AccountRow absent = insertAccount(database.dataSource(), "without-membership");
    Map<String, Object> oldMembershipBefore =
        row(
            database.dataSource(),
            "SELECT id, account_id, tenant_id, gameplay_admission_allowed "
                + "FROM account_tenant_membership WHERE id = "
                + retainedMembershipId);

    flyway(database, null).migrate();

    Map<String, Object> retainedAfter =
        row(
            database.dataSource(),
            "SELECT id, account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                + "membership_version, membership_authority_generation, authority_provenance, "
                + "tenant_provenance_kind, tenant_uuid FROM account_tenant_membership WHERE id = "
                + retainedMembershipId);
    assertThat(retainedAfter)
        .containsEntry("id", retainedMembershipId)
        .containsEntry("account_id", retained.id())
        .containsEntry("tenant_id", 41L)
        .containsEntry("gameplay_admission_allowed", false)
        .containsEntry("lifecycle_state", "LEGACY_UNVERIFIED")
        .containsEntry("membership_version", 1L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("authority_provenance", "LEGACY_UNVERIFIED")
        .containsEntry("tenant_provenance_kind", "UNBRIDGED_RETAINED")
        .containsEntry("tenant_uuid", null);
    assertThat(oldMembershipBefore)
        .containsEntry("id", retainedMembershipId)
        .containsEntry("account_id", retained.id())
        .containsEntry("tenant_id", 41L);
    assertThat(
            scalarLong(
                database.dataSource(),
                "SELECT COUNT(*) FROM account_legacy_membership_sources "
                    + "WHERE membership_id = "
                    + retainedMembershipId))
        .isEqualTo(1L);
    assertThat(
            scalarLong(
                database.dataSource(),
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = " + absent.id()))
        .isZero();

    UUID tenantUuid = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    insertSyntheticFreshTenantIdentity(database.dataSource(), tenantUuid, operationId);
    long creatorMembershipId =
        insertSyntheticCreatorMembership(
            database.dataSource(), absent.id(), tenantUuid, operationId);
    assertThat(
            row(
                database.dataSource(),
                "SELECT account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
                    + "tenant_source_operation_id, tenant_provenance_digest, lifecycle_state, "
                    + "membership_version, membership_authority_generation, authority_provenance, "
                    + "gameplay_admission_allowed FROM account_tenant_membership WHERE id = "
                    + creatorMembershipId))
        .containsEntry("account_id", absent.id())
        .containsEntry("tenant_id", null)
        .containsEntry("tenant_uuid", tenantUuid)
        .containsEntry("tenant_provenance_kind", "FRESH_GAME_DESIGN")
        .containsEntry("tenant_source_operation_id", operationId)
        .containsEntry("tenant_provenance_digest", DIGEST)
        .containsEntry("lifecycle_state", "ACTIVE")
        .containsEntry("membership_version", 1L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("authority_provenance", "TENANT_CREATION")
        .containsEntry("gameplay_admission_allowed", false);

    assertConstraintRejects(
        database.dataSource(),
        "UPDATE account_tenant_membership SET gameplay_admission_allowed = TRUE " + "WHERE id = ?",
        "account_membership_tenant_creation_not_admitting_check",
        creatorMembershipId);
    assertConstraintRejects(
        database.dataSource(),
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, 42, TRUE, 'LEGACY_UNVERIFIED', 1, 1, "
            + "'LEGACY_UNVERIFIED')",
        "account_membership_unverified_not_admitting_check",
        absent.id());
    assertConstraintRejects(
        database.dataSource(),
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, 43, FALSE, 'ACTIVE', 1, 1, 'NOT_A_PROVENANCE')",
        "account_membership_provenance_check",
        absent.id());
    assertConstraintRejects(
        database.dataSource(),
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_uuid, tenant_provenance_kind, tenant_source_operation_id, "
            + "tenant_provenance_digest, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, 'FRESH_GAME_DESIGN', ?, ?, FALSE, 'ACTIVE', 1, 1, 'TENANT_CREATION')",
        "Account membership tenant identity has no exact immutable source",
        absent.id(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        DIGEST);

    assertThat(
            scalarLong(
                database.dataSource(),
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = " + absent.id()))
        .isEqualTo(1L);
    assertThat(
            row(
                database.dataSource(),
                "SELECT authority_provenance, gameplay_admission_allowed "
                    + "FROM account_tenant_membership WHERE id = "
                    + creatorMembershipId))
        .containsEntry("authority_provenance", "TENANT_CREATION")
        .containsEntry("gameplay_admission_allowed", false);
  }

  private static Flyway flyway(Database database, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(database.dataSource())
            .schemas(database.schema())
            .defaultSchema(database.schema())
            .table(HISTORY_TABLE)
            .placeholders(Map.of("serviceSchema", database.schema()))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  private static Database newDatabase() throws SQLException {
    String schema = "account_tenant_creation_" + UUID.randomUUID().toString().replace("-", "");
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl() + separator + "currentSchema=" + schema,
            postgres.getUsername(),
            postgres.getPassword());
    dataSource.setDriverClassName("org.postgresql.Driver");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    return new Database(schema, dataSource);
  }

  private static AccountRow insertAccount(DataSource dataSource, String label) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                    + "RETURNING id")) {
      String suffix = UUID.randomUUID().toString().replace("-", "");
      statement.setString(1, label + "-" + suffix);
      statement.setString(2, label + "-" + suffix + "@example.com");
      statement.setString(3, "test-hash");
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return new AccountRow(result.getLong("id"));
      }
    }
  }

  private static long insertLegacyMembership(DataSource dataSource, long accountId, long tenantId)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO account_tenant_membership "
                    + "(account_id, tenant_id, gameplay_admission_allowed) VALUES (?, ?, TRUE) "
                    + "RETURNING id")) {
      statement.setLong(1, accountId);
      statement.setLong(2, tenantId);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static void insertSyntheticFreshTenantIdentity(
      DataSource dataSource, UUID tenantUuid, UUID operationId) throws SQLException {
    UUID creationRequestId = UUID.randomUUID();
    String namespace = "firemud-test";
    String tenantKey = "synthetic-" + UUID.randomUUID().toString().substring(0, 8);
    long gameRowId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    if (gameRowId == 0) {
      gameRowId = 1;
    }
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "INSERT INTO account_fresh_tenant_identity_associations "
              + "(schema_version, target_namespace, creation_request_id, operation_id, "
              + "request_digest, canonical_tenant_id, source_game_row_id, "
              + "source_game_tenant_key, provenance_kind, evidence_digest) "
              + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?)",
          namespace,
          creationRequestId,
          operationId,
          DIGEST,
          tenantUuid,
          gameRowId,
          tenantKey,
          DIGEST);
    }
  }

  private static long insertSyntheticCreatorMembership(
      DataSource dataSource, long accountId, UUID tenantUuid, UUID operationId)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO account_tenant_membership "
                    + "(account_id, tenant_uuid, tenant_provenance_kind, "
                    + "tenant_source_operation_id, tenant_provenance_digest, "
                    + "gameplay_admission_allowed, lifecycle_state, membership_version, "
                    + "membership_authority_generation, authority_provenance) "
                    + "VALUES (?, ?, 'FRESH_GAME_DESIGN', ?, ?, FALSE, 'ACTIVE', 1, 1, "
                    + "'TENANT_CREATION') RETURNING id")) {
      statement.setLong(1, accountId);
      statement.setObject(2, tenantUuid);
      statement.setObject(3, operationId);
      statement.setString(4, DIGEST);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static Map<String, Object> row(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      var metadata = result.getMetaData();
      var row = new java.util.LinkedHashMap<String, Object>();
      for (int column = 1; column <= metadata.getColumnCount(); column++) {
        row.put(metadata.getColumnLabel(column), result.getObject(column));
      }
      return row;
    }
  }

  private static long scalarLong(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static void assertConstraintRejects(
      DataSource dataSource, String sql, String expected, Object... values) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      assertThatThrownBy(
              () -> {
                execute(connection, sql, values);
              })
          .isInstanceOf(SQLException.class)
          .hasMessageContaining(expected);
      connection.rollback();
    }
  }

  private static void execute(Connection connection, String sql, Object... values)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      statement.executeUpdate();
    }
  }

  private record Database(String schema, DataSource dataSource) {}

  private record AccountRow(long id) {}
}
