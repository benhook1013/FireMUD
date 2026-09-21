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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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
                        new PluginRuntimeStateRepository(DSL.using(configuration))
                            .lockLifecycleScope("1", "game-1", "plugin-1");
                      }));
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

  private static PluginRuntimeRequestHistory failedReceipt(String requestId, String digest) {
    PluginRuntimeRequestHistory history = new PluginRuntimeRequestHistory();
    history.setTenantId("1");
    history.setGameInstanceId("game-1");
    history.setPluginId("plugin-1");
    history.setOperation("DRAIN");
    history.setControlPlaneRequestId(requestId);
    history.setRequestFingerprint(digest);
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

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
