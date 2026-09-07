package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.Scripts.SCRIPTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptDefinitionRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private ScriptDefinitionRepository repository;
  private String schema;

  @BeforeAll
  void migrateSchema() {
    schema = "script_definition_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new ScriptDefinitionRepository(dsl);
  }

  @BeforeEach
  void cleanScripts() {
    dsl.execute("TRUNCATE TABLE scripts RESTART IDENTITY");
  }

  @AfterAll
  void dropSchema() {
    if (schema != null) {
      adminDsl().execute("DROP SCHEMA " + schema + " CASCADE");
      schema = null;
    }
  }

  @Test
  void concurrentIdenticalFirstWritesReturnOneDurableIdentity() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<Future<ScriptDefinition>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  return new ScriptDefinitionRepository(dsl).save(script("{\"value\":1}"));
                }));
      }

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      ScriptDefinition first = get(futures.get(0));
      ScriptDefinition second = get(futures.get(1));

      assertThat(first.getId()).isNotNull();
      assertThat(second.getId()).isEqualTo(first.getId());
      assertThat(first.getRowVersion()).isZero();
      assertThat(second.getRowVersion()).isZero();
      assertThat(repository.findById(first.getId())).contains(first);
      assertThat(dsl.fetchCount(SCRIPTS)).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void identicalRetryReturnsSameRowWithoutRowVersionChurn() {
    ScriptDefinition initial = repository.save(script("{\"value\":1}"));

    ScriptDefinition retry = repository.save(script("{\"value\":1}"));

    assertThat(retry.getId()).isEqualTo(initial.getId());
    assertThat(retry.getRowVersion()).isEqualTo(initial.getRowVersion()).isZero();
    assertThat(dsl.fetchValue(SCRIPTS.ROW_VERSION, SCRIPTS.ID.eq(initial.getId()))).isZero();
    assertThat(dsl.fetchCount(SCRIPTS)).isEqualTo(1);
  }

  @Test
  void changedContentReplacesSameRowAndIncrementsRowVersionExactlyOnce() {
    ScriptDefinition initial = repository.save(script("{\"value\":1}"));

    ScriptDefinition replacement = repository.save(script("{\"value\":2}"));
    ScriptDefinition replacementRetry = repository.save(script("{\"value\":2}"));

    assertThat(replacement.getId()).isEqualTo(initial.getId());
    assertThat(replacement.getRowVersion()).isEqualTo(1);
    assertThat(replacementRetry.getId()).isEqualTo(initial.getId());
    assertThat(replacementRetry.getRowVersion()).isEqualTo(1);
    assertThat(
            dsl.select(SCRIPTS.DEFINITION, SCRIPTS.ROW_VERSION)
                .from(SCRIPTS)
                .where(SCRIPTS.ID.eq(initial.getId()))
                .fetchOne())
        .extracting(
            record -> record.get(SCRIPTS.DEFINITION), record -> record.get(SCRIPTS.ROW_VERSION))
        .containsExactly("{\"value\":2}", 1);
    assertThat(dsl.fetchCount(SCRIPTS)).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent script-definition test did not start");
    }
  }

  private static ScriptDefinition script(String definition) {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("stable-script");
    script.setScriptVersion("v1");
    script.setDefinition(definition);
    return script;
  }

  private static ScriptDefinition get(Future<ScriptDefinition> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    return future.get(10, TimeUnit.SECONDS);
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
}
