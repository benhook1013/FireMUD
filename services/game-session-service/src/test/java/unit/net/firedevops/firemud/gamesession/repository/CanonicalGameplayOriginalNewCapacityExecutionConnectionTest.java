package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Controlled connect behavior only, never source or execution authority. */
class CanonicalGameplayOriginalNewCapacityExecutionConnectionTest {
  @AfterEach
  void awaitConnectorAndBudgetWorkerCleanup() throws InterruptedException {
    Semaphore permits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    assertThat(permits.tryAcquire(maximum, 2, TimeUnit.SECONDS))
        .as("resource and cancellation workers restore all operation permits")
        .isTrue();
    permits.release(maximum);

    ThreadPoolExecutor connectors =
        (ThreadPoolExecutor)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionOwner.class, "CONNECTS"));
    // closeAsync() is observed before the connector task returns. Wait for executor task
    // completion as well, including after failed assertions in either timeout test.
    Awaitility.await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(connectors.getActiveCount()).isZero();
              assertThat(connectors.getCompletedTaskCount()).isEqualTo(connectors.getTaskCount());
            });
  }

  @Test
  void expiredCallDoesNotWaitForConnectionAndDisposesTheLateConnection() throws Exception {
    RedisClient redis = mock();
    DSLContext dsl = mock();
    CanonicalGameplayTenantGameplayTagRepository tags = mock();
    StatefulRedisConnection<byte[], byte[]> late = mock();
    var closeFinished = new CompletableFuture<Void>();
    when(late.closeAsync()).thenReturn(closeFinished);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(redis.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              // Model an OS/DNS/TLS connect that cannot be interrupted. The owner must still
              // return.
              for (; ; ) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException interrupted) {
                  /* The controlled connect remains pending. */
                }
              }
              return late;
            });
    var owner = owner(dsl, redis, tags);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      assertThatThrownBy(
              () ->
                  Context.current()
                      .withDeadlineAfter(100, TimeUnit.MILLISECONDS, scheduler)
                      .call(owner::connectForRead))
          .isInstanceOf(IllegalStateException.class);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      verifyNoInteractions(late, dsl, tags);
      release.countDown();
      verify(late, timeout(1000)).closeAsync();
      Semaphore operationPermits =
          (Semaphore)
              java.util.Objects.requireNonNull(
                  ReflectionTestUtils.getField(
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
      assertThat(operationPermits.availablePermits())
          .isEqualTo(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS - 1);
      verifyNoInteractions(dsl, tags);
    } finally {
      release.countDown();
      closeFinished.complete(null);
      scheduler.shutdownNow();
    }
  }

  @Test
  void physicalConnectorWorkersSaturateFourSlotsWithoutQueuingAnotherAttempt() throws Exception {
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionOwner.MAX_IN_FLIGHT_CONNECTS;
    CountDownLatch entered = new CountDownLatch(maximum), release = new CountDownLatch(1);
    ThreadPoolExecutor connectors =
        (ThreadPoolExecutor)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionOwner.class, "CONNECTS"));
    Semaphore permits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int operationMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    long completedBefore = connectors.getCompletedTaskCount();
    RedisClient redis = mock();
    var owner = owner(mock(), redis, mock());
    try {
      for (int index = 0; index < maximum; index++)
        connectors.execute(
            () -> {
              entered.countDown();
              for (; ; ) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException interrupted) {
                  /* Model an unrelated connector worker that ignores cancellation. */
                }
              }
            });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(connectors.getActiveCount()).isEqualTo(maximum);
      assertThat(connectors.getQueue()).isEmpty();
      assertThat(connectors.getTaskCount()).isEqualTo(completedBefore + maximum);
      assertThat(permits.availablePermits()).isEqualTo(operationMaximum);
      assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(redis);
      assertThat(connectors.getActiveCount()).isEqualTo(maximum);
      assertThat(connectors.getQueue()).isEmpty();
      assertThat(connectors.getTaskCount()).isEqualTo(completedBefore + maximum);
    } finally {
      release.countDown();
    }
    Awaitility.await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(connectors.getActiveCount()).isZero();
              assertThat(connectors.getQueue()).isEmpty();
              assertThat(connectors.getCompletedTaskCount()).isEqualTo(completedBefore + maximum);
            });
    assertThat(permits.availablePermits()).isEqualTo(operationMaximum);
  }

  @Test
  void interruptedBackgroundConnectorsLeaveTwoWorkersForForeground() throws Exception {
    RedisClient redis = mock();
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;
    var lateConnections = new java.util.ArrayList<StatefulRedisConnection<byte[], byte[]>>();
    var foregroundConnections = new java.util.ArrayList<StatefulRedisConnection<byte[], byte[]>>();
    for (int index = 0; index < backgroundMaximum; index++) {
      StatefulRedisConnection<byte[], byte[]> late = mock();
      when(late.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
      lateConnections.add(late);
    }
    for (int index = 0; index < 2; index++) {
      StatefulRedisConnection<byte[], byte[]> foreground = mock();
      when(foreground.isOpen()).thenReturn(true);
      when(foreground.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(foreground.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
      foregroundConnections.add(foreground);
    }

    var nextConnection = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch ready = new CountDownLatch(backgroundMaximum), start = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(backgroundMaximum), release = new CountDownLatch(1);
    when(redis.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            invocation -> {
              int index = nextConnection.getAndIncrement();
              if (index < backgroundMaximum) {
                var late = lateConnections.get(index);
                entered.countDown();
                for (; ; ) {
                  try {
                    release.await();
                    break;
                  } catch (InterruptedException interrupted) {
                    /* Model a blocking connector that ignores cancellation interruption. */
                  }
                }
                return late;
              }
              return foregroundConnections.get(index - backgroundMaximum);
            });

    ThreadPoolExecutor connectors =
        (ThreadPoolExecutor)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionOwner.class, "CONNECTS"));
    Semaphore operationPermits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    Semaphore backgroundPermits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int operationMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    long completedBefore = connectors.getCompletedTaskCount();
    var dsl = mock(DSLContext.class);
    var tags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    var scripts =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution()));
    var foregroundOwner = owner(dsl, redis, tags);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    var callerThreads = Executors.newFixedThreadPool(backgroundMaximum);
    var callers = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
    try {
      for (int index = 0; index < backgroundMaximum; index++)
        callers.add(
            callerThreads.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try (var context =
                      Context.current().withDeadlineAfter(600, TimeUnit.MILLISECONDS, scheduler)) {
                    assertThatThrownBy(
                            () ->
                                context.call(
                                    () ->
                                        CanonicalGameplayOriginalNewCapacityExecutionBudget
                                            .runBackground(
                                                Clock.systemUTC(),
                                                Long.MAX_VALUE,
                                                budget ->
                                                    budgetOwner(dsl, redis, scripts, tags, budget)
                                                        .connectForRead())))
                        .isInstanceOf(IllegalStateException.class);
                  }
                  return null;
                }));
      assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (var caller : callers) caller.get(3, TimeUnit.SECONDS);
      assertThat(operationPermits.availablePermits())
          .isEqualTo(operationMaximum - backgroundMaximum);
      assertThat(backgroundPermits.availablePermits()).isZero();
      assertThat(connectors.getActiveCount()).isEqualTo(backgroundMaximum);
      assertThat(connectors.getQueue()).isEmpty();

      // Background operation permits remain held by the blocked connectors, leaving two permits
      // for foreground calls that complete while both physical background connects remain active.
      for (int index = 0; index < 2; index++) {
        assertThat(foregroundOwner.connectForRead()).isSameAs(foregroundConnections.get(index));
        foregroundConnections.get(index).closeAsync();
      }
      verify(redis, org.mockito.Mockito.times(backgroundMaximum + 2))
          .connect(ByteArrayCodec.INSTANCE);
      assertThat(connectors.getActiveCount()).isEqualTo(backgroundMaximum);
      assertThat(connectors.getQueue()).isEmpty();
      assertThat(connectors.getTaskCount()).isEqualTo(completedBefore + backgroundMaximum + 2);
      assertThat(operationPermits.availablePermits())
          .isEqualTo(operationMaximum - backgroundMaximum);
      assertThat(backgroundPermits.availablePermits()).isZero();
      lateConnections.forEach(org.mockito.Mockito::verifyNoInteractions);
    } finally {
      start.countDown();
      release.countDown();
      scheduler.shutdownNow();
      callerThreads.shutdownNow();
    }

    Awaitility.await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(connectors.getActiveCount()).isZero();
              assertThat(connectors.getQueue()).isEmpty();
              assertThat(connectors.getCompletedTaskCount())
                  .isEqualTo(completedBefore + backgroundMaximum + 2);
              assertThat(operationPermits.availablePermits()).isEqualTo(operationMaximum);
              assertThat(backgroundPermits.availablePermits())
                  .isEqualTo(
                      CanonicalGameplayOriginalNewCapacityExecutionBudget
                          .MAX_BACKGROUND_OPERATIONS);
            });
    lateConnections.forEach(late -> verify(late).closeAsync());
    foregroundConnections.forEach(foreground -> verify(foreground).closeAsync());
    assertThat(callerThreads.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void failedLateCloseIsNotRetriedOrPermitLeaked() {
    RedisClient redis = mock();
    StatefulRedisConnection<byte[], byte[]> asyncFailure = mock();
    StatefulRedisConnection<byte[], byte[]> syncFailure = mock();
    for (StatefulRedisConnection<byte[], byte[]> connection : List.of(asyncFailure, syncFailure)) {
      when(connection.isOpen()).thenReturn(true);
      when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    }
    when(redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(asyncFailure, syncFailure);
    when(asyncFailure.closeAsync())
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("close failed")));
    when(syncFailure.closeAsync()).thenThrow(new IllegalStateException("close failed"));
    var owner = owner(mock(), redis, mock());

    assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
    verify(asyncFailure).closeAsync();
    verify(syncFailure).closeAsync();
    Semaphore operationPermits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    assertThat(operationPermits.availablePermits())
        .isEqualTo(CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS);
  }

  @Test
  void standalonePackagePathEntersTheActualBudgetScopeWithoutSqlOrTagAuthority() {
    RedisClient redis = mock();
    DSLContext dsl = mock();
    CanonicalGameplayTenantGameplayTagRepository tags = mock();
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    when(redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    assertThat(owner(dsl, redis, tags).connectForRead()).isSameAs(connection);
    connection.closeAsync();
    verifyNoInteractions(dsl, tags);
  }

  @Test
  void failedConnectionAttemptDoesNotRetainAConnectorSlot() {
    RedisClient redis = mock();
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionOwner.MAX_IN_FLIGHT_CONNECTS;
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    var failedAttempts = new java.util.concurrent.atomic.AtomicInteger();
    when(redis.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            invocation -> {
              if (failedAttempts.getAndIncrement() < maximum)
                throw new IllegalStateException("Controlled connect failure");
              return connection;
            });
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    var owner = owner(mock(), redis, mock());
    ThreadPoolExecutor connectors =
        (ThreadPoolExecutor)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionOwner.class, "CONNECTS"));
    long completedBefore = connectors.getCompletedTaskCount();

    for (int attempt = 0; attempt < maximum; attempt++)
      assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
    Awaitility.await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(connectors.getActiveCount()).isZero();
              assertThat(connectors.getCompletedTaskCount()).isEqualTo(completedBefore + maximum);
            });
    assertThat(owner.connectForRead()).isSameAs(connection);
    connection.closeAsync();
    verify(redis, org.mockito.Mockito.times(maximum + 1)).connect(ByteArrayCodec.INSTANCE);
  }

  private static CanonicalGameplayOriginalNewCapacityExecutionOwner owner(
      DSLContext dsl, RedisClient redis, CanonicalGameplayTenantGameplayTagRepository tags) {
    return new CanonicalGameplayOriginalNewCapacityExecutionOwner(
        dsl,
        redis,
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution())),
        Clock.systemUTC(),
        tags);
  }

  private static CanonicalGameplayOriginalNewCapacityExecutionOwner budgetOwner(
      DSLContext dsl,
      RedisClient redis,
      RedisScriptCatalog scripts,
      CanonicalGameplayTenantGameplayTagRepository tags,
      CanonicalGameplayOriginalNewCapacityExecutionBudget budget) {
    try {
      Constructor<CanonicalGameplayOriginalNewCapacityExecutionOwner> constructor =
          CanonicalGameplayOriginalNewCapacityExecutionOwner.class.getDeclaredConstructor(
              DSLContext.class,
              RedisClient.class,
              RedisScriptCatalog.class,
              Clock.class,
              CanonicalGameplayTenantGameplayTagRepository.class,
              CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
              StatefulRedisConnection.class);
      constructor.setAccessible(true);
      return constructor.newInstance(dsl, redis, scripts, Clock.systemUTC(), tags, budget, null);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Unable to create scoped owner for connector proof", failure);
    }
  }
}
