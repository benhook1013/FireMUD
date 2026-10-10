package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
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

/** PostgreSQL proof for V2's real legacy-key seed and retained-key writer guards. */
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
  void seedLegacyRowsBeforeV2() {
    schema = "entity_empty_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("1")
        .load()
        .migrate();

    DSLContext legacyDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    // Synthetic pre-V2 rows exercise migration occupancy only. They are not selected-source
    // evidence and do not authenticate Account, Game Design, or World producers.
    legacyDsl.execute(
        "INSERT INTO characters (account_id, name, tenant_id) VALUES (1, 'legacy', 31001)");
    legacyDsl.execute(
        "INSERT INTO items (name, tenant_id, version_id) VALUES ('legacy', 41001, 51001)");
    legacyDsl.execute(
        "INSERT INTO item_transfer_audits (tenant_id, item_id, quantity, verb, correlation_key, "
            + "source_holder_kind, destination_holder_kind) "
            + "VALUES (61001, 99001, 1, 'MOVE', 'legacy-audit', 'UNKNOWN', 'UNKNOWN')");

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
            dsl.fetchValue(
                "SELECT COUNT(*) FROM entity_empty_source_numeric_key_reservation "
                    + "WHERE key_kind = 'VERSION' AND numeric_key = 51001 "
                    + "AND claim_kind = 'LEGACY_NUMERIC'",
                Long.class))
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

    assertThat(dsl.fetchValue("SELECT COUNT(*) FROM items WHERE tenant_id = 71001", Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchValue("SELECT COUNT(*) FROM characters WHERE tenant_id = 72001", Long.class))
        .isEqualTo(1L);
  }

  private void assertLegacyKey(String kind, long key) {
    assertThat(
            dsl.fetchValue(
                "SELECT claim_kind FROM entity_empty_source_numeric_key_reservation "
                    + "WHERE key_kind = ? AND numeric_key = ?",
                String.class,
                kind,
                key))
        .isEqualTo("LEGACY_NUMERIC");
  }

  private void assertRejected(String sql) {
    assertThatThrownBy(() -> dsl.execute(sql)).isInstanceOf(DataAccessException.class);
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
