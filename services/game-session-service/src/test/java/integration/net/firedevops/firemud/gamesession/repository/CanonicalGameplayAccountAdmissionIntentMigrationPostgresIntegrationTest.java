package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-only retained-row proof for V1027 acquisition intent and the V1028 continuation.
 * Seeded ACTIVE/HISTORICAL owners are controlled historical inputs, not producer-authority proofs.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayAccountAdmissionIntentMigrationPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String CONTROL = "game_session_canonical_account_coverage_control";
  private static final String COVERAGE_OPERATION =
      "game_session_canonical_account_coverage_operation";
  private static final String LEGACY_DIGEST = "sha256:" + "1".repeat(64);
  private static final String MEMBER_DIGEST = "sha256:" + "2".repeat(64);
  private static final OffsetDateTime RETAINED_UPDATED_AT =
      OffsetDateTime.parse("2026-10-26T00:00:00Z");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void v1027AndV1028PreserveRetainedV31ControlPostimagesWithoutInferringIntent() {
    String schema =
        "gs_admission_intent_migration_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway throughV1026 = flyway(dataSource, schema, MigrationVersion.fromVersion("1026"));
      throughV1026.migrate();
      throughV1026.validate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      assertLatestMigration(dsl, "1026");

      RetainedControls controls = seedRetainedControls(dsl);
      Map<UUID, String> originalPostimages = readControlPostimages(dsl, false);
      assertThat(originalPostimages).hasSize(3);
      assertThat(originalPostimages.keySet())
          .containsExactlyInAnyOrder(
              controls.noActiveAccountId(),
              controls.activeAccountId(),
              controls.historicalAccountId());

      Flyway throughV1028 = flyway(dataSource, schema, MigrationVersion.fromVersion("1028"));
      throughV1028.migrate();
      throughV1028.validate();
      assertRetainedState(dsl, originalPostimages);

      // A newly constructed Flyway instance models a process restart against the retained schema.
      Flyway restarted = flyway(dataSource, schema, MigrationVersion.fromVersion("1028"));
      restarted.validate();
      restarted.migrate();
      restarted.validate();
      assertRetainedState(dsl, originalPostimages);
    } finally {
      dropSchema(schema);
    }
  }

  private static RetainedControls seedRetainedControls(DSLContext dsl) {
    UUID noActiveAccountId = UUID.randomUUID();
    UUID activeAccountId = UUID.randomUUID();
    UUID historicalAccountId = UUID.randomUUID();
    UUID activeOperationId = UUID.randomUUID();
    UUID historicalOperationId = UUID.randomUUID();
    UUID activeAdmissionFence = UUID.randomUUID();
    UUID activeCoverageFence = UUID.randomUUID();
    UUID historicalAdmissionFence = UUID.randomUUID();
    UUID historicalCoverageFence = UUID.randomUUID();

    // These are deliberate V31-shaped migration inputs. They do not assert that a live account
    // coverage producer ran or that any account is eligible for admission.
    dsl.execute(
        "INSERT INTO "
            + CONTROL
            + " (account_id, state, last_operation_fence, updated_at)"
            + " VALUES (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0, CAST(? AS timestamp with time zone)),"
            + " (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0, CAST(? AS timestamp with time zone)),"
            + " (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0, CAST(? AS timestamp with time zone))",
        noActiveAccountId,
        RETAINED_UPDATED_AT,
        activeAccountId,
        RETAINED_UPDATED_AT,
        historicalAccountId,
        RETAINED_UPDATED_AT);

    insertCoverageOperation(
        dsl,
        activeOperationId,
        activeAccountId,
        "ACTIVE",
        5,
        activeCoverageFence,
        activeAdmissionFence,
        null,
        null,
        null,
        null,
        null);
    insertCoverageOperation(
        dsl,
        historicalOperationId,
        historicalAccountId,
        "HISTORICAL",
        7,
        historicalCoverageFence,
        null,
        historicalAdmissionFence,
        17,
        23,
        0L,
        MEMBER_DIGEST);

    dsl.execute(
        "UPDATE "
            + CONTROL
            + " SET state = 'ACTIVE', last_operation_fence = 5, current_operation_id = ?,"
            + " current_operation_fence = 5, account_admission_fence = ?, coverage_fence = ?,"
            + " inventory_snapshot_revision = NULL, coverage_generation = NULL, updated_at = CAST(? AS timestamp with time zone)"
            + " WHERE account_id = ?",
        activeOperationId,
        activeAdmissionFence,
        activeCoverageFence,
        RETAINED_UPDATED_AT,
        activeAccountId);
    dsl.execute(
        "UPDATE "
            + CONTROL
            + " SET state = 'HISTORICAL', last_operation_fence = 7, current_operation_id = ?,"
            + " current_operation_fence = 7, historical_account_admission_fence = ?,"
            + " coverage_fence = ?, inventory_snapshot_revision = 17, coverage_generation = 23,"
            + " updated_at = CAST(? AS timestamp with time zone) WHERE account_id = ?",
        historicalOperationId,
        historicalAdmissionFence,
        historicalCoverageFence,
        RETAINED_UPDATED_AT,
        historicalAccountId);

    return new RetainedControls(noActiveAccountId, activeAccountId, historicalAccountId);
  }

  private static void insertCoverageOperation(
      DSLContext dsl,
      UUID operationId,
      UUID accountId,
      String lifecycle,
      int operationFence,
      UUID coverageFence,
      UUID accountAdmissionFence,
      UUID historicalAccountAdmissionFence,
      Integer inventorySnapshotRevision,
      Integer coverageGeneration,
      Long redisMemberCount,
      String redisMemberSetDigest) {
    dsl.execute(
        "INSERT INTO "
            + COVERAGE_OPERATION
            + " (operation_id, request_id, account_id, legacy_cohort_id,"
            + " legacy_owner_operation_id, legacy_writer_fence,"
            + " legacy_source_snapshot_revision, legacy_canonical_inventory_revision,"
            + " legacy_namespace_index_readback_revision, legacy_evidence_digest, lifecycle,"
            + " operation_fence, coverage_fence, account_admission_fence,"
            + " historical_account_admission_fence, resolved_scope_kind, snapshot_state,"
            + " blocked_reason, inventory_snapshot_revision, coverage_generation,"
            + " redis_member_count, redis_member_set_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, 1, 1, ?, ?, ?, ?, ?, ?, 'ACCOUNT_WIDE',"
            + " 'COMPLETE', NULL, ?, ?, ?, ?)",
        operationId,
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LEGACY_DIGEST,
        lifecycle,
        operationFence,
        coverageFence,
        accountAdmissionFence,
        historicalAccountAdmissionFence,
        inventorySnapshotRevision,
        coverageGeneration,
        redisMemberCount,
        redisMemberSetDigest);
  }

  private static void assertRetainedState(DSLContext dsl, Map<UUID, String> originalPostimages) {
    assertLatestMigration(dsl, "1028");
    assertThat(readControlPostimages(dsl, true)).isEqualTo(originalPostimages);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) AS row_count FROM "
                        + CONTROL
                        + " WHERE current_fence_intent_id IS NOT NULL")
                .get("row_count", Long.class))
        .isZero();
    assertEmptyTable(dsl, "game_session_canonical_account_admission_fence_intent");
    assertEmptyTable(dsl, "game_session_canonical_account_admission_fence_intent_cohort_row");
    assertEmptyTable(dsl, "game_session_canonical_account_admission_fence_intent_barrier_target");
  }

  private static Map<UUID, String> readControlPostimages(
      DSLContext dsl, boolean removeAddedIntentColumn) {
    String imageExpression =
        removeAddedIntentColumn
            ? "(to_jsonb(c) - 'current_fence_intent_id')::text"
            : "to_jsonb(c)::text";
    Map<UUID, String> postimages = new LinkedHashMap<>();
    for (Record row :
        dsl.fetch(
            "SELECT c.account_id, "
                + imageExpression
                + " AS row_image FROM "
                + CONTROL
                + " c ORDER BY c.account_id")) {
      postimages.put(row.get("account_id", UUID.class), row.get("row_image", String.class));
    }
    return Map.copyOf(postimages);
  }

  private static void assertEmptyTable(DSLContext dsl, String table) {
    assertThat(
            dsl.fetchSingle("SELECT count(*) AS row_count FROM " + table)
                .get("row_count", Long.class))
        .as(table)
        .isZero();
  }

  private static void assertLatestMigration(DSLContext dsl, String expectedVersion) {
    assertThat(
            dsl.fetchSingle(
                    "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                .get("version", String.class))
        .isEqualTo(expectedVersion);
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    return target == null ? configuration.load() : configuration.target(target).load();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Failed to dispose account-admission intent test schema", failure);
    }
  }

  private record RetainedControls(
      UUID noActiveAccountId, UUID activeAccountId, UUID historicalAccountId) {}
}
