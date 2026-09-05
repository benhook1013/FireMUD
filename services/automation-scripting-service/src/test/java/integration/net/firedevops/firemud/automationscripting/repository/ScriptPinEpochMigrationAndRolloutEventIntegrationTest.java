package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutEvents.SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutProjections.SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class ScriptPinEpochMigrationAndRolloutEventIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private String schema;

  @AfterEach
  void dropIsolatedSchema() {
    if (schema != null) {
      adminDsl().execute("DROP SCHEMA " + schema + " CASCADE");
      schema = null;
    }
  }

  @Test
  void rolloutEventStoresAndUpdatesBlankOwnerEvidenceAsEmptyString() {
    DSLContext dsl = migrateToLatest();
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(dsl);

    ScriptPatchInstanceRolloutEvent unpinned = rolloutEvent("rollout-zero", 0L, " ");
    ScriptPatchInstanceRolloutEvent saved = repository.save(unpinned);

    assertThat(saved.getLastObservedControlPlaneRequestId()).isNull();
    assertThat(rawRequestId(dsl, saved.getId())).isEqualTo("");

    saved.setLastObservedControlPlaneRequestId("\t");
    saved.setStatusReason("updated-zero");
    ScriptPatchInstanceRolloutEvent updated = repository.save(saved);

    assertThat(updated.getLastObservedControlPlaneRequestId()).isNull();
    assertThat(rawRequestId(dsl, updated.getId())).isEqualTo("");

    ScriptPatchInstanceRolloutEvent pinned = rolloutEvent("rollout-positive", 4L, "owner-4");
    ScriptPatchInstanceRolloutEvent pinnedSaved = repository.save(pinned);
    assertThat(rawRequestId(dsl, pinnedSaved.getId())).isEqualTo("owner-4");

    pinnedSaved.setStatusReason("updated-positive");
    ScriptPatchInstanceRolloutEvent pinnedUpdated = repository.save(pinnedSaved);
    assertThat(rawRequestId(dsl, pinnedUpdated.getId())).isEqualTo("owner-4");
  }

  @Test
  void rolloutProjectionStoresBlankOwnerEvidenceAsEmptyStringWhileReadingItAsAbsent() {
    DSLContext dsl = migrateToLatest();
    ScriptPatchInstanceRolloutProjectionRepository repository =
        new ScriptPatchInstanceRolloutProjectionRepository(dsl);

    ScriptPatchInstanceRolloutProjection projection = rolloutProjection(0L, " ");
    ScriptPatchInstanceRolloutProjection saved = repository.save(projection);

    assertThat(saved.getLastObservedControlPlaneRequestId()).isNull();
    assertThat(rawProjectionRequestId(dsl, saved.getId())).isEqualTo("");
    assertThat(
            repository
                .findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
                    "tenant-projection", "instance-projection", "patch-projection")
                .orElseThrow()
                .getLastObservedControlPlaneRequestId())
        .isNull();

    saved.setLastObservedControlPlaneRequestId("\t");
    saved.setStatusReason("updated-zero");
    ScriptPatchInstanceRolloutProjection updated = repository.save(saved);

    assertThat(updated.getLastObservedControlPlaneRequestId()).isNull();
    assertThat(rawProjectionRequestId(dsl, updated.getId())).isEqualTo("");
  }

  @Test
  void v5FailsClosedOnDuplicatePreInstanceOnLoadRowsWithoutDeletingEvidence() {
    DSLContext dsl = migrateToV4();
    insertDuplicatePreInstanceOnLoadRows(dsl);

    assertThatThrownBy(this::migrateToLatestInSameSchema)
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("duplicate pre-instance");

    assertThat(countRows(dsl, "tenant-pre-instance")).isEqualTo(2);
    assertThat(
            dsl.fetchValue(
                "SELECT count(*) FROM script_event_ingress_audit "
                    + "WHERE tenant_id = 'tenant-pre-instance' AND game_instance_id IS NULL"))
        .isEqualTo(2L);
  }

  @Test
  void v5FailsClosedOnDuplicateRetainedRuntimeRowsWithoutDeletingEvidence() {
    DSLContext dsl = migrateToV4();
    insertDuplicateRetainedRuntimeRows(dsl);

    assertThatThrownBy(this::migrateToLatestInSameSchema)
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("duplicate retained runtime");

    assertThat(countRows(dsl, "tenant-retained-runtime")).isEqualTo(2);
    assertThat(
            dsl.fetchValue(
                "SELECT count(*) FROM script_event_ingress_audit "
                    + "WHERE tenant_id = 'tenant-retained-runtime' "
                    + "AND game_instance_id = 'instance-retained' "
                    + "AND region_id IS NULL AND region_epoch IS NULL"))
        .isEqualTo(2L);
  }

  @Test
  void v8NormalizesLegacyOneSidedPluginPairsBeforeTheCallbackEnforcesCoherence() {
    DSLContext dsl = migrateToV4();
    dsl.execute(
        "INSERT INTO script_work_items ("
            + "tenant_id, game_instance_id, region_id, region_epoch, entity_id, "
            + "script_id, plugin_id, plugin_version_id, event_type, event_schema_version, "
            + "script_patch_version, script_event_id, source_service, trigger_mode) VALUES ("
            + "'tenant-plugin-pair', 'instance-plugin-pair', 'region-plugin-pair', 1, "
            + "'entity-plugin-pair', 'legacy-script', NULL, 'legacy-version', 'onEnterRegion', "
            + "'v1', 'patch-legacy', 'event-plugin-pair', 'legacy-test', 'EVENT')");

    migrateToLatestInSameSchema();

    assertThat(
            dsl.fetch(
                    "SELECT plugin_id, plugin_version_id FROM script_work_items "
                        + "WHERE tenant_id = 'tenant-plugin-pair'")
                .intoMaps())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("plugin_id", "")
                    .containsEntry("plugin_version_id", ""));
  }

  @Test
  void callbackRejectsAOneSidedPairAfterV8HasCompleted() {
    DSLContext dsl = migrateToV4();
    dsl.execute(
        "INSERT INTO script_work_items ("
            + "tenant_id, game_instance_id, region_id, region_epoch, entity_id, "
            + "script_id, plugin_id, plugin_version_id, event_type, event_schema_version, "
            + "script_patch_version, script_event_id, source_service, trigger_mode) VALUES ("
            + "'tenant-plugin-pair-post-v8', 'instance-plugin-pair', 'region-plugin-pair', 1, "
            + "'entity-plugin-pair', 'legacy-script', NULL, 'legacy-version', 'onEnterRegion', "
            + "'v1', 'patch-legacy', 'event-plugin-pair', 'legacy-test', 'EVENT')");

    migrateToVersionEightInSameSchema();
    dsl.execute(
        "INSERT INTO script_work_items ("
            + "tenant_id, game_instance_id, region_id, region_epoch, entity_id, "
            + "script_id, plugin_id, plugin_version_id, event_type, event_schema_version, "
            + "script_patch_version, script_event_id, source_service, trigger_mode) VALUES ("
            + "'tenant-plugin-pair-post-v8-invalid', 'instance-plugin-pair', 'region-plugin-pair', 1, "
            + "'entity-plugin-pair', 'invalid-script', '', 'version-present', 'onEnterRegion', "
            + "'v1', 'patch-invalid', 'event-plugin-pair-invalid', 'legacy-test', 'EVENT')");

    assertThatThrownBy(this::migrateToLatestInSameSchema)
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("one-sided plugin identity pair");
  }

  private DSLContext migrateToLatest() {
    schema = newSchemaName();
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    return schemaDsl();
  }

  private DSLContext migrateToV4() {
    schema = newSchemaName();
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("4")
        .load()
        .migrate();
    return schemaDsl();
  }

  private void migrateToLatestInSameSchema() {
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
  }

  private void migrateToVersionEightInSameSchema() {
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("8")
        .load()
        .migrate();
  }

  private void insertDuplicatePreInstanceOnLoadRows(DSLContext dsl) {
    for (int i = 0; i < 2; i++) {
      dsl.execute(
          "INSERT INTO script_event_ingress_audit ("
              + "tenant_id, game_instance_id, region_id, region_epoch, entity_id, "
              + "playable_state_scope, script_id, event_type, event_schema_version, "
              + "script_patch_version, script_event_id, source_service, trigger_mode, "
              + "admitted, admission_outcome, admission_reason) VALUES ("
              + "'tenant-pre-instance', NULL, NULL, NULL, NULL, '', 'script-on-load', "
              + "'onLoad', 'v1', 'patch-legacy', 'on-load-event', 'legacy-test', 'ON_LOAD', "
              + "TRUE, 'ADMITTED', 'legacy evidence')");
    }
  }

  private void insertDuplicateRetainedRuntimeRows(DSLContext dsl) {
    for (int i = 0; i < 2; i++) {
      dsl.execute(
          "INSERT INTO script_event_ingress_audit ("
              + "tenant_id, game_instance_id, region_id, region_epoch, entity_id, "
              + "playable_state_scope, script_id, event_type, event_schema_version, "
              + "script_patch_version, script_event_id, source_service, trigger_mode, "
              + "admitted, admission_outcome, admission_reason) VALUES ("
              + "'tenant-retained-runtime', 'instance-retained', NULL, NULL, 'entity-retained', "
              + "'INSTANCE', 'script-runtime', 'onEnterRegion', 'v1', 'patch-legacy', "
              + "'retained-event', 'legacy-test', 'EVENT', TRUE, 'ADMITTED', 'legacy evidence')");
    }
  }

  private ScriptPatchInstanceRolloutEvent rolloutEvent(
      String eventId, long scriptPinEpoch, String requestId) {
    ScriptPatchInstanceRolloutEvent event = new ScriptPatchInstanceRolloutEvent();
    event.setEventId(eventId);
    event.setTenantId("tenant-rollout");
    event.setGameInstanceId("instance-rollout");
    event.setScriptPatchVersion("patch-rollout");
    event.setScriptPinEpoch(scriptPinEpoch);
    event.setLastObservedControlPlaneRequestId(requestId);
    event.setRolloutStatus("ROLLED_BACK");
    event.setStatusReason("initial");
    event.setObservedAt(Instant.parse("2026-08-01T00:00:01Z"));
    event.setProjectionRefreshedAt(Instant.parse("2026-08-01T00:00:02Z"));
    return event;
  }

  private ScriptPatchInstanceRolloutProjection rolloutProjection(
      long scriptPinEpoch, String requestId) {
    ScriptPatchInstanceRolloutProjection projection = new ScriptPatchInstanceRolloutProjection();
    projection.setTenantId("tenant-projection");
    projection.setGameInstanceId("instance-projection");
    projection.setScriptPatchVersion("patch-projection");
    projection.setScriptPinEpoch(scriptPinEpoch);
    projection.setLastObservedControlPlaneRequestId(requestId);
    projection.setRolloutStatus("ROLLED_BACK");
    projection.setStatusReason("initial");
    projection.setLastChangedAt(Instant.parse("2026-08-01T00:00:01Z"));
    projection.setProjectionRefreshedAt(Instant.parse("2026-08-01T00:00:02Z"));
    return projection;
  }

  private String rawRequestId(DSLContext dsl, Long id) {
    return dsl.fetchValue(
        SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID,
        SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ID.eq(id));
  }

  private String rawProjectionRequestId(DSLContext dsl, Long id) {
    return dsl.fetchValue(
        SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID,
        SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ID.eq(id));
  }

  private int countRows(DSLContext dsl, String tenantId) {
    return dsl.fetchCount(
        SCRIPT_EVENT_INGRESS_AUDIT, SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID.eq(tenantId));
  }

  private DSLContext schemaDsl() {
    return DSL.using(dataSource(schema), SQLDialect.POSTGRES);
  }

  private DSLContext adminDsl() {
    return DSL.using(dataSource(null), SQLDialect.POSTGRES);
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

  private String newSchemaName() {
    return "automation_pin_" + UUID.randomUUID().toString().replace("-", "");
  }
}
