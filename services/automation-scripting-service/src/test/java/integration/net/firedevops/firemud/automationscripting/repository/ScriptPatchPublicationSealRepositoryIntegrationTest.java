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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptPatchPublicationSealRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(net.firedevops.firemud.test.TestContainerImages.postgres());

  private DSLContext dsl;
  private String schema;

  @BeforeEach
  void migrateSchema() {
    schema = "script_patch_seal_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION, "classpath:db/migration/saga")
        .table("flyway_schema_history_automation_scripting_service")
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    dsl.execute(
        "INSERT INTO script_patch_base_bindings(tenant_id,script_patch_version,base_version_id) VALUES ('23','patch-7',7)");
  }

  @AfterEach
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
            transactionDsl -> seal(transactionDsl, 7L, "1a".repeat(32), "{\"definitions\":[]}"));
    var retry =
        inTransaction(
            transactionDsl -> seal(transactionDsl, 7L, "1a".repeat(32), "{\"definitions\":[]}"));

    assertThat(retry).isEqualTo(first);
    assertThatThrownBy(
            () ->
                inTransaction(
                    transactionDsl ->
                        seal(transactionDsl, 8L, "1a".repeat(32), "{\"definitions\":[]}")))
        .isInstanceOf(ScriptPatchAlreadySealedException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    transactionDsl ->
                        seal(transactionDsl, 7L, "2b".repeat(32), "{\"definitions\":[1]}")))
        .isInstanceOf(ScriptPatchAlreadySealedException.class);
    assertThat(
            dsl.fetchValue("SELECT count(*) FROM script_patch_publication_seals WHERE is_sealed"))
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
                        transactionDsl.execute(
                            "INSERT INTO scripts (tenant_id, name, version, definition) "
                                + "VALUES (23, 'alpha', 'patch-7', '{\"v\":1}')");
                        updateHasLock.countDown();
                        await(releaseUpdate);
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
                            7L,
                            "patch-7",
                            1,
                            5,
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
      assertThat(
              dsl.fetchValue(
                  "SELECT snapshot_json FROM script_patch_publication_seals "
                      + "WHERE tenant_id = 23 AND script_patch_version = 'patch-7'"))
          .isEqualTo("{\"definitions\":[{\"name\":\"alpha\"}]}");
    } finally {
      releaseUpdate.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void sealedPatchRejectsRawDefinitionBindingBaseAndSealMutations() {
    dsl.execute(
        "INSERT INTO scripts(tenant_id,name,version,base_version_id,definition) VALUES(23,'alpha','patch-7',7,'{}')");
    dsl.execute(
        "INSERT INTO script_event_bindings(tenant_id,script_patch_version,base_version_id,event_type,event_schema_version,script_id,binding_id,target_scope_type,target_scope_id) VALUES(23,'patch-7',7,'onEnter','1','alpha','binding','ROOM','')");
    inTransaction(tx -> seal(tx, 7L, "1a".repeat(32), "{}"));
    for (String sql :
        java.util.List.of(
            "UPDATE scripts SET definition='{\"changed\":true}' WHERE tenant_id=23",
            "DELETE FROM scripts WHERE tenant_id=23",
            "UPDATE script_event_bindings SET enabled=false WHERE tenant_id=23",
            "DELETE FROM script_event_bindings WHERE tenant_id=23",
            "UPDATE script_patch_base_bindings SET base_version_id=8 WHERE tenant_id='23'",
            "DELETE FROM script_patch_base_bindings WHERE tenant_id='23'",
            "DELETE FROM script_patch_publication_seals",
            "UPDATE script_patch_publication_seals SET snapshot_json='{\"changed\":true}'",
            "TRUNCATE TABLE scripts",
            "TRUNCATE TABLE script_patch_publication_seals")) {
      assertThatThrownBy(() -> dsl.execute(sql))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
    assertThat(dsl.fetchValue("SELECT definition FROM scripts WHERE tenant_id=23")).isEqualTo("{}");
    assertThat(
            dsl.fetchValue(
                "SELECT snapshot_json FROM script_patch_publication_seals WHERE tenant_id=23"))
        .isEqualTo("{}");
  }

  @Test
  void sealRequiresPhysicalTransactionAndExactRetainedBase() {
    assertThatThrownBy(
            () -> new ScriptPatchPublicationSealRepository(dsl).lockForUpdate(23L, "patch-7"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owned transaction");
    assertThatThrownBy(() -> inTransaction(tx -> seal(tx, 8L, "1a".repeat(32), "{}")))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("exact retained base");
    assertThat(
            dsl.fetchValue("SELECT count(*) FROM script_patch_publication_seals WHERE is_sealed"))
        .isEqualTo(0L);
  }

  @Test
  void actualOwnerCapturesExactStoredRowsAndRequestAndResealsIdentically() {
    dsl.execute(
        "INSERT INTO scripts(tenant_id,name,version,base_version_id,definition) VALUES(23,'alpha','patch-7',7,'{}')");
    var binding =
        net.firedevops.firemud.common.publication.PublicationDigestRequestBinding.patch(
            "23", "7", "patch-7", "request-7");
    java.util.function.Function<
            DSLContext, net.firedevops.firemud.automationscripting.model.ScriptPatchPublicationSeal>
        capture =
            tx -> {
              var definitions = new ScriptDefinitionRepository(tx);
              var bindings = new ScriptEventBindingRepository(tx);
              var mapper = new tools.jackson.databind.ObjectMapper();
              var digest =
                  new net.firedevops.firemud.automationscripting.service.impl
                      .ScriptDesignDigestServiceImpl(definitions, bindings, mapper);
              return new net.firedevops.firemud.automationscripting.service.impl
                      .ScriptPatchPublicationSealService(
                      definitions,
                      bindings,
                      new ScriptPatchPublicationSealRepository(tx),
                      digest,
                      mapper)
                  .sealSnapshot(
                      binding, binding.derivedWorkflowIdentity(), binding.requestDigest());
            };
    var first = inTransaction(capture);
    assertThat(inTransaction(capture)).isEqualTo(first);
    assertThat(first.contentDigestSchemaVersion()).isEqualTo(5);
    assertThat(first.snapshotJson()).contains(binding.requestDigest()).contains("alpha");
    assertThat(first.snapshotJson()).doesNotContain("onLoadHandlerIds");
  }

  @Test
  void establishedRepeatableReadSnapshotCannotSealOverACommittedWriter() throws Exception {
    try (var connection = dataSource(schema).getConnection()) {
      connection.setAutoCommit(false);
      var oldSnapshot = DSL.using(connection, SQLDialect.POSTGRES);
      oldSnapshot.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
      assertThat(oldSnapshot.fetchValue("SELECT count(*) FROM scripts")).isEqualTo(0L);
      dsl.execute(
          "INSERT INTO scripts(tenant_id,name,version,base_version_id,definition) VALUES(23,'late','patch-7',7,'{}')");
      assertThat(oldSnapshot.fetchValue("SELECT count(*) FROM scripts")).isEqualTo(0L);
      assertThat(dsl.fetchValue("SELECT count(*) FROM scripts")).isEqualTo(1L);
      assertThatThrownBy(() -> seal(oldSnapshot, 7L, "1a".repeat(32), "{}"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable READ COMMITTED");
      connection.rollback();
    }
    assertThat(
            dsl.fetchValue("SELECT count(*) FROM script_patch_publication_seals WHERE is_sealed"))
        .isEqualTo(0L);
  }

  @Test
  void readOnlyTransactionCannotOwnASeal() throws Exception {
    try (var connection = dataSource(schema).getConnection()) {
      connection.setAutoCommit(false);
      var readOnly = DSL.using(connection, SQLDialect.POSTGRES);
      readOnly.execute("SET TRANSACTION READ ONLY");
      assertThatThrownBy(() -> seal(readOnly, 7L, "1a".repeat(32), "{}"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable READ COMMITTED");
      connection.rollback();
    }
  }

  private static net.firedevops.firemud.automationscripting.model.ScriptPatchPublicationSeal seal(
      DSLContext transactionDsl, Long baseVersionId, String digest, String snapshot) {
    return new ScriptPatchPublicationSealRepository(transactionDsl)
        .seal(23L, baseVersionId, "patch-7", 1, 5, digest, snapshot);
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
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setSchema(schema);
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
