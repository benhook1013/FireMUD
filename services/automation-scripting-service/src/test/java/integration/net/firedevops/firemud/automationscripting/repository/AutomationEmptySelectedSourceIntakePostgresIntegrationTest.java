package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationEmptySelectedSourceIntakePostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;

  @BeforeAll
  void migrateWithRetainedLegacyAuthoredRows() {
    schema = "automation_empty_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target("3.2")
        .load()
        .migrate();

    DSLContext upgradeDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    upgradeDsl.execute(
        "INSERT INTO scripts (tenant_id, name, version, definition, base_version_id) "
            + "VALUES (101, 'retained-script', 'legacy-patch', '{}', 201)");
    upgradeDsl.execute(
        "INSERT INTO script_event_bindings (tenant_id, script_patch_version, event_type, "
            + "event_schema_version, script_id, target_scope_type, target_scope_id, base_version_id) "
            + "VALUES (102, 'legacy-patch', 'legacy-event', 'v1', 'retained-script', "
            + "'TENANT', 'legacy', 202)");
    upgradeDsl.execute(
        "INSERT INTO script_patch_base_bindings (tenant_id, script_patch_version, base_version_id) "
            + "VALUES ('103', 'legacy-patch', 203)");

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  @AfterAll
  void dropIsolatedSchema() {
    if (schema != null) {
      String checkedSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + checkedSchema + "\" CASCADE");
      schema = null;
    }
  }

  @Test
  void migrationSeedsLegacyKeysAndGuardsAllThreeAuthoredSourceFamilies() {
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM automation_empty_source_numeric_key_reservation "
                    + "WHERE claim_kind = 'LEGACY_NUMERIC'",
                Long.class))
        .isEqualTo(6L);
    assertThat(
            dsl.fetchValue(
                "SELECT claim_kind FROM automation_empty_source_numeric_key_reservation "
                    + "WHERE key_kind = 'TENANT' AND numeric_key = 101",
                String.class))
        .isEqualTo("LEGACY_NUMERIC");

    reserveCanonicalScope(9001L, 9002L);

    // NEW-side guards deny writes that target either locally reserved numeric dimension.
    assertRejected(
        "INSERT INTO scripts (tenant_id, name, version, definition) "
            + "VALUES (9001, 'blocked-script', 'v1', '{}')");
    assertRejected(
        "INSERT INTO script_event_bindings (tenant_id, script_patch_version, event_type, "
            + "event_schema_version, script_id, target_scope_type, target_scope_id, base_version_id) "
            + "VALUES (400, 'blocked-patch', 'blocked-event', 'v1', 'blocked-script', "
            + "'TENANT', 'one', 9002)");
    assertRejected(
        "INSERT INTO script_patch_base_bindings (tenant_id, script_patch_version, base_version_id) "
            + "VALUES ('9001', 'blocked-patch', 400)");

    // UPDATE checks both sides: legacy rows cannot be moved into a canonical scope.
    upgradeLegacyRowsForUpdate();
    assertRejected("UPDATE scripts SET tenant_id = 9001 WHERE name = 'update-script'");
    assertRejected(
        "UPDATE script_event_bindings SET base_version_id = 9002 "
            + "WHERE script_patch_version = 'update-patch'");
    assertRejected(
        "UPDATE script_patch_base_bindings SET tenant_id = '9001' "
            + "WHERE script_patch_version = 'update-patch'");

    // Fixture-only trigger bypass seeds rows already inside the protected scope so OLD-side
    // UPDATE/DELETE rejection is exercised independently of the NEW-side checks above.
    seedCanonicalRowsForOldSideChecks();
    assertRejected("DELETE FROM scripts WHERE name = 'old-side-script'");
    assertRejected("UPDATE scripts SET tenant_id = 9101 WHERE name = 'old-side-script'");
    assertRejected(
        "DELETE FROM script_event_bindings WHERE script_patch_version = 'old-side-patch'");
    assertRejected(
        "UPDATE script_event_bindings SET base_version_id = 9102 "
            + "WHERE script_patch_version = 'old-side-patch'");
    assertRejected(
        "DELETE FROM script_patch_base_bindings WHERE script_patch_version = 'old-side-patch'");
    assertRejected(
        "UPDATE script_patch_base_bindings SET tenant_id = '9103' "
            + "WHERE script_patch_version = 'old-side-patch'");

    assertRejected("TRUNCATE TABLE scripts");
    assertRejected("TRUNCATE TABLE script_event_bindings");
    assertRejected("TRUNCATE TABLE script_patch_base_bindings");
  }

  @Test
  void staleRepeatableReadWritersCannotMissACommittedCanonicalReservation() throws SQLException {
    try (Connection staleRowWriter = repeatableReadSnapshot()) {
      commitCanonicalEmptySourceFixture(9301L, 9302L);
      try {
        assertThatThrownBy(
                () ->
                    execute(
                        staleRowWriter,
                        "INSERT INTO scripts (tenant_id, name, version, definition) "
                            + "VALUES (9301, 'stale-snapshot-script', 'v1', '{}')"))
            .isInstanceOf(SQLException.class);
      } finally {
        staleRowWriter.rollback();
      }
    }

    try (Connection staleTruncator = repeatableReadSnapshot()) {
      commitCanonicalEmptySourceFixture(9401L, 9402L);
      try {
        assertThatThrownBy(() -> execute(staleTruncator, "TRUNCATE TABLE scripts"))
            .isInstanceOf(SQLException.class);
      } finally {
        staleTruncator.rollback();
      }
    }
  }

  private void reserveCanonicalScope(long tenantKey, long versionKey) {
    dsl.execute(
        "INSERT INTO automation_empty_source_numeric_key_reservation "
            + "(key_kind, numeric_key, claim_kind) VALUES "
            + "('TENANT', ?, 'CANONICAL_EMPTY_SOURCE'), "
            + "('VERSION', ?, 'CANONICAL_EMPTY_SOURCE')",
        tenantKey,
        versionKey);
  }

  private void upgradeLegacyRowsForUpdate() {
    dsl.execute(
        "INSERT INTO scripts (tenant_id, name, version, definition) "
            + "VALUES (410, 'update-script', 'v1', '{}')");
    dsl.execute(
        "INSERT INTO script_event_bindings (tenant_id, script_patch_version, event_type, "
            + "event_schema_version, script_id, target_scope_type, target_scope_id, base_version_id) "
            + "VALUES (411, 'update-patch', 'update-event', 'v1', 'update-script', "
            + "'TENANT', 'one', 412)");
    dsl.execute(
        "INSERT INTO script_patch_base_bindings (tenant_id, script_patch_version, base_version_id) "
            + "VALUES ('413', 'update-patch', 414)");
  }

  private void seedCanonicalRowsForOldSideChecks() {
    dsl.execute("ALTER TABLE scripts DISABLE TRIGGER trg_automation_scripts_source_reservation");
    dsl.execute(
        "INSERT INTO scripts (tenant_id, name, version, definition) "
            + "VALUES (9001, 'old-side-script', 'v1', '{}')");
    dsl.execute("ALTER TABLE scripts ENABLE TRIGGER trg_automation_scripts_source_reservation");

    dsl.execute(
        "ALTER TABLE script_event_bindings "
            + "DISABLE TRIGGER trg_automation_event_bindings_source_reservation");
    dsl.execute(
        "INSERT INTO script_event_bindings (tenant_id, script_patch_version, event_type, "
            + "event_schema_version, script_id, target_scope_type, target_scope_id, base_version_id) "
            + "VALUES (420, 'old-side-patch', 'old-side-event', 'v1', 'old-side-script', "
            + "'TENANT', 'one', 9002)");
    dsl.execute(
        "ALTER TABLE script_event_bindings "
            + "ENABLE TRIGGER trg_automation_event_bindings_source_reservation");

    dsl.execute(
        "ALTER TABLE script_patch_base_bindings "
            + "DISABLE TRIGGER trg_automation_patch_base_bindings_source_reservation");
    dsl.execute(
        "INSERT INTO script_patch_base_bindings (tenant_id, script_patch_version, base_version_id) "
            + "VALUES ('9001', 'old-side-patch', 420)");
    dsl.execute(
        "ALTER TABLE script_patch_base_bindings "
            + "ENABLE TRIGGER trg_automation_patch_base_bindings_source_reservation");
  }

  private void assertRejected(String sql) {
    assertThatThrownBy(() -> dsl.execute(sql)).isInstanceOf(DataAccessException.class);
  }

  private Connection repeatableReadSnapshot() throws SQLException {
    Connection connection = dataSource(schema).getConnection();
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      statement
          .executeQuery("SELECT COUNT(*) FROM automation_empty_source_numeric_key_reservation")
          .close();
    }
    return connection;
  }

  private void commitCanonicalEmptySourceFixture(long tenantKey, long versionKey)
      throws SQLException {
    // Synthetic SQL-only rows exercise the database fence; they do not authenticate Account or
    // World producers and are not proof of the physical owner path.
    try (Connection founder = dataSource(schema).getConnection()) {
      founder.setAutoCommit(false);
      try (PreparedStatement reservation =
          founder.prepareStatement(
              "INSERT INTO automation_empty_source_numeric_key_reservation "
                  + "(key_kind, numeric_key, claim_kind) VALUES "
                  + "('TENANT', ?, 'CANONICAL_EMPTY_SOURCE'), "
                  + "('VERSION', ?, 'CANONICAL_EMPTY_SOURCE')")) {
        reservation.setLong(1, tenantKey);
        reservation.setLong(2, versionKey);
        reservation.executeUpdate();
      }

      UUID operationId = UUID.randomUUID();
      UUID fenceId = UUID.randomUUID();
      UUID intakeRequestId = UUID.randomUUID();
      UUID canonicalTenantId = UUID.randomUUID();
      UUID canonicalVersionId = UUID.randomUUID();
      UUID selectedCommitId = UUID.randomUUID();
      UUID sourceRevisionId = UUID.randomUUID();
      String digest = "sha256:" + "c".repeat(64);
      try (var association =
          founder.prepareStatement(
              "INSERT INTO automation_empty_selected_source_association "
                  + "(target_namespace, operation_id, fence_id, intake_request_id, "
                  + "canonical_tenant_id, canonical_version_id, selected_commit_id, "
                  + "source_revision_id, source_revision_order, local_tenant_key, "
                  + "local_tenant_key_kind, local_tenant_key_claim_kind, local_version_key, "
                  + "local_version_key_kind, local_version_key_claim_kind, request_digest, "
                  + "authorization_binding_digest, receipt_digest, associated_at) "
                  + "VALUES ('example', ?, ?, ?, ?, ?, ?, ?, '1', ?, 'TENANT', "
                  + "'CANONICAL_EMPTY_SOURCE', ?, 'VERSION', 'CANONICAL_EMPTY_SOURCE', "
                  + "?, ?, ?, CURRENT_TIMESTAMP)")) {
        association.setObject(1, operationId);
        association.setObject(2, fenceId);
        association.setObject(3, intakeRequestId);
        association.setObject(4, canonicalTenantId);
        association.setObject(5, canonicalVersionId);
        association.setObject(6, selectedCommitId);
        association.setObject(7, sourceRevisionId);
        association.setLong(8, tenantKey);
        association.setLong(9, versionKey);
        association.setString(10, digest);
        association.setString(11, digest);
        association.setString(12, digest);
        association.executeUpdate();
      }
      try (var receipt =
          founder.prepareStatement(
              "INSERT INTO automation_empty_selected_source_receipt "
                  + "(target_namespace, intake_request_id, request_digest, receipt_digest, "
                  + "receipt_bytes, retained_at) VALUES ('example', ?, ?, ?, decode('01', 'hex'), "
                  + "CURRENT_TIMESTAMP)")) {
        receipt.setObject(1, intakeRequestId);
        receipt.setString(2, digest);
        receipt.setString(3, digest);
        receipt.executeUpdate();
      }
      founder.commit();
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private DriverManagerDataSource dataSource(String schemaName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        schemaName == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schemaName);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }
}
