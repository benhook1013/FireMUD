package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.service.impl.DatabaseGameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
class GameplayAdmissionPointerCardinalityIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + PathHolder.MIGRATIONS.toAbsolutePath().normalize();
  private static final String INDEX_NAME =
      "uq_gameplay_admission_pointer_tenant_visible_public_production";
  private static final long ZERO_PUBLIC_TENANT_ID = 51001L;
  private static final long SINGLE_PUBLIC_TENANT_ID = 51002L;
  private static final long MULTIPLE_PUBLIC_TENANT_ID = 51003L;
  private static final long OTHER_TENANT_ID = 51004L;
  private static final long SINGLE_PUBLIC_ID = 5100201L;
  private static final long VISIBLE_NONPUBLIC_ID = 5100203L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String isolatedSchemaName;

  @AfterEach
  void dropIsolatedSchema() {
    if (isolatedSchemaName != null) {
      DSL.using(dataSource(), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS " + isolatedSchemaName + " CASCADE");
    }
  }

  @Test
  void forwardIndexPreservesRetainedRowsAndEnforcesVisiblePublicCardinality() {
    DriverManagerDataSource dataSource = isolatedDataSource();
    seedRetainedPointerHistory(dataSource, false);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    List<String> retainedPointers = retainedRowsAndXmins(dsl, "gameplay_admission_pointer");
    List<String> retainedEvents = retainedRowsAndXmins(dsl, "gameplay_admission_pointer_event");

    migrateToVersion(dataSource, "1053");

    assertThat(indexExists(dsl)).isTrue();
    assertThat(retainedRowsAndXmins(dsl, "gameplay_admission_pointer")).isEqualTo(retainedPointers);
    assertThat(retainedRowsAndXmins(dsl, "gameplay_admission_pointer_event"))
        .isEqualTo(retainedEvents);
    assertThat(visiblePublicCount(dsl, ZERO_PUBLIC_TENANT_ID)).isZero();
    assertThat(visiblePublicCount(dsl, SINGLE_PUBLIC_TENANT_ID)).isEqualTo(1L);
    assertThat(visiblePublicCount(dsl, OTHER_TENANT_ID)).isEqualTo(1L);
    assertThat(
            scalarLong(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer WHERE tenant_id = ? "
                    + "AND visible = false AND public_production_realm = true",
                SINGLE_PUBLIC_TENANT_ID))
        .isEqualTo(1L);
    assertThat(
            scalarLong(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer WHERE tenant_id = ? "
                    + "AND visible = true AND public_production_realm = false",
                SINGLE_PUBLIC_TENANT_ID))
        .isEqualTo(1L);
    assertThat(
            scalarLong(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer WHERE world_slug = 'one-main' "
                    + "AND realm_slug = 'production' AND visible = true "
                    + "AND public_production_realm = true"))
        .isEqualTo(2L);

    assertUniqueIndexViolation(
        () ->
            dsl.execute(
                "INSERT INTO gameplay_admission_pointer (world_slug, world_display_name, "
                    + "realm_slug, realm_display_name, tenant_id, game_instance_id, "
                    + "pointer_version, visible, requires_character_selection, state_scope, "
                    + "character_creation_policy, last_updated_by, last_update_reason, "
                    + "public_production_realm) VALUES ('write-attempt', 'Write Attempt', "
                    + "'production', 'Production', ?, 9510201, 1, true, false, 'SHARED', "
                    + "'ALLOW_NEW', 'test/cardinality', 'second public insert', true)",
                SINGLE_PUBLIC_TENANT_ID));
    assertThat(
            scalarLong(
                dsl,
                "SELECT COUNT(*) FROM gameplay_admission_pointer WHERE tenant_id = ? "
                    + "AND world_slug = 'write-attempt'",
                SINGLE_PUBLIC_TENANT_ID))
        .isZero();

    String nonpublicBefore = rowAndXmin(dsl, VISIBLE_NONPUBLIC_ID);
    assertUniqueIndexViolation(
        () ->
            dsl.execute(
                "UPDATE gameplay_admission_pointer SET public_production_realm = true "
                    + "WHERE id = ?",
                VISIBLE_NONPUBLIC_ID));
    assertThat(rowAndXmin(dsl, VISIBLE_NONPUBLIC_ID)).isEqualTo(nonpublicBefore);
    assertThat(visiblePublicCount(dsl, SINGLE_PUBLIC_TENANT_ID)).isEqualTo(1L);

    List<String> historyBeforeRerun = flywayHistory(dsl);
    migrateToVersion(dataSource, "1053");
    assertThat(flywayHistory(dsl)).isEqualTo(historyBeforeRerun);

    DSLContext recreatedReaderDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DatabaseGameplayAdmissionPointerAuthorityService recreatedReader =
        new DatabaseGameplayAdmissionPointerAuthorityService(
            new GameplayAdmissionPointerRepository(recreatedReaderDsl),
            new GameplayAdmissionPointerEventRepository(recreatedReaderDsl));
    assertThat(recreatedReader.findPointer(SINGLE_PUBLIC_TENANT_ID, "one-main", "production"))
        .isPresent();
  }

  @Test
  void duplicateRetainedRowsRefuseMigrationWithoutChangingDataOrHistory() {
    DriverManagerDataSource dataSource = isolatedDataSource();
    seedRetainedPointerHistory(dataSource, true);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    List<String> retainedPointers = retainedRowsAndXmins(dsl, "gameplay_admission_pointer");
    List<String> retainedEvents = retainedRowsAndXmins(dsl, "gameplay_admission_pointer_event");
    List<String> migrationHistory = flywayHistory(dsl);

    assertThat(visiblePublicCount(dsl, ZERO_PUBLIC_TENANT_ID)).isZero();
    assertThat(visiblePublicCount(dsl, SINGLE_PUBLIC_TENANT_ID)).isEqualTo(1L);
    assertThat(visiblePublicCount(dsl, MULTIPLE_PUBLIC_TENANT_ID)).isEqualTo(2L);
    assertThat(indexExists(dsl)).isFalse();

    assertThatThrownBy(() -> migrateToVersion(dataSource, "1053"))
        .satisfies(failure -> assertThat(rootCause(failure).getMessage()).contains(INDEX_NAME));

    assertThat(retainedRowsAndXmins(dsl, "gameplay_admission_pointer")).isEqualTo(retainedPointers);
    assertThat(retainedRowsAndXmins(dsl, "gameplay_admission_pointer_event"))
        .isEqualTo(retainedEvents);
    assertThat(flywayHistory(dsl)).isEqualTo(migrationHistory);
    assertThat(indexExists(dsl)).isFalse();
  }

  private static void seedRetainedPointerHistory(
      DriverManagerDataSource dataSource, boolean includeDuplicatePublicRows) {
    migrateToVersion(dataSource, "5");
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    List<RetainedPointer> pointers =
        List.of(
            new RetainedPointer(5100101L, ZERO_PUBLIC_TENANT_ID, "zero-main", true, false),
            new RetainedPointer(5100102L, ZERO_PUBLIC_TENANT_ID, "zero-hidden", false, true),
            new RetainedPointer(5100201L, SINGLE_PUBLIC_TENANT_ID, "one-main", true, true),
            new RetainedPointer(5100202L, SINGLE_PUBLIC_TENANT_ID, "one-hidden", false, true),
            new RetainedPointer(5100203L, SINGLE_PUBLIC_TENANT_ID, "one-preview", true, false),
            new RetainedPointer(5100401L, OTHER_TENANT_ID, "one-main", true, true));
    pointers.forEach(pointer -> insertRetainedPointer(dsl, pointer));
    if (includeDuplicatePublicRows) {
      insertRetainedPointer(
          dsl, new RetainedPointer(5100301L, MULTIPLE_PUBLIC_TENANT_ID, "multi-one", true, true));
      insertRetainedPointer(
          dsl, new RetainedPointer(5100302L, MULTIPLE_PUBLIC_TENANT_ID, "multi-two", true, true));
    }

    migrateToVersion(dataSource, "6");
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer_event (world_slug, realm_slug, "
            + "world_display_name, realm_display_name, tenant_id, game_instance_id, "
            + "pointer_version, visible, requires_character_selection, state_scope, "
            + "character_creation_policy, actor_principal, reason, control_plane_request_id, "
            + "occurred_at, prepared_version_upgrade_id, public_production_realm) "
            + "SELECT pointer.world_slug, pointer.realm_slug, pointer.world_display_name, "
            + "pointer.realm_display_name, pointer.tenant_id, pointer.game_instance_id, "
            + "pointer.pointer_version, pointer.visible, pointer.requires_character_selection, "
            + "pointer.state_scope, pointer.character_creation_policy, 'retained/test', "
            + "'Pre-cardinality retained audit event', 'retained-cardinality:' || pointer.id, "
            + "CURRENT_TIMESTAMP, NULL, pointer.public_production_realm "
            + "FROM gameplay_admission_pointer pointer");
    migrateToVersion(dataSource, "7");
    migrateToVersion(dataSource, "8");
    migrateToVersion(dataSource, "1052");
  }

  private static void insertRetainedPointer(DSLContext dsl, RetainedPointer pointer) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer (id, world_slug, world_display_name, "
            + "realm_slug, realm_display_name, tenant_id, game_instance_id, pointer_version, "
            + "visible, requires_character_selection, state_scope, character_creation_policy, "
            + "last_updated_by, last_update_reason, public_production_realm) "
            + "VALUES (?, ?, ?, 'production', 'Production', ?, ?, 1, ?, false, 'SHARED', "
            + "'ALLOW_NEW', 'retained/test', 'Pre-cardinality retained pointer', ?)",
        pointer.id(),
        pointer.worldSlug(),
        "World " + pointer.worldSlug(),
        pointer.tenantId(),
        pointer.id(),
        pointer.visible(),
        pointer.publicProduction());
  }

  private DriverManagerDataSource isolatedDataSource() {
    isolatedSchemaName = "pointer_cardinality_" + UUID.randomUUID().toString().replace("-", "");
    DSL.using(dataSource(), SQLDialect.POSTGRES).execute("CREATE SCHEMA " + isolatedSchemaName);

    DriverManagerDataSource isolated = dataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    isolated.setUrl(
        postgres.getJdbcUrl() + separator + "currentSchema=" + isolatedSchemaName + ",public");
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

  private static List<String> retainedRowsAndXmins(DSLContext dsl, String tableName) {
    return dsl.fetch(
            "SELECT id::text || ':' || xmin::text || ':' || "
                + "encode(convert_to(to_jsonb(retained_row)::text, 'UTF8'), 'hex') "
                + "FROM "
                + tableName
                + " AS retained_row ORDER BY id")
        .getValues(0, String.class);
  }

  private static String rowAndXmin(DSLContext dsl, long pointerId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text || ':' || "
                    + "encode(convert_to(to_jsonb(pointer)::text, 'UTF8'), 'hex') "
                    + "FROM gameplay_admission_pointer AS pointer WHERE id = ?",
                pointerId),
            "Expected the retained admission pointer row")
        .get(0, String.class);
  }

  private static List<String> flywayHistory(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text FROM flyway_schema_history AS history_row "
                + "ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static boolean indexExists(DSLContext dsl) {
    return scalarLong(
            dsl,
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND indexname = ?",
            INDEX_NAME)
        == 1L;
  }

  private static long visiblePublicCount(DSLContext dsl, long tenantId) {
    return scalarLong(
        dsl,
        "SELECT COUNT(*) FROM gameplay_admission_pointer WHERE tenant_id = ? "
            + "AND visible = true AND public_production_realm = true",
        tenantId);
  }

  private static long scalarLong(DSLContext dsl, String query, Object... bindings) {
    return Objects.requireNonNull(
        Objects.requireNonNull(dsl.fetchOne(query, bindings), "Expected one scalar query row")
            .get(0, Long.class),
        "Expected a non-null scalar query value");
  }

  private static void assertUniqueIndexViolation(Runnable statement) {
    assertThatThrownBy(statement::run)
        .satisfies(failure -> assertThat(rootCause(failure).getMessage()).contains(INDEX_NAME));
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private record RetainedPointer(
      long id, long tenantId, String worldSlug, boolean visible, boolean publicProduction) {}

  private static final class PathHolder {
    private static final java.nio.file.Path MIGRATIONS =
        java.nio.file.Path.of("src/main/resources/db/migration");
  }
}
