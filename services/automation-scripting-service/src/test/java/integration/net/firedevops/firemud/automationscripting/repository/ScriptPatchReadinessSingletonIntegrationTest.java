package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.service.impl.ScriptPatchReadinessProjectionServiceImpl;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class ScriptPatchReadinessSingletonIntegrationTest {
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
  void concurrentPublishesLeaveOneActiveCandidate() throws Exception {
    DSLContext dsl = migrateToLatest();
    DriverManagerDataSource rawDataSource = dataSource(schema);
    TransactionAwareDataSourceProxy transactionAwareDataSource =
        new TransactionAwareDataSourceProxy(rawDataSource);
    DSLContext transactionalDsl = DSL.using(transactionAwareDataSource, SQLDialect.POSTGRES);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(rawDataSource));

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          executor.submit(
              () -> {
                await(start);
                beginInTransaction(transactionalDsl, transactionTemplate, "patch-a");
              });
      Future<?> second =
          executor.submit(
              () -> {
                await(start);
                beginInTransaction(transactionalDsl, transactionTemplate, "patch-b");
              });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(
            dsl.fetch(
                    "select script_patch_version, readiness_status "
                        + "from script_patch_readiness_projections "
                        + "where tenant_id = 'tenant-concurrent' "
                        + "order by script_patch_version")
                .size())
        .isEqualTo(2);
    assertThat(
            dsl.fetch(
                    "select count(*) from script_patch_readiness_projections "
                        + "where tenant_id = 'tenant-concurrent' "
                        + "and readiness_status in ('PENDING_VALIDATION', 'ONLOAD_RUNNING')")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetch(
                    "select count(*) from script_patch_readiness_projections "
                        + "where tenant_id = 'tenant-concurrent' "
                        + "and readiness_status = 'SUPERSEDED'")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void migrationFailsClosedWhenExistingTenantHasMultipleActiveRows() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute(
        "insert into script_patch_readiness_projections "
            + "(tenant_id, script_patch_version, readiness_status, status_reason) "
            + "values ('tenant-duplicate', 'patch-a', 'ONLOAD_RUNNING', 'test'), "
            + "('tenant-duplicate', 'patch-b', 'PENDING_VALIDATION', 'test')");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("uq_script_patch_readiness_active_tenant");
    assertThat(
            dsl.fetch(
                    "select count(*) from script_patch_readiness_projections "
                        + "where tenant_id = 'tenant-duplicate'")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(2L);
  }

  @Test
  void migrationFailsClosedAndPreservesRowsWhenOnLoadClaimIsActive() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute(
        "insert into script_patch_readiness_projections "
            + "(tenant_id, script_patch_version, readiness_status, status_reason) "
            + "values ('tenant-active-onload', 'patch-a', 'ONLOAD_RUNNING', 'test')");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("active work-item and onLoad claims");
    assertThat(
            dsl.fetch(
                    "select count(*) from script_patch_readiness_projections "
                        + "where tenant_id = 'tenant-active-onload' "
                        + "and readiness_status = 'ONLOAD_RUNNING'")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetch(
                    "select count(*) from pg_indexes "
                        + "where schemaname = current_schema() "
                        + "and indexname in (" +
                        "'uq_script_work_item_trigger_identity', " +
                        "'uq_script_work_item_trigger_identity_unpinned', " +
                        "'uq_script_event_audit_handler_identity', " +
                        "'uq_script_event_audit_handler_identity_unpinned')")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(4L);
  }

  @Test
  void migrationFailsClosedWhenLegacyUniqueIndexShapeDrifts() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute("drop index uq_script_work_item_trigger_identity");
    dsl.execute(
        "create unique index uq_script_work_item_trigger_identity "
            + "on script_work_items (tenant_id)");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("four exact V1 producer-agnostic unique indexes");
    String indexDefinition =
        (String)
            dsl.fetchValue(
            "select pg_get_indexdef(indexrelid) from pg_index "
                + "where indexrelid = 'uq_script_work_item_trigger_identity'::regclass",
            String.class);
    assertThat(indexDefinition).contains("(tenant_id)");
    assertThat(
            dsl.fetchValue(
                "select count(*) from pg_indexes where schemaname = current_schema() "
                    + "and indexname = 'uq_script_work_item_trigger_identity_unpinned'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void migrationFailsClosedWhenLegacyUniqueIndexPredicateDrifts() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute("drop index uq_script_work_item_trigger_identity");
    dsl.execute(
        "create unique index uq_script_work_item_trigger_identity on script_work_items "
            + "(tenant_id, game_instance_id, region_id, region_epoch, entity_id, "
            + "playable_state_scope, world_slug, realm_slug, pointer_version, script_id, "
            + "plugin_id, plugin_version_id, binding_id, event_type, event_schema_version, "
            + "script_patch_version, script_pin_epoch, script_event_id, dry_run) "
            + "where script_pin_epoch >= 0");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("four exact V1 producer-agnostic unique indexes");
    String indexPredicate =
        (String)
            dsl.fetchValue(
            "select pg_get_expr(indpred, indrelid) from pg_index "
                + "where indexrelid = 'uq_script_work_item_trigger_identity'::regclass",
            String.class);
    assertThat(indexPredicate).contains(">=");
  }

  private void beginInTransaction(
      DSLContext dsl, TransactionTemplate transactionTemplate, String patchVersion) {
    transactionTemplate.executeWithoutResult(
        status -> {
          ScriptPatchReadinessProjectionRepository repository =
              new ScriptPatchReadinessProjectionRepository(dsl);
          ScriptPatchReadinessProjectionServiceImpl service =
              new ScriptPatchReadinessProjectionServiceImpl(
                  repository, Mockito.mock(ScriptWorkItemRepository.class), dsl);
          service.beginPatchReadiness("tenant-concurrent", patchVersion, 1);
        });
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

  private DSLContext migrateToVersionOne() {
    schema = newSchemaName();
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("1")
        .load()
        .migrate();
    return schemaDsl();
  }

  private void migrateExistingSchemaToLatest() {
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
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
    return "automation_readiness_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static void await(CountDownLatch start) {
    try {
      start.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent readiness test interrupted", ex);
    }
  }
}
