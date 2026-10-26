package net.firedevops.firemud.gamesession.repository;

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
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER_GAME_INSTANCE_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID CANONICAL_REGION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATIONAL_REGION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CAPTURE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String GRAPH_DIGEST = "a".repeat(64);
  private static final String PREPARATION_DIGEST = "sha256:" + "b".repeat(64);
  private static final String FIRST_LEASE_CORRELATION = "c".repeat(64);
  private static final String SECOND_LEASE_CORRELATION = "d".repeat(64);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private DSLContext dsl;
  private CanonicalGameplayRegionRuntimeStatusRepository repository;
  private CanonicalGameplayRegionRuntimeStatusRepository.Source source;

  @BeforeAll
  void setUpRepository() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new CanonicalGameplayRegionRuntimeStatusRepository(dsl);
    source = source(GAME_INSTANCE_ID, CAPTURE_ID);
  }

  @BeforeEach
  void clearOwnerRows() {
    dsl.execute("TRUNCATE TABLE game_session_region_runtime_status");
  }

  @Test
  void initializesOnlyAnAbsentRegionAtItsFirstEpochAndFirstTick() {
    assertThatThrownBy(
            () ->
                repository.installOwner(
                    source,
                    2L,
                    null,
                    FIRST_LEASE_CORRELATION,
                    "game-session-service",
                    "instance-a",
                    Instant.parse("2026-10-26T00:00:00Z")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.find(source)).isEmpty();

    var installed =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));

    assertThat(installed.tenantRegionTag())
        .isEqualTo(
            CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                TENANT_ID, GAME_INSTANCE_ID, OPERATIONAL_REGION_ID));
    assertThat(installed.regionEpoch()).isEqualTo(1L);
    assertThat(installed.lifecycleEpoch()).isEqualTo(4L);
    assertThat(installed.lastCommittedTickId()).isEqualTo(-1L);
    assertThat(installed.executorFence()).isPositive();
    assertThat(repository.find(source)).contains(installed);
  }

  @Test
  void ownerReadRejectsAnAmbientSynchronizationContext() {
    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> repository.find(source)).isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void exactOwnerRetryReplacesFenceWithoutResettingRegionTimeline() {
    var initial =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));
    long sequenceAfterInstall = fenceSequenceValue();
    var sameGrantRetry =
        repository.installOwner(
            source,
            1L,
            initial,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));

    assertThat(sameGrantRetry).isEqualTo(initial);
    assertThat(fenceSequenceValue()).isEqualTo(sequenceAfterInstall);

    dsl.execute(
        "UPDATE game_session_region_runtime_status"
            + " SET last_committed_tick_batch_id = 'batch-7', last_committed_tick_id = 7"
            + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
        TENANT_ID,
        GAME_INSTANCE_ID,
        OPERATIONAL_REGION_ID);
    var current = repository.find(source).orElseThrow();

    var retry =
        repository.installOwner(
            source,
            1L,
            current,
            SECOND_LEASE_CORRELATION,
            "game-session-service",
            "instance-b",
            Instant.parse("2026-10-26T00:00:02Z"));

    assertThat(retry.regionEpoch()).isEqualTo(1L);
    assertThat(retry.executorFence()).isGreaterThan(initial.executorFence());
    assertThat(retry.leaseTokenCorrelation()).isEqualTo(SECOND_LEASE_CORRELATION);
    assertThat(retry.ownerInstanceId()).isEqualTo("instance-b");
    assertThat(retry.lastCommittedTickId()).isEqualTo(7L);
    assertThat(retry.lastCommittedTickBatchId()).isEqualTo("batch-7");
    assertThatThrownBy(
            () ->
                repository.installOwner(
                    source,
                    1L,
                    initial,
                    FIRST_LEASE_CORRELATION,
                    "game-session-service",
                    "instance-a",
                    Instant.parse("2026-10-26T00:00:03Z")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.find(source)).contains(retry);
  }

  @Test
  void sameNaturalScopeRejectsChangedWorldCaptureEvidence() {
    repository.installOwner(
        source,
        1L,
        null,
        FIRST_LEASE_CORRELATION,
        "game-session-service",
        "instance-a",
        Instant.parse("2026-10-26T00:00:01Z"));

    assertThatThrownBy(
            () ->
                repository.find(
                    source(GAME_INSTANCE_ID, new UUID(0x6666666666666666L, 0x8666666666666666L))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sameRegionInDifferentGameInstancesHasDistinctPersistedOwnerTags() {
    var first =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));
    var otherGameSource = source(OTHER_GAME_INSTANCE_ID, CAPTURE_ID);
    var otherGame =
        repository.installOwner(
            otherGameSource,
            1L,
            null,
            SECOND_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:02Z"));

    assertThat(first.regionId()).isEqualTo(otherGame.regionId());
    assertThat(first.tenantRegionTag()).isNotEqualTo(otherGame.tenantRegionTag());
    assertThat(repository.find(source)).contains(first);
    assertThat(repository.find(otherGameSource)).contains(otherGame);
  }

  @Test
  void concurrentNewGrantsFromOnePriorOwnerInstallOnlyOneNewFence() throws Exception {
    var prior =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-prior",
            Instant.parse("2026-10-26T00:00:01Z"));
    long sequenceBefore = fenceSequenceValue();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus> first =
          executor.submit(
              () -> installAfterBarrier(prior, "e".repeat(64), "instance-a", ready, start));
      Future<net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus>
          second =
              executor.submit(
                  () -> installAfterBarrier(prior, "f".repeat(64), "instance-b", ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstResult = first.get(10, TimeUnit.SECONDS);
      var secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat((firstResult == null) ^ (secondResult == null)).isTrue();

      var committed = repository.find(source).orElseThrow();
      assertThat(committed.executorFence()).isGreaterThan(prior.executorFence());
      assertThat(committed.regionEpoch()).isEqualTo(prior.regionEpoch());
      assertThat(fenceSequenceValue()).isEqualTo(sequenceBefore + 1L);
      assertThat(committed).isEqualTo(firstResult == null ? secondResult : firstResult);
    }
  }

  private CanonicalGameplayRegionRuntimeStatus installAfterBarrier(
      CanonicalGameplayRegionRuntimeStatus prior,
      String correlation,
      String ownerInstanceId,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    try {
      return repository.installOwner(
          source,
          1L,
          prior,
          correlation,
          "game-session-service",
          ownerInstanceId,
          Instant.parse("2026-10-26T00:00:02Z"));
    } catch (IllegalStateException staleGrant) {
      return null;
    }
  }

  private long fenceSequenceValue() {
    Record row = dsl.fetchOne("SELECT last_value FROM game_session_region_executor_fence_seq");
    assertThat(row).isNotNull();
    Long value = row.get("last_value", Long.class);
    assertThat(value).isPositive();
    return value;
  }

  private static CanonicalGameplayRegionRuntimeStatusRepository.Source source(
      UUID gameInstanceId, UUID captureId) {
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "firemud",
            TENANT_ID,
            "test-world",
            gameInstanceId,
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            "SHARED",
            true,
            "control-plane-request",
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        TENANT_ID,
        gameInstanceId,
        CANONICAL_REGION_ID,
        OPERATIONAL_REGION_ID,
        4L,
        12L,
        request,
        captureId,
        GRAPH_DIGEST,
        PREPARATION_DIGEST);
  }
}
