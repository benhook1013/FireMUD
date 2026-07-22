package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus.RUNTIME_REGION_STATUS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
class RuntimeRegionStatusRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private RuntimeRegionStatusRepository repository;

  @BeforeAll
  void setUpRepository() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new RuntimeRegionStatusRepository(dsl);
  }

  @BeforeEach
  void cleanTable() {
    dsl.execute("TRUNCATE TABLE runtime_region_status RESTART IDENTITY");
  }

  @Test
  void concurrentNaturalKeyCreationReturnsTheSingleCommittedOwnershipRow() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<RuntimeRegionStatus> first =
          executor.submit(
              () ->
                  saveAfterBarrier(runtimeStatus("region-first", "instance-first"), ready, start));
      Future<RuntimeRegionStatus> second =
          executor.submit(
              () ->
                  saveAfterBarrier(
                      runtimeStatus("region-second", "instance-second"), ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      RuntimeRegionStatus firstSaved = first.get(10, TimeUnit.SECONDS);
      RuntimeRegionStatus secondSaved = second.get(10, TimeUnit.SECONDS);

      assertThat(firstSaved.getId()).isEqualTo(secondSaved.getId());
      assertThat(dsl.fetchCount(RUNTIME_REGION_STATUS)).isEqualTo(1);
      assertThat(repository.findByTenantIdAndGameInstanceId(1L, 2L))
          .get()
          .extracting(RuntimeRegionStatus::getId)
          .isEqualTo(firstSaved.getId());
    }
  }

  @Test
  void saveStillUpdatesAnExistingRowByItsDatabaseId() {
    RuntimeRegionStatus inserted = repository.save(runtimeStatus("region-one", "instance-one"));
    inserted.setRegionEpoch(4L);
    inserted.setPaused(true);

    RuntimeRegionStatus updated = repository.save(inserted);

    assertThat(updated.getId()).isEqualTo(inserted.getId());
    assertThat(updated.getRegionEpoch()).isEqualTo(4L);
    assertThat(updated.isPaused()).isTrue();
    assertThat(dsl.fetchCount(RUNTIME_REGION_STATUS)).isEqualTo(1);
  }

  @Test
  void advanceOwnershipEpochAtomicallyUpdatesPauseAndFence() {
    repository.save(runtimeStatus("region-one", "instance-one"));
    RuntimeRegionStatus pause = runtimeStatus("region-ignored", "instance-pause");
    pause.setPaused(true);
    pause.setExecutorFence("fence-pause");

    RuntimeRegionStatus updated = repository.advanceOwnershipEpoch(pause);

    assertThat(updated.getRegionEpoch()).isEqualTo(2L);
    assertThat(updated.isPaused()).isTrue();
    assertThat(updated.getExecutorFence()).isEqualTo("fence-pause");
    assertThat(repository.findByTenantIdAndGameInstanceId(1L, 2L))
        .get()
        .satisfies(
            committed -> {
              assertThat(committed.getRegionEpoch()).isEqualTo(2L);
              assertThat(committed.isPaused()).isTrue();
              assertThat(committed.getExecutorFence()).isEqualTo("fence-pause");
            });
  }

  @Test
  void concurrentBaselineAndPauseLeaveTheCommittedRowPaused() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<RuntimeRegionStatus> baseline =
          executor.submit(
              () ->
                  ensureBaselineAfterBarrier(
                      runtimeStatus("region-baseline", "instance-baseline"), ready, start));
      Future<RuntimeRegionStatus> pause =
          executor.submit(
              () ->
                  advancePauseAfterBarrier(
                      runtimeStatus("region-pause", "instance-pause"), ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      RuntimeRegionStatus baselineResult = baseline.get(10, TimeUnit.SECONDS);
      RuntimeRegionStatus pauseResult = pause.get(10, TimeUnit.SECONDS);
      RuntimeRegionStatus committed =
          repository.findByTenantIdAndGameInstanceId(1L, 2L).orElseThrow();

      assertThat(pauseResult.isPaused()).isTrue();
      assertThat(committed.isPaused()).isTrue();
      assertThat(committed.getRegionEpoch()).isEqualTo(pauseResult.getRegionEpoch());
      assertThat(committed.getExecutorFence()).isEqualTo(pauseResult.getExecutorFence());
      assertThat(baselineResult.getId()).isEqualTo(committed.getId());
    }
  }

  private RuntimeRegionStatus saveAfterBarrier(
      RuntimeRegionStatus status, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    return repository.save(status);
  }

  private RuntimeRegionStatus ensureBaselineAfterBarrier(
      RuntimeRegionStatus status, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    RuntimeRegionStatus baseline = repository.ensureBaseline(status);
    baseline.setOwnerService("game-session-service");
    baseline.setOwnerInstanceId("instance-baseline");
    baseline.setUpdatedAt(Instant.parse("2026-07-23T00:00:01Z"));
    return repository.refreshObservedOwnership(baseline);
  }

  private RuntimeRegionStatus advancePauseAfterBarrier(
      RuntimeRegionStatus status, CountDownLatch ready, CountDownLatch start) throws Exception {
    status.setPaused(true);
    status.setExecutorFence("fence-pause");
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    return repository.advanceOwnershipEpoch(status);
  }

  private static RuntimeRegionStatus runtimeStatus(String regionId, String ownerInstanceId) {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(1L);
    status.setGameInstanceId(2L);
    status.setRegionId(regionId);
    status.setRegionEpoch(1L);
    status.setExecutorFence("fence-" + ownerInstanceId);
    status.setOwnerService("game-session-service");
    status.setOwnerInstanceId(ownerInstanceId);
    status.setPaused(false);
    status.setLastCommittedTickId(0L);
    status.setUpdatedAt(Instant.parse("2026-07-23T00:00:00Z"));
    return status;
  }
}
