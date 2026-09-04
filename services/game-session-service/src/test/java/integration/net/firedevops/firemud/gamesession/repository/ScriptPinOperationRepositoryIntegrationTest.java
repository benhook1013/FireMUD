package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.GameInstances.GAME_INSTANCES;
import static net.firedevops.firemud.gamesession.jooq.tables.ScriptPinOperation.SCRIPT_PIN_OPERATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ScriptPinOperationRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private GameInstanceRepository repository;

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
    repository = new GameInstanceRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute("TRUNCATE TABLE script_pin_operation, game_instances CASCADE");
    dsl.insertInto(GAME_INSTANCES)
        .set(GAME_INSTANCES.ID, 7L)
        .set(GAME_INSTANCES.TENANT_ID, 1L)
        .set(GAME_INSTANCES.RUNTIME_VERSION, "runtime-1")
        .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, "patch-1")
        .set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, 1L)
        .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID, "initial")
        .set(GAME_INSTANCES.OWNER_ACCOUNT_ID, 99L)
        .set(GAME_INSTANCES.STATUS, "RUNNING")
        .set(GAME_INSTANCES.ROW_VERSION, 0L)
        .execute();
  }

  @Test
  void concurrentExpectedEpochWritersAllowOneAndRejectTheStaleWriter() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<ScriptPinMutationResult> first =
          executor.submit(() -> applyAfterBarrier("request-a", "patch-a", ready, start));
      Future<ScriptPinMutationResult> second =
          executor.submit(() -> applyAfterBarrier("request-b", "patch-b", ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      ScriptPinMutationResult firstResult = first.get(10, TimeUnit.SECONDS);
      ScriptPinMutationResult secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(
              java.util.stream.Stream.of(firstResult, secondResult)
                  .filter(ScriptPinMutationResult::succeeded)
                  .count())
          .isEqualTo(1L);
      assertThat(
              java.util.stream.Stream.of(firstResult, secondResult)
                  .filter(result -> !result.succeeded())
                  .count())
          .isEqualTo(1L);
      ScriptPinMutationResult loser = firstResult.succeeded() ? secondResult : firstResult;
      assertThat(loser.errorCode()).isEqualTo("SCRIPT_PIN_EXPECTATION_FAILED");
      assertThat(dsl.fetchCount(SCRIPT_PIN_OPERATION)).isEqualTo(2);

      ScriptPinMutationResult winner = firstResult.succeeded() ? firstResult : secondResult;
      assertThat(
              dsl.select(GAME_INSTANCES.SCRIPT_PATCH_VERSION, GAME_INSTANCES.SCRIPT_PIN_EPOCH)
                  .from(GAME_INSTANCES)
                  .where(GAME_INSTANCES.ID.eq(7L))
                  .fetchOne())
          .satisfies(
              record -> {
                assertThat(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION))
                    .isEqualTo(winner.resultingScriptPatchVersion());
                assertThat(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH)).isEqualTo(2L);
              });
      ScriptPinMutationResult retry =
          repository.applyScriptPin(
              1L,
              7L,
              winner == firstResult ? "SET" : "SET",
              winner.resultingScriptPatchVersion(),
              winner.controlPlaneRequestId(),
              "operator",
              "concurrent",
              "EXPECT_EPOCH",
              1L);
      assertThat(retry).isEqualTo(winner);
    }
  }

  @Test
  void concurrentExactRetriesReplayOneCommittedResult() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<ScriptPinMutationResult> first =
          executor.submit(() -> applyAfterBarrier("request-same", "patch-same", ready, start));
      Future<ScriptPinMutationResult> second =
          executor.submit(() -> applyAfterBarrier("request-same", "patch-same", ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      ScriptPinMutationResult firstResult = first.get(10, TimeUnit.SECONDS);
      ScriptPinMutationResult secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult).isEqualTo(secondResult);
      assertThat(firstResult.succeeded()).isTrue();
      assertThat(dsl.fetchCount(SCRIPT_PIN_OPERATION)).isEqualTo(1);
      assertThat(
              dsl.select(GAME_INSTANCES.SCRIPT_PATCH_VERSION, GAME_INSTANCES.SCRIPT_PIN_EPOCH)
                  .from(GAME_INSTANCES)
                  .where(GAME_INSTANCES.ID.eq(7L))
                  .fetchOne())
          .satisfies(
              record -> {
                assertThat(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION)).isEqualTo("patch-same");
                assertThat(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH)).isEqualTo(2L);
              });
    }
  }

  @Test
  void requestIdReuseWithDifferentDigestDoesNotMutate() {
    ScriptPinMutationResult committed =
        repository.applyScriptPin(
            1L, 7L, "SET", "patch-a", "request-a", "operator", "first", "EXPECT_EPOCH", 1L);
    ScriptPinMutationResult conflict =
        repository.applyScriptPin(
            1L, 7L, "SET", "patch-b", "request-a", "operator", "first", "EXPECT_EPOCH", 1L);

    assertThat(committed.succeeded()).isTrue();
    assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(
            dsl.select(GAME_INSTANCES.SCRIPT_PATCH_VERSION, GAME_INSTANCES.SCRIPT_PIN_EPOCH)
                .from(GAME_INSTANCES)
                .where(GAME_INSTANCES.ID.eq(7L))
                .fetchOne())
        .satisfies(
            record -> {
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION)).isEqualTo("patch-a");
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH)).isEqualTo(2L);
            });
  }

  @Test
  void exactRetryReplaysCommittedResultAtEpochExhaustion() {
    dsl.update(GAME_INSTANCES).set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, Long.MAX_VALUE - 1L).execute();

    ScriptPinMutationResult committed =
        repository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-max",
            "request-max",
            "operator",
            "exhaustion",
            "EXPECT_EPOCH",
            Long.MAX_VALUE - 1L);
    ScriptPinMutationResult retry =
        repository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-max",
            "request-max",
            "operator",
            "exhaustion",
            "EXPECT_EPOCH",
            Long.MAX_VALUE - 1L);

    assertThat(committed.succeeded()).isTrue();
    assertThat(committed.resultingScriptPinEpoch()).isEqualTo(Long.MAX_VALUE);
    assertThat(retry).isEqualTo(committed);
    assertThat(dsl.fetchCount(SCRIPT_PIN_OPERATION)).isEqualTo(1);
  }

  @Test
  void newMutationAtEpochExhaustionRecordsFailureAndReplaysWithoutStateChange() {
    dsl.update(GAME_INSTANCES).set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, Long.MAX_VALUE).execute();

    ScriptPinMutationResult exhausted =
        repository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-new",
            "operator",
            "exhaustion",
            "EXPECT_EPOCH",
            Long.MAX_VALUE);
    ScriptPinMutationResult retry =
        repository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-new",
            "operator",
            "exhaustion",
            "EXPECT_EPOCH",
            Long.MAX_VALUE);
    ScriptPinMutationResult digestConflict =
        repository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-different",
            "request-new",
            "operator",
            "exhaustion",
            "EXPECT_EPOCH",
            Long.MAX_VALUE);

    assertThat(exhausted.succeeded()).isFalse();
    assertThat(exhausted.errorCode()).isEqualTo("SCRIPT_PIN_EPOCH_EXHAUSTED");
    assertThat(retry).isEqualTo(exhausted);
    assertThat(digestConflict.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(dsl.fetchCount(SCRIPT_PIN_OPERATION)).isEqualTo(1);
    assertThat(
            dsl.select(
                    GAME_INSTANCES.SCRIPT_PATCH_VERSION,
                    GAME_INSTANCES.SCRIPT_PIN_EPOCH,
                    GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID)
                .from(GAME_INSTANCES)
                .where(GAME_INSTANCES.ID.eq(7L))
                .fetchOne())
        .satisfies(
            record -> {
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION)).isEqualTo("patch-1");
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH)).isEqualTo(Long.MAX_VALUE);
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID))
                  .isEqualTo("initial");
            });
  }

  @Test
  void epochExhaustionDoesNotMaskExpectationMismatch() {
    dsl.update(GAME_INSTANCES).set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, Long.MAX_VALUE).execute();

    ScriptPinMutationResult mismatch =
        repository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-mismatch",
            "operator",
            "exhaustion",
            "EXPECT_EPOCH",
            Long.MAX_VALUE - 1L);

    assertThat(mismatch.succeeded()).isFalse();
    assertThat(mismatch.errorCode()).isEqualTo("SCRIPT_PIN_EXPECTATION_FAILED");
    assertThat(dsl.fetchCount(SCRIPT_PIN_OPERATION)).isEqualTo(1);
    assertThat(
            dsl.select(GAME_INSTANCES.SCRIPT_PATCH_VERSION, GAME_INSTANCES.SCRIPT_PIN_EPOCH)
                .from(GAME_INSTANCES)
                .where(GAME_INSTANCES.ID.eq(7L))
                .fetchOne())
        .satisfies(
            record -> {
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION)).isEqualTo("patch-1");
              assertThat(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH)).isEqualTo(Long.MAX_VALUE);
            });
  }

  private ScriptPinMutationResult applyAfterBarrier(
      String requestId, String target, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    return repository.applyScriptPin(
        1L, 7L, "SET", target, requestId, "operator", "concurrent", "EXPECT_EPOCH", 1L);
  }
}
