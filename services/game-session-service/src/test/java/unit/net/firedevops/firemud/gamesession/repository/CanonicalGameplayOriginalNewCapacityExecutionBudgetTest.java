package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariProxyConnection;
import io.grpc.Context;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import org.awaitility.Awaitility;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.jdbc.PgConnection;
import org.springframework.test.util.ReflectionTestUtils;

/** Controlled acquisition/driver behavior, not database or owner-evidence qualification. */
class CanonicalGameplayOriginalNewCapacityExecutionBudgetTest {
  private static final Clock CLOCK = Clock.systemUTC();

  @AfterEach
  void awaitResourceAndCancellationWorkerCleanup() throws InterruptedException {
    Semaphore permits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    // A mocked provider release is observed before its caller drains connection custody and
    // restores admission. Wait for the real worker/cancellation lifecycle, including on failure.
    assertThat(permits.tryAcquire(maximum, 2, TimeUnit.SECONDS))
        .as("resource and cancellation workers restore all operation permits")
        .isTrue();
    try {
      assertThat(permits.availablePermits())
          .as("operation teardown has exactly the configured permits")
          .isZero();
    } finally {
      permits.release(maximum);
    }
    Semaphore background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int maximumBackground =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;
    assertThat(background.tryAcquire(maximumBackground, 2, TimeUnit.SECONDS))
        .as("background admission follows the same cleanup lifecycle")
        .isTrue();
    try {
      assertThat(background.availablePermits())
          .as("background teardown has exactly the configured permits")
          .isZero();
    } finally {
      background.release(maximumBackground);
    }
  }

  @Test
  void rollbackRetiresCapturedDriverWhenThePoolProxyLosesItsDelegate() throws SQLException {
    var dataSource = mock(HikariDataSource.class);
    var pooled = mock(HikariProxyConnection.class);
    var driver = mock(PgConnection.class);
    var delegateAvailable = new AtomicBoolean(true);
    when(dataSource.getConnection()).thenReturn(pooled);
    when(pooled.unwrap(PgConnection.class))
        .thenAnswer(invocation -> {
          if (!delegateAvailable.get()) throw new SQLException("delegate no longer available");
          return driver;
        });
    doAnswer(
            invocation -> {
              delegateAvailable.set(false);
              throw new SQLException("controlled rollback failure");
            })
        .when(pooled)
        .rollback();
    when(driver.isClosed()).thenReturn(false, true);
    DSLContext source =
        DSL.using(new DataSourceConnectionProvider(dataSource), SQLDialect.POSTGRES);

    CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
        CLOCK,
        Long.MAX_VALUE,
        budget -> {
          var provider = budget.sql(source).configuration().connectionProvider();
          Connection borrowed = provider.acquire();
          try {
            assertThatThrownBy(borrowed::rollback)
                .isInstanceOf(SQLException.class)
                .hasMessage("controlled rollback failure");
          } finally {
            provider.release(borrowed);
          }
          return null;
        });

    var retirement = inOrder(pooled, driver, dataSource);
    retirement.verify(pooled).unwrap(PgConnection.class);
    retirement.verify(pooled).rollback();
    retirement.verify(driver).abort(any());
    retirement.verify(driver).isClosed();
    retirement.verify(dataSource).evictConnection(pooled);
    retirement.verify(pooled).close();
  }

  @Test
  void ownedResultDisposesExactlyOnceAfterCallerCancellation() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var disposed = new CountDownLatch(1);
    var value = new Object();
    var disposalCount = new AtomicInteger();
    try (var caller = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result =
          CompletableFuture.supplyAsync(
              () -> {
                var previous = caller.attach();
                try {
                  return CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
                      CLOCK,
                      Long.MAX_VALUE,
                      budget -> {
                        started.countDown();
                        awaitUninterruptibly(release);
                        return value;
                      },
                      abandoned -> {
                        if (abandoned == value) disposalCount.incrementAndGet();
                        disposed.countDown();
                      });
                } finally {
                  caller.detach(previous);
                }
              },
              executor);
      try {
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        caller.cancel(new IllegalStateException("controlled caller cancellation"));
        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
            .hasCauseInstanceOf(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
        assertThat(disposalCount.get()).isZero();
      } finally {
        release.countDown();
      }
      assertThat(disposed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(disposalCount.get()).isEqualTo(1);
    }
    Object returned =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
            CLOCK, Long.MAX_VALUE, budget -> value, ignored -> disposalCount.incrementAndGet());
    assertThat(returned).isSameAs(value);
    assertThat(disposalCount.get()).isEqualTo(1);
  }

  @Test
  void backgroundPhysicalCompletionIncludesDelayedProviderReturn() throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    when(provider.acquire()).thenReturn(connection);
    var releaseEntered = new CountDownLatch(1);
    var providerMayReturn = new CountDownLatch(1);
    var physicallyReleased = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              releaseEntered.countDown();
              awaitUninterruptibly(providerMayReturn);
              return null;
            })
        .when(provider)
        .release(connection);
    var dsl = DSL.using(new DefaultConfiguration().set(provider).set(SQLDialect.POSTGRES));
    try (var caller = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var outcome =
          CompletableFuture.supplyAsync(
              () -> {
                var previous = caller.attach();
                try {
                  return CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                      CLOCK,
                      Long.MAX_VALUE,
                      budget -> {
                        var scoped = budget.sql(dsl);
                        var scopedProvider = scoped.configuration().connectionProvider();
                        var acquired = scopedProvider.acquire();
                        scopedProvider.release(acquired);
                        return true;
                      },
                      physicallyReleased::countDown);
                } finally {
                  caller.detach(previous);
                }
              },
              executor);
      try {
        assertThat(releaseEntered.await(2, TimeUnit.SECONDS)).isTrue();
        caller.cancel(new IllegalStateException("controlled caller cancellation"));
        assertThatThrownBy(() -> outcome.get(2, TimeUnit.SECONDS))
            .hasCauseInstanceOf(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
        assertThat(physicallyReleased.getCount()).isEqualTo(1);
      } finally {
        providerMayReturn.countDown();
      }
      assertThat(physicallyReleased.await(2, TimeUnit.SECONDS)).isTrue();
      verify(provider).release(connection);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void transferReadbackRetainsAdmissionUntilAsyncRedisCloseCompletes(boolean cancelCaller)
      throws Exception {
    RedisClient redis = mock();
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    ClientOptions options = ClientOptions.builder().autoReconnect(false).build();
    RedisAsyncCommands<byte[], byte[]> commands = mock();
    RedisFuture<String> principal = mock();
    CompletableFuture<Void> close = new CompletableFuture<>();
    when(redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(options);
    when(connection.async()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(principal);
    when(principal.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn("gamesession_coord_app");
    var closeStarted = new CountDownLatch(1);
    when(connection.closeAsync())
        .thenAnswer(invocation -> {
          closeStarted.countDown();
          return close;
        });
    DSLContext dsl = source(mock());
    var owner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            dsl,
            redis,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution())),
            CLOCK,
            new CanonicalGameplayTenantGameplayTagRepository(dsl, "prod", 1));
    CountDownLatch readbackReturned = new CountDownLatch(1);
    Semaphore operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int operationMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    Semaphore background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;

    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(() ->
              context.call(() ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                      CLOCK,
                      CLOCK.millis() + 4000,
                      budget -> {
                        owner
                            .forTransferBudget(budget)
                            .withTransferReadback(ignored -> readbackReturned.countDown());
                        return true;
                      })));
      try {
        assertThat(readbackReturned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(closeStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(close).isNotDone();
        assertThat(caller.isDone()).isFalse();
        assertThat(operations.availablePermits()).isEqualTo(operationMaximum - 1);
        assertThat(background.availablePermits()).isEqualTo(backgroundMaximum - 1);

        if (cancelCaller) {
          context.cancel(null);
          assertThatThrownBy(() -> caller.get(2, TimeUnit.SECONDS))
              .hasCauseInstanceOf(
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
          assertThat(close).isNotDone();
          assertThat(operations.availablePermits()).isEqualTo(operationMaximum - 1);
          assertThat(background.availablePermits()).isEqualTo(backgroundMaximum - 1);
        }
        assertThat(close.complete(null)).isTrue();
        if (!cancelCaller) assertThat(caller.get(2, TimeUnit.SECONDS)).isTrue();
        Awaitility.await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
              assertThat(operations.availablePermits()).isEqualTo(operationMaximum);
              assertThat(background.availablePermits()).isEqualTo(backgroundMaximum);
            });
      } finally {
        close.complete(null);
      }
    }
    verify(connection).closeAsync();
  }

  @Test
  void actualOwnerReadReturnsAtDeadlineAndLateAcquisitionNeverExecutes() throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    RedisClient redis = mock();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    DSLContext dsl = source(provider);
    var owner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            dsl,
            redis,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution())),
            CLOCK,
            new CanonicalGameplayTenantGameplayTagRepository(dsl, "prod", 1));
    var request = CapacityAdmissionTestFixture.playerRequest();
    var reservation =
        new TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.randomUUID(),
            1,
            request.evidence().expiresAt(),
            TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    long started = System.nanoTime();
    try (var caller =
        new WatchdogCaller(
            CompletableFuture.runAsync(() ->
                assertThatThrownBy(() ->
                        Context.current()
                            .withDeadlineAfter(2, TimeUnit.SECONDS, scheduler)
                            .call(() -> owner.readExact(request, reservation)))
                    .isInstanceOf(IllegalStateException.class)),
            release::countDown)) {
      caller.result().get(3500, TimeUnit.MILLISECONDS);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3500);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      Semaphore background =
          (Semaphore)
              java.util.Objects.requireNonNull(
                  ReflectionTestUtils.getField(
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                      "BACKGROUND_OPERATIONS"));
      assertThat(background.availablePermits())
          .isEqualTo(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS - 1);
      verifyNoInteractions(connection, redis);
      release.countDown();
      verify(provider, timeout(2000)).release(connection);
      verifyNoInteractions(connection, redis);
      assertThat(dsl.configuration().connectionProvider())
          .isInstanceOf(ControlledConnectionProvider.class);
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void activeStatementCancellationFinishesBeforePoolReturn() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    CountDownLatch executing = new CountDownLatch(1), finishExecution = new CountDownLatch(1);
    CountDownLatch cancelling = new CountDownLatch(1), finishCancellation = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1), released = new CountDownLatch(1);
    AtomicBoolean returned = new AtomicBoolean();
    AtomicBoolean orderingViolation = new AtomicBoolean();
    AtomicReference<Thread> executionThread = new AtomicReference<>();
    when(statement.execute())
        .thenAnswer(invocation -> {
          executionThread.set(Thread.currentThread());
          executing.countDown();
          awaitUninterruptibly(finishExecution);
          return true;
        });
    doAnswer(
            invocation -> {
              if (returned.get()) orderingViolation.set(true);
              cancelling.countDown();
              awaitUninterruptibly(finishCancellation);
              if (returned.get()) orderingViolation.set(true);
              cancelled.countDown();
              return null;
            })
        .when(statement)
        .cancel();
    doAnswer(
            invocation -> {
              returned.set(true);
              if (cancelled.getCount() != 0) orderingViolation.set(true);
              released.countDown();
              return null;
            })
        .when(provider)
        .release(connection);
    // Initialize the fixture DSL before starting the execution/cancellation budget.
    DSLContext dsl = source(provider);
    long started = System.nanoTime();
    try (var caller =
        new WatchdogCaller(
            CompletableFuture.runAsync(() ->
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK, CLOCK.millis() + 2000, budget -> execute(budget.sql(dsl))))
                    .isInstanceOf(IllegalStateException.class)),
            () -> {
              finishExecution.countDown();
              finishCancellation.countDown();
            })) {
      caller.result().get(3500, TimeUnit.MILLISECONDS);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3500);
      assertThat(executing.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(cancelling.await(1, TimeUnit.SECONDS)).isTrue();
      finishExecution.countDown();
      org.awaitility.Awaitility.await()
          .atMost(2, TimeUnit.SECONDS)
          .untilAsserted(() -> {
            Thread worker = executionThread.get();
            assertThat(worker).isNotNull();
            assertThat(worker.getState()).isEqualTo(Thread.State.BLOCKED);
            assertThat(
                    java.util.Arrays.stream(worker.getStackTrace())
                        .anyMatch(frame ->
                            frame.getClassName().contains("$OwnedConnection")
                                && (frame.getMethodName().startsWith("lambda$statement$")
                                    || frame.getMethodName().equals("release"))))
                .isTrue();
          });
      verify(provider, never()).release(connection);
      assertThat(finishCancellation.getCount())
          .as("cancellation still owns the connection custody monitor")
          .isEqualTo(1);
      finishCancellation.countDown();
      assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(orderingViolation).isFalse();
      verify(provider, timeout(2000)).release(connection);
      verify(statement).cancel();
      verify(statement).setQueryTimeout(anyInt());
    }
  }

  @Test
  void cancellationRetiresBeforeDelayedDriverWorkResumes() throws Exception {
    ConnectionProvider delegate = mock();
    var provider = new ControlledConnectionProvider(delegate);
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(delegate.acquire()).thenReturn(connection);
    CountDownLatch insideDriver = new CountDownLatch(1), resumeDriver = new CountDownLatch(1);
    when(statement.execute())
        .thenAnswer(invocation -> {
          insideDriver.countDown();
          awaitUninterruptibly(resumeDriver);
          if (provider.retired(connection)) throw new java.sql.SQLException("retired connection");
          return true;
        });
    DSLContext dsl = source(provider);
    Semaphore permits = operationPermits();
    int availableBefore = permits.availablePermits();
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() ->
                  assertThatThrownBy(() ->
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                              CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(dsl))))
                      .isInstanceOf(
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable
                              .class)));
      try {
        assertThat(insideDriver.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
        assertThat(provider.retirementAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(provider.retired(connection)).isTrue();
        assertThat(permits.availablePermits()).isEqualTo(availableBefore - 1);
        verify(delegate, never()).release(connection);

        resumeDriver.countDown();
        verify(delegate, timeout(2000)).release(connection);
        assertThat(
                permits.tryAcquire(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS,
                    2,
                    TimeUnit.SECONDS))
            .isTrue();
        permits.release(
            CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS);
      } finally {
        resumeDriver.countDown();
      }
    }
  }

  @Test
  void failedConnectionRetirementKeepsConnectionAndOperationPermit() throws Exception {
    ConnectionProvider delegate = mock();
    var provider = new ControlledConnectionProvider(delegate);
    provider.failRetirement = true;
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(delegate.acquire()).thenReturn(connection);
    CountDownLatch insideDriver = new CountDownLatch(1), resumeDriver = new CountDownLatch(1);
    when(statement.execute())
        .thenAnswer(invocation -> {
          insideDriver.countDown();
          awaitUninterruptibly(resumeDriver);
          return true;
        });
    DSLContext dsl = source(provider);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    Semaphore permits = operationPermits();
    AtomicInteger retained =
        (AtomicInteger)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "RETIRE_FAILURE_RETAINED_OPERATIONS"));
    int beforeRetained = retained.get();
    int availableBefore = permits.availablePermits();
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() ->
                  assertThatThrownBy(() ->
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                              CLOCK,
                              Long.MAX_VALUE,
                              budget -> {
                                captured.set(budget);
                                return execute(budget.sql(dsl));
                              }))
                      .isInstanceOf(
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable
                              .class)));
      try {
        assertThat(insideDriver.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
        assertThat(provider.retirementAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(provider.retired(connection)).isFalse();
        resumeDriver.countDown();
        var budget = captured.get();
        org.awaitility.Awaitility.await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() ->
                assertThat(
                        java.util.Objects.requireNonNull(
                            ReflectionTestUtils.getField(budget, "workerDone")))
                    .isEqualTo(true));
        assertThat(permits.availablePermits()).isEqualTo(availableBefore - 1);
        assertThat(retained.get()).isEqualTo(beforeRetained + 1);
        assertThat(
                (AtomicInteger)
                    java.util.Objects.requireNonNull(
                        ReflectionTestUtils.getField(budget, "outstandingConnections")))
            .hasValue(1);
        assertThat(
                java.util.Objects.requireNonNull(ReflectionTestUtils.getField(budget, "released")))
            .isEqualTo(false);
        verify(delegate, never()).release(connection);
      } finally {
        resumeDriver.countDown();
        var budget = captured.get();
        if (budget != null) {
          Object lifecycle =
              java.util.Objects.requireNonNull(ReflectionTestUtils.getField(budget, "lifecycle"));
          org.awaitility.Awaitility.await()
              .atMost(2, TimeUnit.SECONDS)
              .until(() -> {
                synchronized (lifecycle) {
                  return Boolean.TRUE.equals(ReflectionTestUtils.getField(budget, "workerDone"))
                      && (!Boolean.TRUE.equals(
                              ReflectionTestUtils.getField(budget, "cancellationStarted"))
                          || Boolean.TRUE.equals(
                              ReflectionTestUtils.getField(budget, "cancellationDone")));
                }
              });
          synchronized (lifecycle) {
            synchronized (
                java.util.Objects.requireNonNull(
                    ReflectionTestUtils.getField(budget, "resources"))) {
              var connections =
                  (java.util.Map<?, ?>)
                      java.util.Objects.requireNonNull(
                          ReflectionTestUtils.getField(budget, "connections"));
              var outstanding =
                  (AtomicInteger)
                      java.util.Objects.requireNonNull(
                          ReflectionTestUtils.getField(budget, "outstandingConnections"));
              if (Boolean.FALSE.equals(ReflectionTestUtils.getField(budget, "released"))
                  && connections.size() == 1
                  && outstanding.get() == 1) {
                // This operation owns only the one disposable mock connection.
                connections.clear();
                outstanding.set(0);
                if (retained.get() == beforeRetained + 1) retained.decrementAndGet();
                ReflectionTestUtils.invokeMethod(budget, "releaseIfFinished");
              }
            }
          }
        }
      }
    }
  }

  @Test
  void uncertainProviderReleaseRetainsOnePermitAndExposesItThroughMicrometer() throws Exception {
    ConnectionProvider provider = mock();
    Connection first = mock(), second = mock();
    when(provider.acquire()).thenReturn(first, second);
    doThrow(new IllegalStateException("Controlled uncertain pool return"))
        .when(provider)
        .release(org.mockito.ArgumentMatchers.any(Connection.class));
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    var registry = new SimpleMeterRegistry();
    try {
      Metrics.addRegistry(registry);
      AtomicInteger retained =
          (AtomicInteger)
              ReflectionTestUtils.getField(
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                  "RELEASE_FAILURE_RETAINED_OPERATIONS");
      Semaphore permits =
          (Semaphore)
              ReflectionTestUtils.getField(
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS");
      assertThat(retained).isNotNull();
      assertThat(permits).isNotNull();
      Semaphore background =
          (Semaphore)
              java.util.Objects.requireNonNull(
                  ReflectionTestUtils.getField(
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                      "BACKGROUND_OPERATIONS"));
      int backgroundAvailable = background.availablePermits();
      int before = retained.get();
      int available = permits.availablePermits();
      var rollbackFailure = new AtomicReference<Throwable>();
      var autoCommitFailure = new AtomicReference<Throwable>();
      var logicalFinishes = new AtomicInteger();
      try {
        assertThatThrownBy(() ->
                CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackgroundCleanup(
                    CLOCK,
                    Long.MAX_VALUE,
                    null,
                    budget -> {
                      captured.set(budget);
                      var scoped =
                          budget.sql(source(provider)).configuration().connectionProvider();
                      Connection proxy = java.util.Objects.requireNonNull(scoped.acquire());
                      scoped.acquire();
                      assertThatThrownBy(() -> scoped.release(proxy))
                          .hasMessage("Controlled uncertain pool return");
                      rollbackFailure.set(
                          org.assertj.core.api.Assertions.catchThrowable(proxy::rollback));
                      autoCommitFailure.set(
                          org.assertj.core.api.Assertions.catchThrowable(
                              () -> proxy.setAutoCommit(true)));
                      return true;
                    },
                    logicalFinishes::incrementAndGet))
            .isInstanceOf(IllegalStateException.class);
        assertThat(logicalFinishes).hasValue(1);
        assertThat(rollbackFailure.get()).isInstanceOf(java.sql.SQLException.class);
        assertThat(autoCommitFailure.get()).isInstanceOf(java.sql.SQLException.class);
        assertThat(
                registry
                    .get("original_new_capacity_sql_release_failure_retained_operations")
                    .gauge()
                    .value())
            .isEqualTo(before + 1);
        assertThat(permits.availablePermits()).isEqualTo(available - 1);
        assertThat(background.availablePermits()).isEqualTo(backgroundAvailable - 1);
        assertThat(ReflectionTestUtils.getField(captured.get(), "released")).isEqualTo(false);
        assertThat(
                (AtomicInteger)
                    ReflectionTestUtils.getField(captured.get(), "outstandingConnections"))
            .hasValue(2);
        verify(first, never()).rollback();
        verify(first, never()).setAutoCommit(true);
        verify(provider).release(first);
        verify(provider).release(second);
      } finally {
        // Only mocked resources exist here. Restore JVM-wide fixture state after proving that
        // production code neither retries an uncertain return nor releases its retained permit.
        if (retained.get() == before + 1) {
          retained.decrementAndGet();
        }
        if (permits.availablePermits() == available - 1) permits.release();
        if (background.availablePermits() == backgroundAvailable - 1) background.release();
      }
    } finally {
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void exceptionalOrCancelledRedisDisposalRetainsTheBudgetAndReportsIt() {
    var registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
    var retained =
        (AtomicInteger)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "REDIS_CLOSE_FAILURE_RETAINED_OPERATIONS"));
    var operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    var background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int priorOperations = operations.availablePermits();
    int priorBackground = background.availablePermits();
    int priorRetained = retained.get();
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.class);
    var events =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    CompletableFuture<Void> late = new CompletableFuture<>();
    CompletableFuture<Void> cancelled = new CompletableFuture<>();
    var operationsEntered = new AtomicInteger();
    try {
      assertThatThrownBy(() ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                  CLOCK,
                  Long.MAX_VALUE,
                  budget -> {
                    operationsEntered.incrementAndGet();
                    budget.awaitAsyncClose(
                        CompletableFuture.failedFuture(
                            new IllegalStateException(
                                "fixture diagnostic marker must not be emitted")));
                    return true;
                  }))
          .isExactlyInstanceOf(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
      CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
          CLOCK,
          Long.MAX_VALUE,
          budget -> {
            budget.retainUntilAsyncClose(cancelled);
            budget.retainUntilAsyncClose(late);
            operationsEntered.incrementAndGet();
            return true;
          });
      assertThat(events.list).hasSize(1);
      assertThat(operationsEntered.get()).isEqualTo(2);
      assertThat(cancelled.cancel(false)).isTrue();
      assertThat(late.completeExceptionally(new IllegalStateException("second close failure")))
          .isTrue();
      assertThat(events.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .containsExactly(
              "Original NEW Redis close failed; operation permit retained (category=exception)",
              "Original NEW Redis close failed; operation permit retained (category=cancelled)");
      assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
      assertThat(operations.availablePermits()).isEqualTo(priorOperations - 2);
      assertThat(background.availablePermits()).isEqualTo(priorBackground - 2);
      assertThat(
              registry
                  .get("original_new_capacity_redis_close_failure_retained_operations")
                  .gauge()
                  .value())
          .isEqualTo(priorRetained + 2);
    } finally {
      late.complete(null);
      cancelled.complete(null);
      logger.detachAppender(events);
      events.stop();
      // Both runBackground calls have returned, so their resource workers have finished; neither
      // operation acquired SQL resources or started a cancellation worker. The terminal failed
      // and cancelled close futures therefore retain exactly the admitted operation count. Undo
      // only that test-created retained state after proving it still exists.
      int ownedOperations = operationsEntered.get();
      int retainedOperationPermits = priorOperations - operations.availablePermits();
      int retainedBackgroundPermits = priorBackground - background.availablePermits();
      if (retainedOperationPermits >= 0 && retainedOperationPermits <= ownedOperations) {
        operations.release(retainedOperationPermits);
      }
      if (retainedBackgroundPermits >= 0 && retainedBackgroundPermits <= ownedOperations) {
        background.release(retainedBackgroundPermits);
      }
      if (retained.get() == priorRetained + ownedOperations) {
        retained.addAndGet(-ownedOperations);
      }
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void timedOutRedisDisposalRetainsAndThenReleasesTheBudgetOnce() {
    var registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
    var retained =
        (AtomicInteger)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "REDIS_CLOSE_FAILURE_RETAINED_OPERATIONS"));
    var operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    var background =
        (Semaphore)
            (java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS")));
    int priorOperations = operations.availablePermits();
    int priorBackground = background.availablePermits();
    int priorRetained = retained.get();
    var close = new CompletableFuture<Void>();
    try {
      assertThatThrownBy(() ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                  CLOCK,
                  CLOCK.millis() + 1_000,
                  budget -> {
                    budget.awaitAsyncClose(close);
                    return true;
                  }))
          .isExactlyInstanceOf(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);

      var gauge =
          registry.get("original_new_capacity_redis_close_failure_retained_operations").gauge();
      Awaitility.await()
          .atMost(2, TimeUnit.SECONDS)
          .untilAsserted(() -> {
            assertThat(retained).hasValue(priorRetained + 1);
            assertThat(gauge.value()).isEqualTo(priorRetained + 1);
            assertThat(close).isNotDone();
            assertThat(operations.availablePermits()).isEqualTo(priorOperations - 1);
            assertThat(background.availablePermits()).isEqualTo(priorBackground - 1);
          });

      assertThat(close.complete(null)).isTrue();
      Awaitility.await()
          .atMost(2, TimeUnit.SECONDS)
          .untilAsserted(() -> {
            assertThat(operations.availablePermits()).isEqualTo(priorOperations);
            assertThat(background.availablePermits()).isEqualTo(priorBackground);
            assertThat(retained).hasValue(priorRetained);
            assertThat(gauge.value()).isEqualTo(priorRetained);
          });
    } finally {
      close.complete(null);
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void cancellationAfterPoolReturnCannotCancelAReusedConnectionStatement() throws Exception {
    ConnectionProvider delegate = mock();
    var provider = new ControlledConnectionProvider(delegate);
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(delegate.acquire()).thenReturn(connection);
    when(statement.execute()).thenReturn(true);
    CountDownLatch returned = new CountDownLatch(1), finish = new CountDownLatch(1);
    CountDownLatch cancellationWorkerFinished = new CountDownLatch(1);
    DSLContext dsl = source(provider);
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() -> {
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK,
                            Long.MAX_VALUE,
                            budget -> {
                              execute(budget.sql(dsl));
                              // Execute returns only after the scoped provider returned the
                              // connection; cancellation below must happen after that custody
                              // transition, while the operation remains active at finish.
                              returned.countDown();
                              awaitUninterruptibly(finish);
                              return true;
                            },
                            cancellation ->
                                Thread.ofVirtual()
                                    .name("test-original-new-capacity-cancel")
                                    .unstarted(() -> {
                                      try {
                                        cancellation.run();
                                      } finally {
                                        cancellationWorkerFinished.countDown();
                                      }
                                    })))
                    .isInstanceOf(IllegalStateException.class);
              }));
      try {
        assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
      } finally {
        finish.countDown();
      }
    }
    assertThat(cancellationWorkerFinished.await(2, TimeUnit.SECONDS)).isTrue();
    awaitResourceAndCancellationWorkerCleanup();
    verify(statement, never()).cancel();
    assertThat(provider.retirementAttempted.getCount())
        .as("a returned connection is no longer in cancellation custody")
        .isEqualTo(1);
    assertThat(provider.retired(connection)).isFalse();
  }

  @Test
  void blockedAcquisitionsKeepAllFourSlotsAfterCallerTimeoutAndRejectWithoutQueueing()
      throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    CountDownLatch entered = new CountDownLatch(maximum), release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    DSLContext source = source(provider);
    var callers = new ArrayList<CompletableFuture<Void>>();
    var executor = Executors.newFixedThreadPool(maximum);
    try {
      for (int index = 0; index < maximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(() ->
                            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                                CLOCK,
                                CLOCK.millis() + 3000,
                                budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (var caller : callers) assertThat(caller.isDone()).isFalse();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
      assertPromptBudgetRefusal(
          executor,
          () ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                  CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(source))));
      verify(provider, org.mockito.Mockito.times(maximum)).acquire();
      verifyNoInteractions(connection);
    } finally {
      release.countDown();
      executor.shutdownNow();
      verify(provider, timeout(2000).times(maximum)).release(connection);
      awaitResourceAndCancellationWorkerCleanup();
    }
  }

  @Test
  void backgroundSaturationReservesAdmissionCapacityWithoutExceedingCombinedFourSlots()
      throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    CountDownLatch backgroundEntered = new CountDownLatch(backgroundMaximum);
    CountDownLatch allEntered = new CountDownLatch(maximum);
    CountDownLatch release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          backgroundEntered.countDown();
          allEntered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    DSLContext source = source(provider);
    var callers = new ArrayList<CompletableFuture<Void>>();
    var executor = Executors.newFixedThreadPool(maximum);
    try {
      for (int index = 0; index < backgroundMaximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(() ->
                            CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                                CLOCK,
                                CLOCK.millis() + 1000,
                                budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(backgroundEntered.await(2, TimeUnit.SECONDS)).isTrue();
      var refused = new AtomicBoolean();
      assertPromptBudgetRefusal(
          executor,
          () ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                  CLOCK,
                  Long.MAX_VALUE,
                  budget -> {
                    refused.set(true);
                    return true;
                  }));
      assertThat(refused).isFalse();
      boolean admitted =
          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
              CLOCK, Long.MAX_VALUE, budget -> Boolean.TRUE);
      assertThat(admitted).isTrue();
      for (int index = backgroundMaximum; index < maximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(() ->
                            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                                CLOCK,
                                CLOCK.millis() + 1000,
                                budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(allEntered.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
      // Callers timed out, but blocked acquisition still owns all four permits.
      assertPromptBudgetRefusal(
          executor,
          () ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                  CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(source))));
      verify(provider, org.mockito.Mockito.times(maximum)).acquire();
      verifyNoInteractions(connection);
    } finally {
      release.countDown();
      executor.shutdownNow();
      verify(provider, timeout(2000).times(maximum)).release(connection);
    }
  }

  private static void assertPromptBudgetRefusal(ExecutorService executor, Runnable extra)
      throws Exception {
    // The holders stay blocked. The independent watchdog is shorter than the five-second
    // operation budget, so an operation that queues and times out cannot satisfy this assertion.
    var refusal =
        executor.submit(() ->
            assertThatThrownBy(extra::run)
                .isInstanceOf(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class));
    try {
      refusal.get(2, TimeUnit.SECONDS);
    } finally {
      refusal.cancel(true);
    }
  }

  @Test
  void explicitDeadlineIsOneBudgetAcrossQueriesWithoutGrpcContext() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    AtomicInteger executions = new AtomicInteger(), queryTimeout = new AtomicInteger();
    AtomicInteger secondTimeout = new AtomicInteger();
    AtomicLong wallMillis = new AtomicLong(CLOCK.millis());
    Clock clock = mock();
    when(clock.millis()).thenAnswer(ignored -> wallMillis.get());
    CountDownLatch second = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              queryTimeout.set(invocation.getArgument(0));
              return null;
            })
        .when(statement)
        .setQueryTimeout(anyInt());
    when(statement.execute())
        .thenAnswer(invocation -> {
          if (executions.incrementAndGet() == 1) {
            wallMillis.addAndGet(1500L);
          } else {
            secondTimeout.set(queryTimeout.get());
            second.countDown();
            awaitUninterruptibly(release);
          }
          return true;
        });
    DSLContext dsl = source(provider);
    try (var caller =
        new WatchdogCaller(
            CompletableFuture.runAsync(() ->
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            clock,
                            clock.millis() + 2000,
                            budget -> {
                              var sql = budget.sql(dsl);
                              execute(sql);
                              return execute(sql);
                            }))
                    .isInstanceOf(IllegalStateException.class)),
            release::countDown)) {
      caller.result().get(3500, TimeUnit.MILLISECONDS);
      assertThat(second.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(secondTimeout)
          .as("second query receives at most the remaining 500 ms")
          .hasValue(1);
      verify(statement, timeout(1000)).cancel();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void secondQueryCannotRenewAnAlreadyConsumedAbsoluteDeadline(boolean exhaustFirstQuery)
      throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    when(provider.acquire()).thenReturn(preparedConnection(statement));
    AtomicInteger executions = new AtomicInteger();
    AtomicLong wallMillis = new AtomicLong(CLOCK.millis());
    Clock clock = mock();
    when(clock.millis()).thenAnswer(ignored -> wallMillis.get());
    long absoluteDeadline = clock.millis() + 5_000L;
    when(statement.execute())
        .thenAnswer(invocation -> {
          if (executions.incrementAndGet() == 1 && exhaustFirstQuery) {
            wallMillis.set(absoluteDeadline + 1L);
          }
          return true;
        });
    DSLContext dsl = source(provider);
    Runnable bothQueries =
        () ->
            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                clock,
                absoluteDeadline,
                budget -> {
                  var sql = budget.sql(dsl);
                  execute(sql);
                  return execute(sql);
                });
    if (exhaustFirstQuery) {
      assertThatThrownBy(bothQueries::run)
          .isInstanceOf(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
      assertThat(executions).hasValue(1);
    } else {
      bothQueries.run();
      assertThat(executions).hasValue(2);
    }
  }

  @Test
  void expiredExplicitBudgetDoesNotAcquireAndTagRebindingPreservesPrivateConfiguration() {
    ConnectionProvider provider = mock();
    DSLContext original = source(provider);
    DSLContext operation = mock();
    var tags = new CanonicalGameplayTenantGameplayTagRepository(original, "capacity-test", 7);
    var rebound = tags.withCapacityExecutionDsl(operation);
    assertThat(ReflectionTestUtils.getField(rebound, "dsl")).isSameAs(operation);
    assertThat(ReflectionTestUtils.getField(rebound, "workloadNamespace"))
        .isEqualTo("capacity-test");
    assertThat(ReflectionTestUtils.getField(rebound, "allocationVersion")).isEqualTo(7);
    assertThat(ReflectionTestUtils.getField(tags, "dsl")).isSameAs(original);
    assertThatThrownBy(() ->
            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                CLOCK, CLOCK.millis() - 1, budget -> execute(budget.sql(original))))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(provider, operation);
  }

  @Test
  void cancellationWorkerStartupFailureRetainsOnlyActualOutstandingResources() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    when(statement.execute())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(finish);
          return true;
        });
    DSLContext dsl = source(provider);
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() -> {
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK,
                            Long.MAX_VALUE,
                            budget -> {
                              captured.set(budget);
                              return execute(budget.sql(dsl));
                            },
                            ignored -> {
                              throw new java.util.concurrent.RejectedExecutionException(
                                  "Controlled startup refusal");
                            }))
                    .isInstanceOf(IllegalStateException.class);
              }));
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
        verify(statement, never()).cancel();
        verify(provider, never()).release(connection);
        assertThat(ReflectionTestUtils.getField(captured.get(), "cancellationDone"))
            .isEqualTo(true);
        finish.countDown();
        verify(provider, timeout(2000)).release(connection);
        awaitResourceAndCancellationWorkerCleanup();
        assertNoOwnedConnections(captured.get());
        for (int index = 0;
            index
                < 2 * CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
            index++) {
          boolean admitted =
              CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                  CLOCK, Long.MAX_VALUE, budget -> true);
          assertThat(admitted).isTrue();
        }
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  void exceptionalCallbackDrainsAnAcquiredConnectionBeforeReleasingItsResourcePermit() {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    when(provider.acquire()).thenReturn(connection);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    assertThatThrownBy(() ->
            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                CLOCK,
                Long.MAX_VALUE,
                budget -> {
                  captured.set(budget);
                  budget.sql(source(provider)).configuration().connectionProvider().acquire();
                  throw new IllegalStateException(
                      "Controlled exception before ordinary provider release");
                }))
        .isInstanceOf(IllegalStateException.class);
    verify(provider).release(connection);
    assertNoOwnedConnections(captured.get());
  }

  @Test
  void absoluteDeadlineStaysFrozenAcrossSqlWorkAndWallClockPassage() throws Exception {
    Clock clock = mock();
    AtomicLong now = new AtomicLong(1_800_000_000_000L);
    when(clock.millis()).thenAnswer(ignored -> now.get());
    long originalDeadline = now.get() + 4000;
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    when(provider.acquire()).thenReturn(preparedConnection(statement));
    when(statement.execute())
        .thenAnswer(ignored -> {
          now.addAndGet(1000);
          return true;
        });
    DSLContext dsl = source(provider);

    long retainedDeadline =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
            clock,
            originalDeadline,
            budget -> {
              assertThat(budget.absoluteDeadlineMillis()).isEqualTo(originalDeadline);
              execute(budget.sql(dsl));
              assertThat(budget.remainingMillis()).isBetween(1L, 3000L);
              return budget.absoluteDeadlineMillis();
            });

    assertThat(retainedDeadline).isEqualTo(originalDeadline);
    assertThat(now).hasValue(originalDeadline - 3000);
    verify(statement).execute();
  }

  @Test
  void defaultFiveSecondAbsoluteDeadlineDoesNotRenewAfterClockPassage() {
    Clock clock = mock();
    AtomicLong now = new AtomicLong(1_800_000_000_000L);
    when(clock.millis()).thenAnswer(ignored -> now.get());
    long initial = now.get();

    long retainedDeadline =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
            clock,
            Long.MAX_VALUE,
            budget -> {
              long deadline = budget.absoluteDeadlineMillis();
              now.addAndGet(1000);
              assertThat(budget.absoluteDeadlineMillis()).isEqualTo(deadline);
              return deadline;
            });

    assertThat(retainedDeadline).isEqualTo(initial + 5_000L);
  }

  @Test
  void incomingGrpcDeadlineCapsTheFrozenAbsoluteDeadline() throws Exception {
    Clock clock =
        Clock.fixed(java.time.Instant.ofEpochMilli(1_800_000_000_000L), java.time.ZoneOffset.UTC);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      long retainedDeadline =
          Context.current()
              .withDeadlineAfter(2, TimeUnit.SECONDS, scheduler)
              .call(() ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      clock, clock.millis() + 4000, budget -> budget.absoluteDeadlineMillis()));
      assertThat(retainedDeadline).isBetween(clock.millis() + 1, clock.millis() + 2000);
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static void assertNoOwnedConnections(
      CanonicalGameplayOriginalNewCapacityExecutionBudget budget) {
    Object monitor =
        java.util.Objects.requireNonNull(ReflectionTestUtils.getField(budget, "resources"));
    synchronized (monitor) {
      assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(budget, "connections"))
          .isEmpty();
      assertThat((AtomicInteger) ReflectionTestUtils.getField(budget, "outstandingConnections"))
          .hasValue(0);
    }
  }

  /** A controlled JDBC answer, without invoking a resource-creating method during stubbing. */
  private static Connection preparedConnection(PreparedStatement statement) {
    return mock(
        Connection.class,
        invocation -> {
          if (invocation.getMethod().getName().equals("prepareStatement")) {
            assertThat(invocation.getArgument(0, String.class)).isEqualTo("controlled");
            return statement;
          }
          return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
  }

  private static DSLContext source(ConnectionProvider provider) {
    ConnectionProvider scoped =
        provider instanceof CanonicalGameplayOriginalNewCapacityExecutionBudget.ConnectionRetirer
            ? provider
            : new ControlledConnectionProvider(provider);
    return DSL.using(new DefaultConfiguration().set(SQLDialect.POSTGRES).set(scoped));
  }

  private static Semaphore operationPermits() {
    return (Semaphore)
        java.util.Objects.requireNonNull(
            ReflectionTestUtils.getField(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
  }

  private static final class ControlledConnectionProvider
      implements ConnectionProvider,
          CanonicalGameplayOriginalNewCapacityExecutionBudget.ConnectionRetirer {
    private final ConnectionProvider delegate;
    private final java.util.Set<Connection> retired =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final CountDownLatch retirementAttempted = new CountDownLatch(1);
    private volatile boolean failRetirement;

    private ControlledConnectionProvider(ConnectionProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Connection acquire() {
      return delegate.acquire();
    }

    @Override
    public void release(Connection connection) {
      delegate.release(connection);
    }

    @Override
    public synchronized void retire(Connection connection) throws java.sql.SQLException {
      retirementAttempted.countDown();
      if (failRetirement) throw new java.sql.SQLException("controlled retirement uncertainty");
      retired.add(connection);
    }

    private synchronized boolean retired(Connection connection) {
      return retired.contains(connection);
    }
  }

  private static boolean execute(DSLContext sql) {
    return sql.connectionResult(connection -> {
      try (var statement = connection.prepareStatement("controlled")) {
        return statement.execute();
      }
    });
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    for (; ; ) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private record WatchdogCaller(CompletableFuture<Void> result, Runnable release)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      release.run();
      // Await termination after releasing the fixture. The watchdog owns the assertion
      // outcome; this join ignores that outcome without ignoring a cleanup timeout.
      result.handle((ignored, failure) -> null).get(2, TimeUnit.SECONDS);
    }
  }
}
