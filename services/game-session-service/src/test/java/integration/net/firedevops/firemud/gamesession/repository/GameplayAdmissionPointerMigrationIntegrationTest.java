package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameplayAdmissionPointerMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long RETAINED_BOOTSTRAP_TENANT_ID = 2942L;
  private static final String LEGACY_BOOTSTRAP_ACTOR = "system/bootstrap";
  private static final String LEGACY_BOOTSTRAP_REASON = "Initial gameplay pointer bootstrap";
  private static final String MIGRATION_ACTOR = "system/migration";
  private static final String MIGRATION_REASON =
      "Repair duplicate public production in retained bootstrap catalog";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void v8RepairsOnlyProvenRetainedBootstrapPairAndLeavesDemoAndRuntimeIdentityUnchanged()
      throws IOException {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(true, false);
    DSLContext dsl = fixture.dsl();
    Record demoBefore = pointerRecordForTenant(dsl, RETAINED_BOOTSTRAP_TENANT_ID, "demo");
    Record sandboxBefore = pointerRecordForTenant(dsl, RETAINED_BOOTSTRAP_TENANT_ID, "sandbox");
    UUID demoRealmIdBefore = demoBefore.get("realm_id", UUID.class);
    UUID sandboxRealmIdBefore = sandboxBefore.get("realm_id", UUID.class);
    UUID namespaceIdBefore = sandboxBefore.get("playable_state_namespace_id", UUID.class);

    assertThat(namespaceIdBefore)
        .isEqualTo(demoBefore.get("playable_state_namespace_id", UUID.class));
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT catalog_revision FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ?",
                Long.class,
                bootstrapRequestId(RETAINED_BOOTSTRAP_TENANT_ID, 102L, "sandbox")))
        .isNull();
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT realm_id FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ?",
                UUID.class,
                bootstrapRequestId(RETAINED_BOOTSTRAP_TENANT_ID, 102L, "sandbox")))
        .isNull();

    Flyway flyway = migrateToVersion(fixture.dataSource(), "8");

    Record demoAfter = pointerRecordForTenant(dsl, RETAINED_BOOTSTRAP_TENANT_ID, "demo");
    Record sandboxAfter = pointerRecordForTenant(dsl, RETAINED_BOOTSTRAP_TENANT_ID, "sandbox");
    assertThat(demoAfter.get("public_production_realm", Boolean.class)).isTrue();
    assertThat(demoAfter.get("pointer_version", Long.class))
        .isEqualTo(demoBefore.get("pointer_version", Long.class));
    assertThat(demoAfter.get("catalog_revision", Long.class)).isEqualTo(1L);
    assertThat(demoAfter.get("game_instance_id", Long.class))
        .isEqualTo(demoBefore.get("game_instance_id", Long.class));
    assertThat(demoAfter.get("realm_id", UUID.class)).isEqualTo(demoRealmIdBefore);
    assertThat(demoAfter.get("playable_state_namespace_id", UUID.class))
        .isEqualTo(namespaceIdBefore);

    assertThat(sandboxAfter.get("public_production_realm", Boolean.class)).isFalse();
    assertThat(sandboxAfter.get("pointer_version", Long.class))
        .isEqualTo(sandboxBefore.get("pointer_version", Long.class));
    assertThat(sandboxAfter.get("catalog_revision", Long.class)).isEqualTo(2L);
    assertThat(sandboxAfter.get("game_instance_id", Long.class))
        .isEqualTo(sandboxBefore.get("game_instance_id", Long.class));
    assertThat(sandboxAfter.get("realm_id", UUID.class)).isEqualTo(sandboxRealmIdBefore);
    assertThat(sandboxAfter.get("playable_state_namespace_id", UUID.class))
        .isEqualTo(namespaceIdBefore);
    assertThat(sandboxAfter.get("last_updated_by", String.class)).isEqualTo(MIGRATION_ACTOR);
    assertThat(sandboxAfter.get("last_update_reason", String.class)).isEqualTo(MIGRATION_REASON);

    String migrationRequestId = migrationRequestId(RETAINED_BOOTSTRAP_TENANT_ID);
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ?",
                Long.class,
                migrationRequestId))
        .isEqualTo(1L);
    Record migrationEvent =
        dsl.fetchOne(
            "SELECT * FROM gameplay_admission_pointer_event WHERE control_plane_request_id = ?",
            migrationRequestId);
    assertThat(migrationEvent).isNotNull();
    assertThat(migrationEvent.get("pointer_version", Long.class)).isEqualTo(1L);
    assertThat(migrationEvent.get("catalog_revision", Long.class)).isEqualTo(2L);
    assertThat(migrationEvent.get("realm_id", UUID.class)).isEqualTo(sandboxRealmIdBefore);
    assertThat(migrationEvent.get("playable_state_namespace_id", UUID.class))
        .isEqualTo(namespaceIdBefore);
    assertThat(migrationEvent.get("public_production_realm", Boolean.class)).isFalse();
    assertThat(migrationEvent.get("actor_principal", String.class)).isEqualTo(MIGRATION_ACTOR);
    assertThat(migrationEvent.get("reason", String.class)).isEqualTo(MIGRATION_REASON);
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer_event WHERE tenant_id = ? "
                    + "AND world_slug = 'sandbox' AND realm_slug = 'production'",
                Long.class,
                RETAINED_BOOTSTRAP_TENANT_ID))
        .isEqualTo(2L);
    Record originalBootstrapEvent =
        dsl.fetchOne(
            "SELECT * FROM gameplay_admission_pointer_event "
                + "WHERE control_plane_request_id = ?",
            bootstrapRequestId(RETAINED_BOOTSTRAP_TENANT_ID, 102L, "sandbox"));
    assertThat(originalBootstrapEvent).isNotNull();
    assertThat(originalBootstrapEvent.get("catalog_revision", Long.class)).isNull();
    assertThat(originalBootstrapEvent.get("realm_id", UUID.class)).isNull();
    assertThat(originalBootstrapEvent.get("playable_state_namespace_id", UUID.class)).isNull();

    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(worldViewsFromDatabase(dsl));
    assertThat(catalog.publicProductionRealmCardinality(RETAINED_BOOTSTRAP_TENANT_ID))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
    assertThat(catalog.resolvePublicWorldFromAuthoritySnapshot("demo"))
        .hasValueSatisfying(
            world -> assertThat(catalog.resolveRealm(world, "production")).isPresent());

    dsl.execute(
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/V8__repair_legacy_bootstrap_public_realm.sql")));
    flyway.migrate();
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ?",
                Long.class,
                migrationRequestId))
        .isEqualTo(1L);
    assertThat(
            pointerRecordForTenant(dsl, RETAINED_BOOTSTRAP_TENANT_ID, "sandbox")
                .get("catalog_revision", Long.class))
        .isEqualTo(2L);
  }

  @Test
  void v8LeavesOperatorModifiedBootstrapPairUntouched() {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(true, false);
    fixture
        .dsl()
        .execute(
            "UPDATE gameplay_admission_pointer SET last_updated_by = 'operator/alice', "
                + "last_update_reason = 'operator changed catalog' WHERE tenant_id = ? "
                + "AND world_slug = 'sandbox'",
            RETAINED_BOOTSTRAP_TENANT_ID);

    assertBootstrapPairNotRepaired(fixture);
    assertThat(
            pointerRecordForTenant(fixture.dsl(), RETAINED_BOOTSTRAP_TENANT_ID, "sandbox")
                .get("last_updated_by", String.class))
        .isEqualTo("operator/alice");
  }

  @Test
  void v8LeavesBootstrapPairWithoutExactCreationProvenanceUntouched() {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(false, false);

    assertBootstrapPairNotRepaired(fixture);
  }

  @Test
  void v8LeavesTenantWithAnAdditionalPublicRealmUntouched() {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(true, true);

    assertBootstrapPairNotRepaired(fixture);
    assertThat(
            fetchTypedValue(
                fixture.dsl(),
                "SELECT COUNT(*) FROM gameplay_admission_pointer WHERE tenant_id = ? "
                    + "AND public_production_realm = true",
                Long.class,
                RETAINED_BOOTSTRAP_TENANT_ID))
        .isEqualTo(3L);
  }

  @Test
  void v8LeavesBootstrapPairWithIncompleteCurrentIdentityUntouched() {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(true, false);
    fixture
        .dsl()
        .execute(
            "UPDATE gameplay_admission_pointer SET realm_id = NULL, "
                + "playable_state_namespace_id = NULL WHERE tenant_id = ? "
                + "AND world_slug = 'sandbox'",
            RETAINED_BOOTSTRAP_TENANT_ID);

    assertBootstrapPairNotRepaired(fixture);
    Record sandbox = pointerRecordForTenant(fixture.dsl(), RETAINED_BOOTSTRAP_TENANT_ID, "sandbox");
    assertThat(sandbox.get("realm_id", UUID.class)).isNull();
    assertThat(sandbox.get("playable_state_namespace_id", UUID.class)).isNull();
  }

  @Test
  void v8LeavesBootstrapPairWithUnknownScopeUntouched() {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(true, false);
    fixture
        .dsl()
        .execute(
            "UPDATE gameplay_admission_pointer SET state_scope = 'UNKNOWN' WHERE tenant_id = ? "
                + "AND world_slug = 'sandbox'",
            RETAINED_BOOTSTRAP_TENANT_ID);

    assertBootstrapPairNotRepaired(fixture);
    assertThat(
            pointerRecordForTenant(fixture.dsl(), RETAINED_BOOTSTRAP_TENANT_ID, "sandbox")
                .get("state_scope", String.class))
        .isEqualTo("UNKNOWN");
  }

  @Test
  void v8LeavesBootstrapPairUntouchedWhenMigrationRequestIdAlreadyExists() {
    LegacyBootstrapFixture fixture = legacyBootstrapFixture(true, false);
    insertConflictingMigrationEvent(fixture.dsl(), RETAINED_BOOTSTRAP_TENANT_ID);

    assertBootstrapPairNotRepaired(fixture, 1L);
    Record existingEvent =
        fixture
            .dsl()
            .fetchOne(
                "SELECT * FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ?",
                migrationRequestId(RETAINED_BOOTSTRAP_TENANT_ID));
    assertThat(existingEvent).isNotNull();
    assertThat(existingEvent.get("actor_principal", String.class)).isEqualTo("operator/conflict");
  }

  private static LegacyBootstrapFixture legacyBootstrapFixture(
      boolean includeSandboxCreationEvent, boolean includeAdditionalPublicRealm) {
    DriverManagerDataSource dataSource = isolatedDataSource();
    migrateToVersion(dataSource, "5");
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    insertLegacyBootstrapPointer(dsl, 1L, "demo", "Demo World", 101L, false);
    insertLegacyBootstrapPointer(dsl, 2L, "sandbox", "Builder Sandbox", 102L, true);
    if (includeAdditionalPublicRealm) {
      insertPointer(
          dsl, 3L, RETAINED_BOOTSTRAP_TENANT_ID, "other", "public", 103L, true, true, "SHARED");
    }

    migrateToVersion(dataSource, "6");
    insertLegacyBootstrapCreationEvent(dsl, 8101L, "demo", "Demo World", 101L, false);
    if (includeSandboxCreationEvent) {
      insertLegacyBootstrapCreationEvent(dsl, 8102L, "sandbox", "Builder Sandbox", 102L, true);
    }
    migrateToVersion(dataSource, "7");
    return new LegacyBootstrapFixture(dataSource, dsl);
  }

  private static void assertBootstrapPairNotRepaired(LegacyBootstrapFixture fixture) {
    assertBootstrapPairNotRepaired(fixture, 0L);
  }

  private static void assertBootstrapPairNotRepaired(
      LegacyBootstrapFixture fixture, long expectedMigrationRequestEventCount) {
    migrateToVersion(fixture.dataSource(), "8");
    Record demo = pointerRecordForTenant(fixture.dsl(), RETAINED_BOOTSTRAP_TENANT_ID, "demo");
    Record sandbox = pointerRecordForTenant(fixture.dsl(), RETAINED_BOOTSTRAP_TENANT_ID, "sandbox");
    assertThat(demo.get("public_production_realm", Boolean.class)).isTrue();
    assertThat(demo.get("pointer_version", Long.class)).isEqualTo(1L);
    assertThat(demo.get("catalog_revision", Long.class)).isEqualTo(1L);
    assertThat(sandbox.get("public_production_realm", Boolean.class)).isTrue();
    assertThat(sandbox.get("pointer_version", Long.class)).isEqualTo(1L);
    assertThat(sandbox.get("catalog_revision", Long.class)).isEqualTo(1L);
    assertThat(
            fetchTypedValue(
                fixture.dsl(),
                "SELECT COUNT(*) FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ?",
                Long.class,
                migrationRequestId(RETAINED_BOOTSTRAP_TENANT_ID)))
        .isEqualTo(expectedMigrationRequestEventCount);
    assertThat(
            fetchTypedValue(
                fixture.dsl(),
                "SELECT COUNT(*) FROM gameplay_admission_pointer_event "
                    + "WHERE control_plane_request_id = ? AND actor_principal = ?",
                Long.class,
                migrationRequestId(RETAINED_BOOTSTRAP_TENANT_ID),
                MIGRATION_ACTOR))
        .isEqualTo(0L);
  }

  private static DriverManagerDataSource isolatedDataSource() {
    String schemaName = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
    DSL.using(dataSource(), SQLDialect.POSTGRES).execute("CREATE SCHEMA " + schemaName);

    DriverManagerDataSource isolated = dataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    isolated.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schemaName + ",public");
    return isolated;
  }

  private static Flyway migrateToVersion(DriverManagerDataSource dataSource, String version) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .target(MigrationVersion.fromVersion(version))
            .load();
    flyway.migrate();
    return flyway;
  }

  private static void insertLegacyBootstrapPointer(
      DSLContext dsl,
      long id,
      String worldSlug,
      String worldDisplayName,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer ("
            + "id, world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, visible, requires_character_selection, "
            + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
            + "public_production_realm) VALUES (?, ?, ?, 'production', 'Live Realm', ?, ?, 1, "
            + "true, ?, 'SHARED', 'ALLOW_NEW', ?, ?, true)",
        id,
        worldSlug,
        worldDisplayName,
        RETAINED_BOOTSTRAP_TENANT_ID,
        gameInstanceId,
        requiresCharacterSelection,
        LEGACY_BOOTSTRAP_ACTOR,
        LEGACY_BOOTSTRAP_REASON);
  }

  private static void insertLegacyBootstrapCreationEvent(
      DSLContext dsl,
      long eventId,
      String worldSlug,
      String worldDisplayName,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer_event ("
            + "id, world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, visible, requires_character_selection, "
            + "state_scope, character_creation_policy, actor_principal, reason, "
            + "control_plane_request_id, occurred_at, prepared_version_upgrade_id, "
            + "public_production_realm) VALUES (?, ?, 'production', ?, 'Live Realm', ?, ?, 1, "
            + "true, ?, 'SHARED', 'ALLOW_NEW', ?, ?, ?, CURRENT_TIMESTAMP, NULL, true)",
        eventId,
        worldSlug,
        worldDisplayName,
        RETAINED_BOOTSTRAP_TENANT_ID,
        gameInstanceId,
        requiresCharacterSelection,
        LEGACY_BOOTSTRAP_ACTOR,
        LEGACY_BOOTSTRAP_REASON,
        bootstrapRequestId(RETAINED_BOOTSTRAP_TENANT_ID, gameInstanceId, worldSlug));
  }

  private static void insertConflictingMigrationEvent(DSLContext dsl, long tenantId) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer_event ("
            + "id, world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, catalog_revision, realm_id, "
            + "playable_state_namespace_id, visible, requires_character_selection, state_scope, "
            + "character_creation_policy, actor_principal, reason, control_plane_request_id, "
            + "occurred_at, public_production_realm) "
            + "SELECT 8200, sandbox.world_slug, sandbox.realm_slug, sandbox.world_display_name, "
            + "sandbox.realm_display_name, sandbox.tenant_id, sandbox.game_instance_id, "
            + "sandbox.pointer_version, sandbox.catalog_revision, sandbox.realm_id, "
            + "sandbox.playable_state_namespace_id, sandbox.visible, "
            + "sandbox.requires_character_selection, sandbox.state_scope, "
            + "sandbox.character_creation_policy, 'operator/conflict', 'conflicting request', ?, "
            + "CURRENT_TIMESTAMP, sandbox.public_production_realm "
            + "FROM gameplay_admission_pointer sandbox WHERE sandbox.tenant_id = ? "
            + "AND sandbox.world_slug = 'sandbox'",
        migrationRequestId(tenantId),
        tenantId);
  }

  private static String bootstrapRequestId(long tenantId, long gameInstanceId, String worldSlug) {
    return "bootstrap:" + tenantId + ":" + gameInstanceId + ":" + worldSlug + ":production";
  }

  private static String migrationRequestId(long tenantId) {
    return "migration:V8:repair-legacy-bootstrap-public-realm:" + tenantId;
  }

  private static <T> T fetchTypedValue(
      DSLContext dsl, String query, Class<T> type, Object... bindings) {
    Record row = dsl.fetchOne(query, bindings);
    return row == null ? null : row.get(0, type);
  }

  private static Record pointerRecordForTenant(DSLContext dsl, long tenantId, String worldSlug) {
    Record pointer =
        dsl.fetchOne(
            "SELECT * FROM gameplay_admission_pointer "
                + "WHERE tenant_id = ? AND world_slug = ? AND realm_slug = 'production'",
            tenantId,
            worldSlug);
    if (pointer == null) {
      throw new IllegalStateException(
          "Expected bootstrap pointer row to exist: tenant=" + tenantId + ", world=" + worldSlug);
    }
    return pointer;
  }

  private record LegacyBootstrapFixture(DriverManagerDataSource dataSource, DSLContext dsl) {}

  @Test
  void migrationsV1ThroughV7BackfillPointerIdentityAndKeepHistoricalAuditUnknown() {
    DriverManagerDataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("5"))
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    insertPointer(dsl, 1L, 10L, "demo", "live", 101L, true, true, "SHARED");
    insertPointer(dsl, 2L, 10L, "demo", "private", 102L, false, true, "SHARED");
    insertPointer(dsl, 3L, 10L, "lab", "playtest", 103L, true, false, "ISOLATED");
    insertPointer(dsl, 4L, 11L, "demo", "live", 104L, true, true, "SHARED");
    insertPointer(dsl, 5L, 12L, "legacy", "unresolved", 105L, false, false, "UNKNOWN");

    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .target(MigrationVersion.fromVersion("6"))
            .load();
    flyway.migrate();

    insertPointerEvent(dsl, 9001L, 1L, 101L);

    assertThat(
            Arrays.stream(flyway.info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .toList())
        .contains("1", "2", "3", "4", "5", "6");

    UUID sharedRealmId = realmId(dsl, 1L);
    UUID sharedNamespaceId = namespaceId(dsl, 1L);
    assertThat(sharedRealmId).isNotNull();
    assertThat(namespaceId(dsl, 2L)).isEqualTo(sharedNamespaceId);
    assertThat(realmId(dsl, 2L)).isNotEqualTo(sharedRealmId);
    assertThat(namespaceId(dsl, 3L)).isNotEqualTo(sharedNamespaceId);
    assertThat(namespaceId(dsl, 4L)).isNotEqualTo(sharedNamespaceId);
    assertThat(realmId(dsl, 5L)).isNull();
    assertThat(namespaceId(dsl, 5L)).isNull();
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT issue_code FROM gameplay_admission_pointer_identity_backfill_issue "
                    + "WHERE pointer_id = 5",
                String.class))
        .isEqualTo("UNKNOWN_PLAYABLE_STATE_SCOPE");
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT catalog_revision FROM gameplay_admission_pointer WHERE id = 1",
                Long.class))
        .isEqualTo(1L);

    dsl.execute(
        "UPDATE gameplay_admission_pointer SET game_instance_id = 111, pointer_version = 5 "
            + "WHERE id = 1");
    assertThat(realmId(dsl, 1L)).isEqualTo(sharedRealmId);
    assertThat(namespaceId(dsl, 1L)).isEqualTo(sharedNamespaceId);
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT catalog_revision FROM gameplay_admission_pointer WHERE id = 1",
                Long.class))
        .isEqualTo(1L);

    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(worldViewsFromDatabase(dsl));
    assertThat(catalog.publicProductionRealmCardinality(10L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
    assertThat(catalog.publicProductionRealmCardinality(11L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
    assertThat(catalog.publicProductionRealmCardinality(12L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.ZERO);

    assertThatThrownBy(
            () -> insertPointer(dsl, 6L, 10L, "demo", "live", 106L, true, false, "SHARED"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_gameplay_admission_pointer_tenant_world_realm");
    assertThatThrownBy(
            () -> insertPointer(dsl, 6L, 10L, "demo", "alternate", 111L, true, false, "SHARED"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_gameplay_admission_pointer_runtime_target");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE gameplay_admission_pointer SET realm_id = ? WHERE id = 3",
                    sharedRealmId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_gameplay_admission_pointer_realm_id");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE gameplay_admission_pointer SET playable_state_namespace_id = NULL "
                        + "WHERE id = 1"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("gameplay_admission_pointer_identity_pair_complete");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE gameplay_admission_pointer SET catalog_revision = 0 WHERE id = 1"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("gameplay_admission_pointer_catalog_revision_positive");

    Flyway auditFlyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .target(MigrationVersion.fromVersion("7"))
            .load();
    auditFlyway.migrate();

    assertThat(
            Arrays.stream(auditFlyway.info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .toList())
        .contains("7");
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT catalog_revision FROM gameplay_admission_pointer_event WHERE id = 9001",
                Long.class))
        .isNull();
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT realm_id FROM gameplay_admission_pointer_event WHERE id = 9001",
                UUID.class))
        .isNull();
    assertThat(
            fetchTypedValue(
                dsl,
                "SELECT playable_state_namespace_id FROM gameplay_admission_pointer_event "
                    + "WHERE id = 9001",
                UUID.class))
        .isNull();

    UUID eventRealmId = realmId(dsl, 1L);
    UUID eventNamespaceId = namespaceId(dsl, 1L);
    insertBoundPointerEvent(dsl, 9002L, 1L, 101L, 1L, eventRealmId, eventNamespaceId);
    Record boundEvent =
        dsl.fetchOne("SELECT * FROM gameplay_admission_pointer_event WHERE id = 9002");
    assertThat(boundEvent.get("catalog_revision", Long.class)).isEqualTo(1L);
    assertThat(boundEvent.get("realm_id", UUID.class)).isEqualTo(eventRealmId);
    assertThat(boundEvent.get("playable_state_namespace_id", UUID.class))
        .isEqualTo(eventNamespaceId);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE gameplay_admission_pointer_event SET catalog_revision = 0 "
                        + "WHERE id = 9002"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("gameplay_admission_pointer_event_catalog_revision_positive");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE gameplay_admission_pointer_event SET playable_state_namespace_id = NULL "
                        + "WHERE id = 9002"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("gameplay_admission_pointer_event_identity_pair_complete");
  }

  private static void insertPointerEvent(
      DSLContext dsl, long eventId, long tenantId, long gameInstanceId) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer_event ("
            + "id, world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, visible, requires_character_selection, "
            + "state_scope, character_creation_policy, actor_principal, reason, "
            + "control_plane_request_id, occurred_at, public_production_realm) "
            + "VALUES (?, 'demo', 'live', 'Demo World', 'Live Realm', ?, ?, 1, true, false, "
            + "'SHARED', 'ALLOW_NEW', 'migration-test', 'retained history', 'event-9001', "
            + "CURRENT_TIMESTAMP, true)",
        eventId,
        tenantId,
        gameInstanceId);
  }

  private static void insertBoundPointerEvent(
      DSLContext dsl,
      long eventId,
      long tenantId,
      long gameInstanceId,
      long catalogRevision,
      UUID realmId,
      UUID namespaceId) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer_event ("
            + "id, world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, catalog_revision, realm_id, "
            + "playable_state_namespace_id, visible, requires_character_selection, state_scope, "
            + "character_creation_policy, actor_principal, reason, control_plane_request_id, "
            + "occurred_at, public_production_realm) "
            + "VALUES (?, 'demo', 'live', 'Demo World', 'Live Realm', ?, ?, 1, ?, ?, ?, true, "
            + "false, 'SHARED', 'ALLOW_NEW', 'migration-test', 'bound event', 'event-9002', "
            + "CURRENT_TIMESTAMP, true)",
        eventId,
        tenantId,
        gameInstanceId,
        catalogRevision,
        realmId,
        namespaceId);
  }

  private static void insertPointer(
      DSLContext dsl,
      long id,
      long tenantId,
      String worldSlug,
      String realmSlug,
      long gameInstanceId,
      boolean visible,
      boolean publicProduction,
      String stateScope) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer ("
            + "id, world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, visible, requires_character_selection, "
            + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
            + "public_production_realm) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, false, ?, "
            + "'ALLOW_NEW', 'migration-test', 'migration fixture', ?)",
        id,
        worldSlug,
        worldSlug + " World",
        realmSlug,
        realmSlug + " Realm",
        tenantId,
        gameInstanceId,
        visible,
        stateScope,
        publicProduction);
  }

  private static UUID realmId(DSLContext dsl, long pointerId) {
    return pointerRecord(dsl, pointerId).get("realm_id", UUID.class);
  }

  private static UUID namespaceId(DSLContext dsl, long pointerId) {
    return pointerRecord(dsl, pointerId).get("playable_state_namespace_id", UUID.class);
  }

  private static Record pointerRecord(DSLContext dsl, long pointerId) {
    Record pointer =
        dsl.fetchOne("SELECT * FROM gameplay_admission_pointer WHERE id = ?", pointerId);
    if (pointer == null) {
      throw new IllegalStateException(
          "Expected gameplay admission pointer row to exist: id=" + pointerId);
    }
    return pointer;
  }

  private static List<GameplayWorldCatalog.WorldView> worldViewsFromDatabase(DSLContext dsl) {
    Map<String, String> displayNamesByWorld = new LinkedHashMap<>();
    Map<String, List<GameplayWorldCatalog.RealmView>> realmsByWorld = new LinkedHashMap<>();
    for (Record row :
        dsl.fetch(
            "SELECT world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
                + "game_instance_id, pointer_version, visible, public_production_realm, "
                + "requires_character_selection, state_scope, character_creation_policy, "
                + "catalog_revision, realm_id, playable_state_namespace_id "
                + "FROM gameplay_admission_pointer ORDER BY id")) {
      String worldSlug = row.get("world_slug", String.class);
      displayNamesByWorld.putIfAbsent(worldSlug, row.get("world_display_name", String.class));
      realmsByWorld
          .computeIfAbsent(worldSlug, ignored -> new ArrayList<>())
          .add(
              new GameplayWorldCatalog.RealmView(
                  row.get("realm_slug", String.class),
                  row.get("realm_display_name", String.class),
                  row.get("tenant_id", Long.class),
                  row.get("game_instance_id", Long.class),
                  row.get("pointer_version", Long.class),
                  row.get("visible", Boolean.class),
                  row.get("public_production_realm", Boolean.class),
                  row.get("requires_character_selection", Boolean.class),
                  row.get("state_scope", String.class),
                  row.get("character_creation_policy", String.class),
                  row.get("catalog_revision", Long.class),
                  row.get("realm_id", UUID.class),
                  row.get("playable_state_namespace_id", UUID.class)));
    }
    return realmsByWorld.entrySet().stream()
        .map(
            entry ->
                new GameplayWorldCatalog.WorldView(
                    entry.getKey(), displayNamesByWorld.get(entry.getKey()), entry.getValue()))
        .toList();
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }
}
