package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeRequestHistory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
class PluginRuntimeLifecycleFenceIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private String schema;

  @BeforeAll
  void migrateSchema() {
    schema = "plugin_runtime_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  @AfterAll
  void dropSchema() {
    if (schema != null) {
      adminDsl().execute("DROP SCHEMA " + schema + " CASCADE");
      schema = null;
    }
  }

  @Test
  void failedReceiptIsImmutableAndLifecycleAdvisoryLockSerializesConcurrentActivation()
      throws Exception {
    PluginRuntimeRequestHistoryRepository history = new PluginRuntimeRequestHistoryRepository(dsl);
    PluginRuntimeRequestHistory first = failedReceipt("drain-1", "digest-a");
    PluginRuntimeRequestHistory saved = history.insertOrGet(first);
    PluginRuntimeRequestHistory changed = failedReceipt("drain-1", "digest-b");
    changed.setPluginState("ENABLED");
    changed.setPluginActivationEpoch(9L);
    changed.setLifecycleRevision(9L);
    assertThatThrownBy(() -> history.insertOrGet(changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("control_plane_request_id already records a different plugin request");

    PluginRuntimeRequestHistory crossOperation = failedReceipt("drain-1", "digest-a");
    crossOperation.setOperation("ACTIVATE");
    assertThatThrownBy(() -> history.insertOrGet(crossOperation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("control_plane_request_id already records a different plugin request");

    PluginRuntimeRequestHistory exactRetry = failedReceipt("drain-1", "digest-a");
    PluginRuntimeRequestHistory winner = history.insertOrGet(exactRetry);
    assertThat(winner.getRequestFingerprint()).isEqualTo("digest-a");
    assertThat(winner.getRequestOutcome()).isEqualTo("FAILED");
    assertThat(winner.getFailureCode()).isEqualTo("FAILED_PRECONDITION");
    assertThat(winner.getPluginState()).isEqualTo(saved.getPluginState());
    assertThat(winner.getPluginActivationEpoch()).isZero();

    CountDownLatch firstHasLock = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondReachedLockCall = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> firstTransaction =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        new PluginRuntimeStateRepository(DSL.using(configuration))
                            .lockLifecycleScope("1", "game-1", "plugin-1");
                        firstHasLock.countDown();
                        await(releaseFirst);
                      }));
      assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> secondTransaction =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        secondReachedLockCall.countDown();
                        new PluginRuntimeStateRepository(DSL.using(configuration))
                            .lockLifecycleScope("1", "game-1", "plugin-1");
                      }));
      assertThat(secondReachedLockCall.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100);
      assertThat(secondTransaction.isDone()).isFalse();
      releaseFirst.countDown();
      firstTransaction.get(5, TimeUnit.SECONDS);
      secondTransaction.get(5, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void nextEligibleDefaultUsesUtcWhenSessionTimezoneIsNonUtc() {
    dsl.transaction(
        configuration -> {
          DSLContext transactionDsl = DSL.using(configuration);
          transactionDsl.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
          transactionDsl.execute(
              "insert into script_work_items "
                  + "(tenant_id, game_instance_id, region_id, region_epoch, entity_id, script_id, "
                  + "plugin_id, plugin_version_id, event_type, event_schema_version, "
                  + "script_patch_version, script_event_id, source_service, trigger_mode) "
                  + "values ('tenant-utc', 'instance-utc', 'region-1', 1, 'entity-1', 'script-1', "
                  + "'plugin-1', 'version-1', 'onCommand', '1', 'patch-1', "
                  + "'event-utc-default', 'test', 'MANUAL')");

          assertThat(
                  transactionDsl.fetchValue(
                      "select next_eligible_at = pg_catalog.timezone('UTC', current_timestamp) "
                          + "from script_work_items where script_event_id = 'event-utc-default'",
                      Boolean.class))
              .isTrue();
        });
  }

  @Test
  void migrationFoundsRetainedRuntimeFenceAndOnlyMatchesCompleteScheduleProvenance() {
    String retainedSchema = newSchemaName();
    try {
      DSLContext retainedDsl = migrateToVersionTwo(retainedSchema);
      retainedDsl.execute(
          "insert into plugin_runtime_states "
              + "(tenant_id, game_instance_id, runtime_region_id, runtime_region_epoch, plugin_id, "
              + "active_plugin_version_id, plugin_state, status_reason) "
              + "values ('tenant-retained', 'instance-retained', 'region-1', 7, 'plugin-retained', "
              + "'version-1', 'PLUGIN_STATE_ENABLED', 'retained'), "
              + "('tenant-empty', 'instance-empty', null, null, 'plugin-empty', '', 'DISABLED', 'retained')");
      insertSchedule(
          retainedDsl,
          "tenant-retained",
          "instance-retained",
          "plugin-retained",
          "version-1",
          "region-1",
          7L,
          "schedule-matching");
      insertSchedule(
          retainedDsl,
          "tenant-retained",
          "instance-retained",
          "plugin-retained",
          "version-old",
          "region-1",
          7L,
          "schedule-mismatched-version");
      insertSchedule(
          retainedDsl,
          "tenant-retained",
          "instance-retained",
          "plugin-retained",
          "version-1",
          "region-2",
          7L,
          "schedule-mismatched-region");
      insertSchedule(
          retainedDsl,
          "tenant-retained",
          "instance-retained",
          "plugin-retained",
          "version-1",
          "region-1",
          8L,
          "schedule-mismatched-region-epoch");
      insertSchedule(
          retainedDsl,
          "tenant-retained",
          "instance-retained",
          "plugin-retained",
          "version-1",
          "",
          null,
          "schedule-missing-region");
      insertSchedule(
          retainedDsl,
          "tenant-empty",
          "instance-empty",
          "plugin-empty",
          "version-never-active",
          "region-1",
          7L,
          "schedule-orphaned");
      retainedDsl.execute(
          "insert into script_work_items "
              + "(tenant_id, game_instance_id, region_id, region_epoch, entity_id, script_id, "
              + "plugin_id, plugin_version_id, event_type, event_schema_version, script_patch_version, "
              + "script_event_id, source_service, trigger_mode) "
              + "values ('tenant-retained', 'instance-retained', 'region-1', 1, 'entity-1', 'script-1', "
              + "'plugin-retained', 'version-1', 'onCommand', '1', 'patch-1', 'event-1', 'test', 'MANUAL')");

      migrateExistingSchemaToLatest(retainedSchema);
      DSLContext migratedDsl = schemaDsl(retainedSchema);

      assertThat(fencePair(migratedDsl, "plugin_runtime_states", "tenant-retained"))
          .containsExactly(1L, 1L);
      assertThat(fencePair(migratedDsl, "plugin_runtime_states", "tenant-empty"))
          .containsExactly(0L, 0L);
      assertThat(fencePair(migratedDsl, "script_schedule_instances", "schedule-matching"))
          .containsExactly(1L, 1L);
      assertThat(fencePair(migratedDsl, "script_schedule_instances", "schedule-mismatched-version"))
          .containsExactly(0L, 0L);
      assertThat(fencePair(migratedDsl, "script_schedule_instances", "schedule-mismatched-region"))
          .containsExactly(0L, 0L);
      assertThat(
              fencePair(
                  migratedDsl, "script_schedule_instances", "schedule-mismatched-region-epoch"))
          .containsExactly(0L, 0L);
      assertThat(fencePair(migratedDsl, "script_schedule_instances", "schedule-missing-region"))
          .containsExactly(0L, 0L);
      assertThat(fencePair(migratedDsl, "script_schedule_instances", "schedule-orphaned"))
          .containsExactly(0L, 0L);
      assertThat(fencePair(migratedDsl, "script_work_items", "event-1")).containsExactly(0L, 0L);
    } finally {
      dropSchema(retainedSchema);
    }
  }

  @Test
  void migrationFailsClosedOnRetainedEnabledRuntimeWithoutActiveVersion() {
    String contradictorySchema = newSchemaName();
    try {
      DSLContext retainedDsl = migrateToVersionTwo(contradictorySchema);
      retainedDsl.execute(
          "insert into plugin_runtime_states "
              + "(tenant_id, game_instance_id, plugin_id, active_plugin_version_id, plugin_state, status_reason) "
              + "values ('tenant-contradictory', 'instance-contradictory', 'plugin-contradictory', '', 'PLUGIN_STATE_ENABLED', 'retained')");

      assertThatThrownBy(() -> migrateExistingSchemaToLatest(contradictorySchema))
          .isInstanceOf(FlywayException.class)
          .hasMessageContaining(
              "V3 cannot establish plugin lifecycle fence for executable runtime state without active plugin version");
    } finally {
      dropSchema(contradictorySchema);
    }
  }

  @Test
  void databaseRejectsIncoherentAndNegativePluginFencePairs() {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "insert into plugin_runtime_states "
                        + "(tenant_id, game_instance_id, plugin_id, active_plugin_version_id, "
                        + "plugin_state, status_reason, plugin_activation_epoch, lifecycle_revision) "
                        + "values ('tenant-invalid', 'instance-active-zero', 'plugin-invalid', "
                        + "'version-active', 'PLUGIN_STATE_ENABLED', 'invalid', 0, 0)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_plugin_runtime_states_plugin_fence");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "insert into plugin_runtime_states "
                        + "(tenant_id, game_instance_id, plugin_id, plugin_state, "
                        + "status_reason, plugin_activation_epoch, lifecycle_revision) "
                        + "values ('tenant-invalid', 'instance-incoherent', 'plugin-invalid', "
                        + "'DISABLED', 'invalid', 1, 0)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_plugin_runtime_states_plugin_fence");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "insert into plugin_runtime_states "
                        + "(tenant_id, game_instance_id, plugin_id, plugin_state, "
                        + "status_reason, plugin_activation_epoch, lifecycle_revision) "
                        + "values ('tenant-invalid', 'instance-negative', 'plugin-invalid', "
                        + "'DISABLED', 'invalid', -1, -1)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_plugin_runtime_states_plugin_fence");

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "insert into plugin_runtime_request_history "
                        + "(tenant_id, game_instance_id, plugin_id, operation, "
                        + "control_plane_request_id, request_fingerprint, plugin_state, "
                        + "plugin_activation_epoch, lifecycle_revision) "
                        + "values ('tenant-invalid', 'instance-incoherent', 'plugin-invalid', "
                        + "'DISABLE', 'request-incoherent', 'fingerprint', 'DISABLED', 1, 0)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_plugin_runtime_request_history_plugin_fence");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "insert into plugin_runtime_request_history "
                        + "(tenant_id, game_instance_id, plugin_id, operation, "
                        + "control_plane_request_id, request_fingerprint, plugin_state, "
                        + "plugin_activation_epoch, lifecycle_revision) "
                        + "values ('tenant-invalid', 'instance-negative', 'plugin-invalid', "
                        + "'DISABLE', 'request-negative', 'fingerprint', 'DISABLED', -1, -1)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_plugin_runtime_request_history_plugin_fence");
  }

  private static PluginRuntimeRequestHistory failedReceipt(String requestId, String digest) {
    PluginRuntimeRequestHistory history = new PluginRuntimeRequestHistory();
    history.setTenantId("1");
    history.setGameInstanceId("game-1");
    history.setPluginId("plugin-1");
    history.setOperation("DRAIN");
    history.setControlPlaneRequestId(requestId);
    history.setRequestFingerprint(digest);
    history.setPreviousPluginVersionId("");
    history.setActivePluginVersionId("");
    history.setPluginState("DISABLED");
    history.setRequestOutcome("FAILED");
    history.setFailureCode("FAILED_PRECONDITION");
    history.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return history;
  }

  private DriverManagerDataSource dataSource(String targetSchema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + targetSchema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private DSLContext adminDsl() {
    return DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private DSLContext migrateToVersionTwo(String targetSchema) {
    Flyway.configure()
        .dataSource(dataSource(targetSchema))
        .locations(MIGRATION_LOCATION)
        .schemas(targetSchema)
        .defaultSchema(targetSchema)
        .target("2")
        .load()
        .migrate();
    return schemaDsl(targetSchema);
  }

  private void migrateExistingSchemaToLatest(String targetSchema) {
    Flyway.configure()
        .dataSource(dataSource(targetSchema))
        .locations(MIGRATION_LOCATION)
        .schemas(targetSchema)
        .defaultSchema(targetSchema)
        .load()
        .migrate();
  }

  private DSLContext schemaDsl(String targetSchema) {
    return DSL.using(dataSource(targetSchema), SQLDialect.POSTGRES);
  }

  private void dropSchema(String targetSchema) {
    adminDsl().execute("DROP SCHEMA " + targetSchema + " CASCADE");
  }

  private String newSchemaName() {
    return "plugin_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
  }

  private void insertSchedule(
      DSLContext dsl,
      String tenantId,
      String gameInstanceId,
      String pluginId,
      String pluginVersionId,
      String runtimeRegionId,
      Long runtimeRegionEpoch,
      String scheduleDefinitionId) {
    dsl.execute(
        "insert into script_schedule_instances "
            + "(tenant_id, game_instance_id, script_patch_version, script_id, plugin_id, plugin_version_id, "
            + "event_type, schedule_definition_id, schedule_kind, cadence_value, cadence_unit, "
            + "materialization_status, runtime_region_id, runtime_region_epoch, "
            + "schedule_metadata_json, schedule_semantics_hash) "
            + "values (?, ?, 'patch-1', 'script-1', ?, ?, 'onInterval', ?, 'INTERVAL', 1, 'SECONDS', "
            + "'MATERIALIZED', ?, ?, '{}', 'schedule-hash')",
        tenantId,
        gameInstanceId,
        pluginId,
        pluginVersionId,
        scheduleDefinitionId,
        runtimeRegionId,
        runtimeRegionEpoch);
  }

  private java.util.List<Long> fencePair(DSLContext dsl, String table, String identity) {
    String identityColumn =
        table.equals("plugin_runtime_states") ? "tenant_id" : "schedule_definition_id";
    if (table.equals("script_work_items")) {
      identityColumn = "script_event_id";
    }
    var row =
        dsl.fetchOne(
            "select plugin_activation_epoch, lifecycle_revision from "
                + table
                + " where "
                + identityColumn
                + " = ?",
            identity);
    assertThat(row).isNotNull();
    return java.util.List.of(
        row.get("plugin_activation_epoch", Long.class), row.get("lifecycle_revision", Long.class));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
