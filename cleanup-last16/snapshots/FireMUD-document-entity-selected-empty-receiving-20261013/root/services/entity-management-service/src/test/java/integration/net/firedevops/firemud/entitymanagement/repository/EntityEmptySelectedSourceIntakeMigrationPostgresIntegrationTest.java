package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
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

/** PostgreSQL proof for forward V10 retention over canonical, unchanged V1-V9 history. */
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
  private Map<String, String> retainedChecksums;
  private Map<String, Integer> migrationChecksums;

  @BeforeEach
  void seedCanonicalHistoryBeforeV10() {
    schema = "entity_empty_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    var legacyFlywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .schemas(schema)
            .defaultSchema(schema)
            .target("1");
    legacyFlywayConfiguration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    legacyFlywayConfiguration.load().migrate();

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

    migrateTo(dataSource, "7");
    EntityEmptySelectedSourceIntakePostgresFixture.seedRetainedAssignment(legacyDsl, 81001L);
    EntityEmptySelectedSourceIntakePostgresFixture.seedRetainedRoster(
        legacyDsl, EntityEmptySelectedSourceIntakePostgresFixture.TENANT, null, 82001L);
    migrateTo(dataSource, "8");
    EntityEmptySelectedSourceIntakePostgresFixture.seedRetainedRoster(
        legacyDsl,
        EntityEmptySelectedSourceIntakePostgresFixture.TENANT,
        EntityEmptySelectedSourceIntakePostgresFixture.VERSION,
        null);
    migrateTo(dataSource, "9");
    retainedChecksums = retainedRowChecksums(legacyDsl);
    migrationChecksums =
        legacyDsl
            .fetch(
                "SELECT version, checksum FROM flyway_schema_history WHERE success AND version IS NOT NULL")
            .stream()
            .collect(
                Collectors.toMap(
                    row -> row.get(0, String.class), row -> row.get(1, Integer.class)));
    migrateTo(dataSource, null);
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
            new ReservedNumericKey("VERSION", 51001L, "LEGACY_NUMERIC"),
            new ReservedNumericKey("VERSION", 81001L, "LEGACY_NUMERIC"),
            new ReservedNumericKey("VERSION", 82001L, "LEGACY_NUMERIC"));
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
  void forwardMigrationPreservesRetainedRowsAndChecksumsWithoutFabricatingEmptyReceipts() {
    assertThat(retainedRowChecksums(dsl)).isEqualTo(retainedChecksums);
    assertThat(
            dsl
                .fetch(
                    "SELECT version, checksum FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4','5','6','7','8','9')")
                .stream()
                .collect(
                    Collectors.toMap(
                        row -> row.get(0, String.class), row -> row.get(1, Integer.class))))
        .isEqualTo(migrationChecksums);
    assertThat(
            dsl.fetchSingle(
                    "SELECT COUNT(*) FROM entity_canonical_gameplay_roster_snapshots WHERE pointer_version IS NULL AND active_world_epoch IS NULL")
                .get(0, Long.class))
        .isEqualTo(2L);
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    var repository = new EntityEmptySelectedSourceIntakeRepository(dsl);
    assertThatThrownBy(
            () ->
                repository.retainFresh(
                    fixture.inputs(),
                    EntityEmptySelectedSourceIntakeReceipt.requestDigest(
                        fixture.authorization().targetNamespace(),
                        fixture.authorization(),
                        fixture.freezeEvidence())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained owner history");
    assertThat(
            dsl.fetchSingle("SELECT COUNT(*) FROM entity_empty_selected_source_receipt")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT COUNT(*) FROM entity_empty_source_numeric_key_reservation WHERE claim_kind = 'CANONICAL_EMPTY_SOURCE'")
                .get(0, Long.class))
        .isZero();
    assertThat(retainedRowChecksums(dsl)).isEqualTo(retainedChecksums);
  }

  private static Map<String, String> retainedRowChecksums(DSLContext context) {
    return context
        .fetch(
            "SELECT 'actor' AS identity, md5(row_to_json(r)::text) AS checksum FROM characters r "
                + "UNION ALL SELECT assignment_uuid::text, md5(row_to_json(r)::text) FROM entity_preseeded_actor_assignment_operations r "
                + "UNION ALL SELECT snapshot_uuid::text, md5(row_to_json(r)::text) FROM entity_canonical_gameplay_roster_snapshots r")
        .stream()
        .collect(
            Collectors.toMap(row -> row.get(0, String.class), row -> row.get(1, String.class)));
  }

  private void migrateTo(DriverManagerDataSource dataSource, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) {
      configuration.target(target);
    }
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
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
