package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutEvents.SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutProjections.SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import org.flywaydb.core.Flyway;
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
  void runtimeIngressIdentityTreatsNullIdentityFieldsAsNotDistinct() {
    DSLContext dsl = migrateToLatest();
    ScriptEventIngressAuditRepository repository = new ScriptEventIngressAuditRepository(dsl);

    var first = repository.insertIfAbsentByIdentity(nullableRuntimeIngress());
    var second = repository.insertIfAbsentByIdentity(nullableRuntimeIngress());

    assertThat(first.inserted()).isTrue();
    assertThat(second.inserted()).isFalse();
    assertThat(first.audit().getId()).isEqualTo(second.audit().getId());
    assertThat(dsl.fetchCount(SCRIPT_EVENT_INGRESS_AUDIT)).isEqualTo(1);
  }

  @Test
  void pinnedEventAuditIdentityTreatsNullPluginFieldsAsNotDistinct() {
    DSLContext dsl = migrateToLatest();
    ScriptEventAuditRepository repository = new ScriptEventAuditRepository(dsl);

    var first = repository.insertIfAbsentByHandlerIdentity(nullablePinnedEventAudit());
    var second = repository.insertIfAbsentByHandlerIdentity(nullablePinnedEventAudit());

    assertThat(first.inserted()).isTrue();
    assertThat(second.inserted()).isFalse();
    assertThat(first.audit().getId()).isEqualTo(second.audit().getId());
    assertThat(dsl.fetchCount(SCRIPT_EVENT_AUDIT)).isEqualTo(1);
  }

  @Test
  void unpinnedEventAuditIdentityTreatsNullPluginFieldsAsNotDistinct() {
    DSLContext dsl = migrateToLatest();
    ScriptEventAuditRepository repository = new ScriptEventAuditRepository(dsl);

    var first = repository.insertIfAbsentByHandlerIdentity(nullableUnpinnedEventAudit());
    var second = repository.insertIfAbsentByHandlerIdentity(nullableUnpinnedEventAudit());

    assertThat(first.inserted()).isTrue();
    assertThat(second.inserted()).isFalse();
    assertThat(first.audit().getId()).isEqualTo(second.audit().getId());
    assertThat(dsl.fetchCount(SCRIPT_EVENT_AUDIT)).isEqualTo(1);
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

  private ScriptEventIngressAudit nullableRuntimeIngress() {
    ScriptEventIngressAudit ingress = new ScriptEventIngressAudit();
    ingress.setTenantId("tenant-runtime-null");
    ingress.setGameInstanceId("instance-runtime-null");
    ingress.setRegionId(null);
    ingress.setRegionEpoch(null);
    ingress.setEntityId(null);
    ingress.setEventType("onEnterRegion");
    ingress.setEventSchemaVersion("v1");
    ingress.setScriptPatchVersion("patch-runtime-null");
    ingress.setScriptPinEpoch(2L);
    ingress.setScriptPinControlPlaneRequestId("pin-runtime-null");
    ingress.setScriptEventId("event-runtime-null");
    ingress.setRequestDigest("a".repeat(64));
    ingress.setSourceService("game-session-service");
    ingress.setTriggerMode("EVENT");
    ingress.setAdmitted(true);
    ingress.setAdmissionOutcome("ADMITTED");
    ingress.setAdmissionReason("accepted");
    return ingress;
  }

  private ScriptEventAudit nullablePinnedEventAudit() {
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId("tenant-pinned-null");
    audit.setGameInstanceId("instance-pinned-null");
    audit.setRegionId("region-pinned-null");
    audit.setRegionEpoch(1L);
    audit.setEntityId("entity-pinned-null");
    audit.setScriptId("script-pinned-null");
    audit.setPluginId(null);
    audit.setPluginVersionId(null);
    audit.setEventType("onEnterRegion");
    audit.setEventSchemaVersion("v1");
    audit.setScriptPatchVersion("patch-pinned-null");
    audit.setScriptPinEpoch(2L);
    audit.setScriptPinControlPlaneRequestId("pin-pinned-null");
    audit.setScriptEventId("event-pinned-null");
    audit.setSourceService("game-session-service");
    audit.setTriggerMode("EVENT");
    audit.setFinalStage("HANDOFF");
    audit.setFinalOutcome("HANDED_OFF");
    audit.setFinalReason("accepted");
    return audit;
  }

  private ScriptEventAudit nullableUnpinnedEventAudit() {
    ScriptEventAudit audit = nullablePinnedEventAudit();
    audit.setTenantId("tenant-unpinned-null");
    audit.setGameInstanceId("instance-unpinned-null");
    audit.setRegionId("region-unpinned-null");
    audit.setEntityId("entity-unpinned-null");
    audit.setScriptId("script-unpinned-null");
    audit.setScriptPatchVersion("patch-unpinned-null");
    audit.setScriptPinEpoch(null);
    audit.setScriptPinControlPlaneRequestId(null);
    audit.setScriptEventId("event-unpinned-null");
    return audit;
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
