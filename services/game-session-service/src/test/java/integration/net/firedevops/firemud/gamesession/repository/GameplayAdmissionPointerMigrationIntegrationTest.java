package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migrationsV1ThroughV6BackfillStableRealmIdentityAndKeepAdmissionConstraints() {
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
            dsl.fetchValue(
                "SELECT issue_code FROM gameplay_admission_pointer_identity_backfill_issue "
                    + "WHERE pointer_id = 5",
                String.class))
        .isEqualTo("UNKNOWN_PLAYABLE_STATE_SCOPE");
    assertThat(
            dsl.fetchValue(
                "SELECT catalog_revision FROM gameplay_admission_pointer WHERE id = 1", Long.class))
        .isEqualTo(1L);

    dsl.execute(
        "UPDATE gameplay_admission_pointer SET game_instance_id = 111, pointer_version = 5 "
            + "WHERE id = 1");
    assertThat(realmId(dsl, 1L)).isEqualTo(sharedRealmId);
    assertThat(namespaceId(dsl, 1L)).isEqualTo(sharedNamespaceId);
    assertThat(
            dsl.fetchValue(
                "SELECT catalog_revision FROM gameplay_admission_pointer WHERE id = 1", Long.class))
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
    return dsl.fetchOne("SELECT realm_id FROM gameplay_admission_pointer WHERE id = ?", pointerId)
        .get("realm_id", UUID.class);
  }

  private static UUID namespaceId(DSLContext dsl, long pointerId) {
    return dsl.fetchOne(
            "SELECT playable_state_namespace_id FROM gameplay_admission_pointer WHERE id = ?",
            pointerId)
        .get("playable_state_namespace_id", UUID.class);
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
