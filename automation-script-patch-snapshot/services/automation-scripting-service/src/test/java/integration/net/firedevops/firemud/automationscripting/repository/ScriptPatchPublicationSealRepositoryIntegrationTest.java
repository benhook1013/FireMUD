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
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.automationscripting.model.ScriptPatchAlreadySealedException;
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
class ScriptPatchPublicationSealRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private String schema;

  @BeforeAll
  void migrateSchema() {
    schema = "script_patch_seal_" + UUID.randomUUID().toString().replace("-", "");
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

  @BeforeEach
  void cleanSeals() {
    dsl.execute("TRUNCATE TABLE script_patch_publication_seals");
  }

  @AfterAll
  void dropSchema() {
    if (schema != null) {
      adminDsl().execute("DROP SCHEMA " + schema + " CASCADE");
      schema = null;
    }
  }

  @Test
  void identicalResealIsIdempotentButChangedSnapshotOrBaseBindingFailsClosed() {
    var first =
        inTransaction(
            transactionDsl ->
                seal(transactionDsl, "base-7", "1a".repeat(32), "{\"definitions\":[]}"));
    var retry =
        inTransaction(
            transactionDsl ->
                seal(transactionDsl, "base-7", "1a".repeat(32), "{\"definitions\":[]}"));

    assertThat(retry).isEqualTo(first);
    assertThatThrownBy(
            () ->
                inTransaction(
                    transactionDsl ->
                        seal(
                            transactionDsl,
                            "base-8",
                            "1a".repeat(32),
                            "{\"definitions\":[]}")))
        .isInstanceOf(ScriptPatchAlreadySealedException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    transactionDsl ->
                        seal(
                            transactionDsl,
                            "base-7",
                            "2b".repeat(32),
                            "{\"definitions\":[1]}")))
        .isInstanceOf(ScriptPatchAlreadySealedException.class);
    assertThat(dsl.fetchValue("SELECT count(*) FROM script_patch_publication_seals WHERE is_sealed"))
        .isEqualTo(1L);
  }

  @Test
  void rowLockSerializesMutationAndSealTransactionsForOnePatch() throws Exception {
    CountDownLatch updateHasLock = new CountDownLatch(1);
    CountDownLatch releaseUpdate = new CountDownLatch(1);
    CountDownLatch sealHasLock = new CountDownLatch(1);
    AtomicBoolean sealCompleted = new AtomicBoolean();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> update =
          executor.submit(
              () ->
                  inTransaction(
                      transactionDsl -> {
                        new ScriptPatchPublicationSealRepository(transactionDsl)
                            .lockForUpdate(23L, "patch-7");
                        updateHasLock.countDown();
                        await(releaseUpdate);
                        transactionDsl.execute(
                            "INSERT INTO scripts (tenant_id, name, version, definition) "
                                + "VALUES (23, 'alpha', 'patch-7', '{\"v\":1}')");
                        return null;
                      }));
      assertThat(updateHasLock.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> seal =
          executor.submit(
              () ->
                  inTransaction(
                      transactionDsl -> {
                        var repository = new ScriptPatchPublicationSealRepository(transactionDsl);
                        repository.seal(
                            23L,
                            "base-7",
                            "patch-7",
                            1,
                            4,
                            "1a".repeat(32),
                            "{\"definitions\":[{\"name\":\"alpha\"}]}");
                        sealHasLock.countDown();
                        sealCompleted.set(true);
                        return null;
                      }));

      assertThat(sealHasLock.await(250, TimeUnit.MILLISECONDS)).isFalse();
      releaseUpdate.countDown();
      update.get(10, TimeUnit.SECONDS);
      seal.get(10, TimeUnit.SECONDS);

      assertThat(sealCompleted).isTrue();
      assertThat(dsl.fetchCount(DSL.table("scripts"))).isEqualTo(1);
      assertThat(dsl.fetchValue(
              "SELECT snapshot_json FROM script_patch_publication_seals "
                  + "WHERE tenant_id = 23 AND script_patch_version = 'patch-7'"))
          .isEqualTo("{\"definitions\":[{\"name\":\"alpha\"}]}");
    } finally {
      releaseUpdate.countDown();
      executor.shutdownNow();
    }
  }

  private static net.firedevops.firemud.automationscripting.model.ScriptPatchPublicationSeal seal(
      DSLContext transactionDsl, String baseVersionId, String digest, String snapshot) {
    return new ScriptPatchPublicationSealRepository(transactionDsl)
        .seal(23L, baseVersionId, "patch-7", 1, 4, digest, snapshot);
  }

  private <T> T inTransaction(java.util.function.Function<DSLContext, T> action) {
    return dsl.transactionResult(configuration -> action.apply(DSL.using(configuration)));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("patch update transaction was not released");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("patch transaction wait was interrupted", ex);
    }
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl() + "?currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static DSLContext adminDsl() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return DSL.using(dataSource, SQLDialect.POSTGRES);
  }
}
