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
    when(late.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
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
      verifyNoInteractions(dsl, tags);
    } finally {
      release.countDown();
      scheduler.shutdownNow();
    }
  }

  @Test
  void nonInterruptibleConnectsSaturateFourSharedSlotsWithoutQueuingAnotherAttempt()
      throws Exception {
    RedisClient redis = mock();
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionOwner.MAX_IN_FLIGHT_CONNECTS;
    var lateConnections = new java.util.ArrayList<StatefulRedisConnection<byte[], byte[]>>();
    for (int index = 0; index < maximum; index++) {
      StatefulRedisConnection<byte[], byte[]> late = mock();
      when(late.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
      lateConnections.add(late);
    }
    var nextConnection = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch ready = new CountDownLatch(maximum), start = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(maximum), release = new CountDownLatch(1);
    when(redis.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            invocation -> {
              var late = lateConnections.get(nextConnection.getAndIncrement());
              entered.countDown();
              for (; ; ) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException interrupted) {
                  /* Model a non-interruptible connect. */
                }
              }
              return late;
            });
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
    var owner = owner(mock(), redis, mock());
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    var callerThreads = Executors.newFixedThreadPool(maximum);
    var callers = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
    try {
      for (int index = 0; index < maximum; index++)
        callers.add(
            callerThreads.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  // Construct deadlines only after all dedicated callers are ready. Common-pool
                  // startup and mock preparation must not consume the controlled deadline.
                  try (var context =
                      Context.current().withDeadlineAfter(1, TimeUnit.SECONDS, scheduler)) {
                    assertThatThrownBy(() -> context.call(owner::connectForRead))
                        .isInstanceOf(IllegalStateException.class);
                  }
                  return null;
                }));
      assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (var caller : callers) caller.get(3, TimeUnit.SECONDS);
      // Caller return can precede resource-worker abandonment. Prove operation permits have
      // recovered before testing connector saturation, so that the fifth refusal cannot be
      // explained by the independent operation cap.
      Awaitility.await()
          .atMost(2, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(permits.availablePermits()).isEqualTo(operationMaximum));
      assertThat(connectors.getActiveCount()).isEqualTo(maximum);
      assertThat(connectors.getQueue()).isEmpty();
      assertThat(connectors.getTaskCount()).isEqualTo(completedBefore + maximum);
      assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
      verify(redis, org.mockito.Mockito.times(maximum)).connect(ByteArrayCodec.INSTANCE);
      assertThat(connectors.getActiveCount()).isEqualTo(maximum);
      assertThat(connectors.getQueue()).isEmpty();
      assertThat(connectors.getTaskCount()).isEqualTo(completedBefore + maximum);
      lateConnections.forEach(org.mockito.Mockito::verifyNoInteractions);
    } finally {
      // Cleanup never verifies: a failed barrier/deadline assertion must remain the primary error.
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
              assertThat(connectors.getCompletedTaskCount()).isEqualTo(completedBefore + maximum);
            });
    for (var late : lateConnections) verify(late).closeAsync();
    assertThat(permits.availablePermits()).isEqualTo(operationMaximum);
    assertThat(callerThreads.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void reconnectingConnectionIsRefusedAndClosedRatherThanReusedForReadback() {
    RedisClient redis = mock();
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    when(redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    assertThatThrownBy(() -> owner(mock(), redis, mock()).connectForRead())
        .isInstanceOf(IllegalStateException.class);
    verify(connection, org.mockito.Mockito.atLeastOnce()).closeAsync();
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
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    when(redis.connect(ByteArrayCodec.INSTANCE))
        .thenThrow(new IllegalStateException("Controlled connect failure"))
        .thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    var owner = owner(mock(), redis, mock());

    assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
    assertThat(owner.connectForRead()).isSameAs(connection);
    connection.closeAsync();
    verify(redis, org.mockito.Mockito.times(2)).connect(ByteArrayCodec.INSTANCE);
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
}
