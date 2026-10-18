package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for V1011's actual-parent legacy-key seed and retained-key writer guards. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class EntityEmptySelectedSourceIntakeMigrationPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;

  @BeforeEach
  void seedLegacyRowsBeforeV1011() {
    schema = "entity_empty_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    var legacyFlywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION, "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .target("1010");
    legacyFlywayConfiguration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    legacyFlywayConfiguration.load().migrate();

    DSLContext legacyDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    // Synthetic V1010 parent rows exercise migration occupancy only. They are not selected-source
    // evidence and do not authenticate Account, Game Design, or World producers.
    legacyDsl.execute(
        "INSERT INTO characters (account_id, name, tenant_id) VALUES (1, 'legacy', 31001)");
    legacyDsl.execute(
        "INSERT INTO items (name, tenant_id, version_id) VALUES ('legacy', 41001, 51001)");
    legacyDsl.execute(
        "INSERT INTO item_transfer_audits (tenant_id, item_id, quantity, verb, correlation_key, "
            + "source_holder_kind, destination_holder_kind) "
            + "VALUES (61001, 99001, 1, 'MOVE', 'legacy-audit', 'UNKNOWN', 'UNKNOWN')");

    legacyDsl.execute(
        "INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?)",
        UUID.randomUUID());
    legacyDsl.execute(
        "INSERT INTO saga_instance (saga_name, state) VALUES ('retained-entity-source', 'PENDING')");
    legacyDsl.execute(
        "INSERT INTO saga_step (instance_id, name, status) SELECT id, 'retained-step', 'PENDING' FROM saga_instance WHERE saga_name = 'retained-entity-source'");
    var parentRows = retainedParentRows(legacyDsl);
    var parentHistory = history(legacyDsl);
    assertThat(
            legacyDsl
                .fetch(
                    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")
                .getValues(0, String.class))
        .containsExactly(
            "1", "1000", "1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
            "1010");

    var flywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION, "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema));
    flywayConfiguration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    flywayConfiguration.load().migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(retainedParentRows(dsl)).isEqualTo(parentRows);
    assertThat(history(dsl).subList(0, parentHistory.size()))
        .containsExactlyElementsOf(parentHistory);
    assertThat(
            dsl.fetchSingle(
                    "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                .get(0, String.class))
        .isEqualTo("1011");
  }

  @AfterEach
  void dropIsolatedSchema() {
    if (schema != null) {
      String checkedSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + checkedSchema + "\" CASCADE");
      schema = null;
    }
  }

  @Test
  void migrationSeedsActualLegacySourceAndAuditTenantVersionKeys() {
    List<ReservedNumericKey> legacyKeys =
        dsl.fetch(
                "SELECT key_kind, numeric_key, claim_kind "
                    + "FROM entity_empty_source_numeric_key_reservation "
                    + "WHERE claim_kind = 'LEGACY_NUMERIC' ORDER BY key_kind, numeric_key")
            .map(
                row ->
                    new ReservedNumericKey(
                        row.get(0, String.class),
                        row.get(1, Long.class),
                        row.get(2, String.class)));
    assertThat(legacyKeys)
        .containsExactly(
            new ReservedNumericKey("TENANT", 31001L, "LEGACY_NUMERIC"),
            new ReservedNumericKey("TENANT", 41001L, "LEGACY_NUMERIC"),
            new ReservedNumericKey("TENANT", 61001L, "LEGACY_NUMERIC"),
            new ReservedNumericKey("VERSION", 51001L, "LEGACY_NUMERIC"));
    assertLegacyKey("TENANT", 31001L);
    assertLegacyKey("TENANT", 41001L);
    assertLegacyKey("TENANT", 61001L);
    assertLegacyKey("VERSION", 51001L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT COUNT(*) FROM entity_empty_source_numeric_key_reservation "
                        + "WHERE key_kind = 'VERSION' AND numeric_key = 51001 "
                        + "AND claim_kind = 'LEGACY_NUMERIC'")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void retainedNumericKeysRejectLaterSourceRuntimeAndAuditWriters() {
    dsl.execute(
        "INSERT INTO entity_empty_source_numeric_key_reservation "
            + "(key_kind, numeric_key, claim_kind) VALUES "
            + "('TENANT', 99001, 'CANONICAL_EMPTY_SOURCE'), "
            + "('VERSION', 99002, 'CANONICAL_EMPTY_SOURCE')");

    assertRejected(
        "INSERT INTO items (name, tenant_id, version_id) VALUES ('blocked', 99001, 99002)");
    assertRejected("UPDATE items SET tenant_id = 99001 WHERE tenant_id = 41001");
    assertRejected(
        "INSERT INTO characters (account_id, name, tenant_id) VALUES (2, 'blocked', 99001)");
    assertRejected("UPDATE characters SET tenant_id = 99001 WHERE tenant_id = 31001");
    assertRejected(
        "INSERT INTO item_transfer_audits (tenant_id, item_id, quantity, verb, correlation_key, "
            + "source_holder_kind, destination_holder_kind) "
            + "VALUES (99001, 99003, 1, 'MOVE', 'blocked-audit', 'UNKNOWN', 'UNKNOWN')");
  }

  @Test
  void unassociatedLegacyKeysRemainWritable() {
    dsl.execute(
        "INSERT INTO items (name, tenant_id, version_id) "
            + "VALUES ('unassociated legacy item', 71001, 71002)");
    dsl.execute(
        "INSERT INTO characters (account_id, name, tenant_id) "
            + "VALUES (3, 'unassociated legacy character', 72001)");

    assertThat(
            dsl.fetchSingle("SELECT COUNT(*) FROM items WHERE tenant_id = 71001")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchSingle("SELECT COUNT(*) FROM characters WHERE tenant_id = 72001")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void staleRepeatableReadWritersCannotMissACommittedCanonicalReservation() throws SQLException {
    try (Connection staleRowWriter = repeatableReadSnapshot()) {
      commitCanonicalReservation(99001L);
      try {
        assertThatThrownBy(
                () ->
                    execute(
                        staleRowWriter,
                        "INSERT INTO characters (account_id, name, tenant_id) "
                            + "VALUES (99001, 'stale-snapshot-character', 99001)"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining(
                "Entity authored-source row writes require READ COMMITTED isolation");
      } finally {
        staleRowWriter.rollback();
      }
    }

    try (Connection staleTruncator = repeatableReadSnapshot()) {
      commitCanonicalReservation(99002L);
      try {
        assertThatThrownBy(() -> execute(staleTruncator, "TRUNCATE TABLE items CASCADE"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining(
                "Entity authored-source TRUNCATE requires READ COMMITTED isolation");
      } finally {
        staleTruncator.rollback();
      }
    }
  }

  /** Storage retention only; synthetic parent rows establish no authenticated owner authority. */
  private static Map<String, List<String>> retainedParentRows(DSLContext source) {
    Map<String, List<String>> rows = new java.util.LinkedHashMap<>();
    for (String table :
        List.of(
            "characters",
            "items",
            "item_transfer_audits",
            "entity_tenant_identities",
            "entity_playable_state_namespace_scopes",
            "entity_preseeded_actor_assignment_operations",
            "entity_canonical_gameplay_roster_snapshots",
            "entity_canonical_gameplay_roster_snapshot_actors",
            "saga_instance",
            "saga_step")) {
      rows.put(
          table,
          source
              .fetch(
                  "SELECT jsonb_build_object('row', to_jsonb(t), 'xmin', t.xmin::TEXT)::TEXT FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::TEXT")
              .getValues(0, String.class));
    }
    return rows;
  }

  private static List<String> history(DSLContext source) {
    return source
        .fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT FROM flyway_schema_history t ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private void assertLegacyKey(String kind, long key) {
    assertThat(
            dsl.fetchSingle(
                    "SELECT claim_kind FROM entity_empty_source_numeric_key_reservation "
                        + "WHERE key_kind = ? AND numeric_key = ?",
                    kind,
                    key)
                .get(0, String.class))
        .isEqualTo("LEGACY_NUMERIC");
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
          .executeQuery("SELECT COUNT(*) FROM entity_empty_source_numeric_key_reservation")
          .close();
    }
    return connection;
  }

  private void commitCanonicalReservation(long tenantKey) throws SQLException {
    try (Connection founder = dataSource(schema).getConnection()) {
      founder.setAutoCommit(false);
      try (var reservation =
          founder.prepareStatement(
              "INSERT INTO entity_empty_source_numeric_key_reservation "
                  + "(key_kind, numeric_key, claim_kind) "
                  + "VALUES ('TENANT', ?, 'CANONICAL_EMPTY_SOURCE')")) {
        reservation.setLong(1, tenantKey);
        reservation.executeUpdate();
      }
      founder.commit();
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private record ReservedNumericKey(String keyKind, Long numericKey, String claimKind) {}

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
