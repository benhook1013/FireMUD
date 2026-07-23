package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus.RUNTIME_REGION_STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void optimisticTickWritersUpdateOnlyTheirOwnedColumns() {
    repository.save(runtimeStatus("region-one", "instance-one"));
    RuntimeRegionStatus expected = repository.findByTenantIdAndGameInstanceId(1L, 2L).orElseThrow();
    expected.setUpdatedAt(Instant.parse("2026-07-23T00:00:01Z"));

    RuntimeRegionStatus progressed = repository.advanceLastCommittedTickId(expected).orElseThrow();
    assertThat(progressed.getLastCommittedTickId()).isEqualTo(1L);
    assertThat(progressed.getRegionEpoch()).isEqualTo(expected.getRegionEpoch());
    assertThat(progressed.getExecutorFence()).isEqualTo(expected.getExecutorFence());
    assertThat(progressed.isPaused()).isFalse();

    progressed.setUpdatedAt(Instant.parse("2026-07-23T00:00:02Z"));
    RuntimeRegionStatus drained =
        repository.commitDrainedBatch(progressed, "batch-one").orElseThrow();
    assertThat(drained.getLastCommittedTickBatchId()).isEqualTo("batch-one");
    assertThat(drained.getLastCommittedTickId()).isEqualTo(1L);
    assertThat(repository.commitDrainedBatch(progressed, "batch-stale")).isEmpty();
  }

  @Test
  void staleBaselineRefreshCannotOverwriteConcurrentPause() throws Exception {
    CountDownLatch baselineRead = new CountDownLatch(1);
    CountDownLatch pauseCommitted = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<RuntimeRegionStatus> baseline =
          executor.submit(
              () -> {
                RuntimeRegionStatus baselineStatus =
                    repository.ensureBaseline(
                        runtimeStatus("region-baseline", "instance-baseline"));
                baselineRead.countDown();
                assertThat(pauseCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                baselineStatus.setOwnerService("game-session-service");
                baselineStatus.setOwnerInstanceId("instance-baseline");
                baselineStatus.setUpdatedAt(Instant.parse("2026-07-23T00:00:01Z"));
                return repository.refreshObservedOwnership(baselineStatus);
              });
      Future<RuntimeRegionStatus> pause =
          executor.submit(
              () -> {
                assertThat(baselineRead.await(5, TimeUnit.SECONDS)).isTrue();
                RuntimeRegionStatus pauseStatus = runtimeStatus("region-pause", "instance-pause");
                pauseStatus.setPaused(true);
                pauseStatus.setExecutorFence("fence-pause");
                RuntimeRegionStatus paused = repository.advanceOwnershipEpoch(pauseStatus);
                pauseCommitted.countDown();
                return paused;
              });

      RuntimeRegionStatus pauseResult = pause.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> baseline.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Runtime ownership changed during observation refresh");
      RuntimeRegionStatus committed =
          repository.findByTenantIdAndGameInstanceId(1L, 2L).orElseThrow();

      assertThat(pauseResult.isPaused()).isTrue();
      assertThat(committed.isPaused()).isTrue();
      assertThat(committed.getRegionEpoch()).isEqualTo(pauseResult.getRegionEpoch());
      assertThat(committed.getExecutorFence()).isEqualTo(pauseResult.getExecutorFence());
      assertThat(committed.getOwnerService()).isEqualTo(pauseResult.getOwnerService());
      assertThat(committed.getOwnerInstanceId()).isEqualTo(pauseResult.getOwnerInstanceId());
    }
  }

  @Test
  void staleTickProgressCannotOverwriteAConcurrentPause() {
    repository.save(runtimeStatus("region-one", "instance-one"));
    RuntimeRegionStatus staleWriter =
        repository.findByTenantIdAndGameInstanceId(1L, 2L).orElseThrow();
    RuntimeRegionStatus pause = runtimeStatus("region-one", "instance-pause");
    pause.setPaused(true);
    pause.setExecutorFence("fence-pause");
    RuntimeRegionStatus paused = repository.advanceOwnershipEpoch(pause);

    assertThat(repository.advanceLastCommittedTickId(staleWriter)).isEmpty();
    assertThat(repository.findByTenantIdAndGameInstanceId(1L, 2L))
        .get()
        .satisfies(
            committed -> {
              assertThat(committed.isPaused()).isTrue();
              assertThat(committed.getRegionEpoch()).isEqualTo(paused.getRegionEpoch());
              assertThat(committed.getLastCommittedTickId()).isEqualTo(0L);
            });
  }

  @Test
  void staleBatchDrainCannotOverwriteAConcurrentPause() {
    repository.save(runtimeStatus("region-one", "instance-one"));
    RuntimeRegionStatus staleWriter =
        repository.findByTenantIdAndGameInstanceId(1L, 2L).orElseThrow();
    RuntimeRegionStatus pause = runtimeStatus("region-one", "instance-pause");
    pause.setPaused(true);
    pause.setExecutorFence("fence-pause");
    RuntimeRegionStatus paused = repository.advanceOwnershipEpoch(pause);

    assertThat(repository.commitDrainedBatch(staleWriter, "batch-stale")).isEmpty();
    assertThat(repository.findByTenantIdAndGameInstanceId(1L, 2L))
        .get()
        .satisfies(
            committed -> {
              assertThat(committed.isPaused()).isTrue();
              assertThat(committed.getRegionEpoch()).isEqualTo(paused.getRegionEpoch());
              assertThat(committed.getLastCommittedTickBatchId()).isNull();
            });
  }

  private RuntimeRegionStatus saveAfterBarrier(
      RuntimeRegionStatus status, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    return repository.save(status);
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
