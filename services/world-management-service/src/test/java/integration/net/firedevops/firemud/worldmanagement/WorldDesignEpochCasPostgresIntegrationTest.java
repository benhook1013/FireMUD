package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignAggregateEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignScopeEpochRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Current Flyway storage proof using controlled private legacy selectors. */
@Testcontainers(disabledWithoutDocker = true)
class WorldDesignEpochCasPostgresIntegrationTest {
  private static final LocalDateTime WINNING_TIME = LocalDateTime.of(2026, 10, 6, 1, 0);
  private static final LocalDateTime LOSING_TIME = WINNING_TIME.plusMinutes(1);

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @ParameterizedTest
  @ValueSource(longs = {0L, 7L})
  void concurrentOriginalEpochClaimsHaveOneWinnerAndPreserveEveryNeighbor(long expected)
      throws Exception {
    String schema = "world_epoch_cas_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    dsl.execute(
        """
        INSERT INTO world_design_aggregate_epoch
          (tenant_id, version_id, aggregate_type, aggregate_id, draft_revision_epoch)
        VALUES (502, 71, 'ROOM', 6, ?), (501, 72, 'ROOM', 6, ?),
               (501, 71, 'REGION', 6, ?), (501, 71, 'ROOM', 7, ?)
        """,
        expected,
        expected,
        expected,
        expected);
    dsl.execute(
        """
        INSERT INTO world_design_scope_epoch
          (tenant_id, version_id, scope_type, scope_id, draft_scope_revision_epoch)
        VALUES (502, 71, 'ZONE_SUBTREE', '6', ?), (501, 72, 'ZONE_SUBTREE', '6', ?),
               (501, 71, 'REGION_SUBTREE', '6', ?), (501, 71, 'ZONE_SUBTREE', '7', ?)
        """,
        expected,
        expected,
        expected,
        expected);
    if (expected != 0L) {
      dsl.execute(
          "INSERT INTO world_design_aggregate_epoch (tenant_id, version_id, aggregate_type, "
              + "aggregate_id, draft_revision_epoch) VALUES (501, 71, 'ROOM', 6, ?)",
          expected);
      dsl.execute(
          "INSERT INTO world_design_scope_epoch (tenant_id, version_id, scope_type, "
              + "scope_id, draft_scope_revision_epoch) VALUES (501, 71, 'ZONE_SUBTREE', '6', ?)",
          expected);
    }
    String aggregateNeighbors =
        "SELECT r.xmin::text, to_jsonb(r)::text FROM world_design_aggregate_epoch r "
            + "WHERE (tenant_id, version_id, aggregate_type, aggregate_id) <> (501, 71, 'ROOM', 6) "
            + "ORDER BY id";
    String scopeNeighbors =
        "SELECT r.xmin::text, to_jsonb(r)::text FROM world_design_scope_epoch r "
            + "WHERE (tenant_id, version_id, scope_type, scope_id) <> (501, 71, 'ZONE_SUBTREE', '6') "
            + "ORDER BY id";
    var aggregatesBefore = dsl.fetch(aggregateNeighbors);
    var scopesBefore = dsl.fetch(scopeNeighbors);
    assertThat(aggregatesBefore).hasSize(4);
    assertThat(scopesBefore).hasSize(4);

    var aggregate = new WorldDesignAggregateEpochRepository(dsl);
    assertExactlyOneWinner(
        dsl,
        transaction,
        () -> aggregate.compareAndAdvance(501L, 71L, "ROOM", 6L, expected, WINNING_TIME),
        () -> aggregate.compareAndAdvance(501L, 71L, "ROOM", 6L, expected, LOSING_TIME));
    var retainedAggregate =
        aggregate
            .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(501L, 71L, "ROOM", 6L)
            .orElseThrow();
    assertThat(retainedAggregate.getDraftRevisionEpoch()).isEqualTo(expected + 1L);
    assertThat(retainedAggregate.getUpdatedAt()).isEqualTo(WINNING_TIME);

    var scope = new WorldDesignScopeEpochRepository(dsl);
    assertExactlyOneWinner(
        dsl,
        transaction,
        () -> scope.compareAndAdvance(501L, 71L, "ZONE_SUBTREE", "6", expected, WINNING_TIME),
        () -> scope.compareAndAdvance(501L, 71L, "ZONE_SUBTREE", "6", expected, LOSING_TIME));
    var retainedScope =
        scope
            .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(501L, 71L, "ZONE_SUBTREE", "6")
            .orElseThrow();
    assertThat(retainedScope.getDraftScopeRevisionEpoch()).isEqualTo(expected + 1L);
    assertThat(retainedScope.getUpdatedAt()).isEqualTo(WINNING_TIME);
    assertThat(dsl.fetch(aggregateNeighbors)).isEqualTo(aggregatesBefore);
    assertThat(dsl.fetch(scopeNeighbors)).isEqualTo(scopesBefore);
  }

  private static void assertExactlyOneWinner(
      DSLContext dsl,
      TransactionTemplate transaction,
      BooleanSupplier first,
      BooleanSupplier contender)
      throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      var contenderPid = new AtomicInteger();
      var entered = new CountDownLatch(1);
      var loser =
          transaction.execute(
              status -> {
                int ownerPid = backendPid(dsl);
                assertThat(first.getAsBoolean()).isTrue();
                var pending =
                    executor.submit(
                        () ->
                            transaction.execute(
                                competingStatus -> {
                                  contenderPid.set(backendPid(dsl));
                                  entered.countDown();
                                  return contender.getAsBoolean();
                                }));
                assertBlockingSession(dsl, entered, ownerPid, contenderPid);
                return pending;
              });
      assertThat(Objects.requireNonNull(loser).get(10, TimeUnit.SECONDS)).isFalse();
    }
  }

  private static int backendPid(DSLContext dsl) {
    return dsl.fetchSingle("SELECT pg_backend_pid()").get(0, int.class);
  }

  private static void assertBlockingSession(
      DSLContext dsl, CountDownLatch entered, int ownerPid, AtomicInteger contenderPid) {
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        if (dsl.fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", ownerPid, contenderPid.get())
            .get(0, boolean.class)) {
          return;
        }
        TimeUnit.MILLISECONDS.sleep(10);
      }
      throw new AssertionError("Contender never waited on the original PostgreSQL transaction");
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while observing World epoch contention", interruption);
    }
  }
}
