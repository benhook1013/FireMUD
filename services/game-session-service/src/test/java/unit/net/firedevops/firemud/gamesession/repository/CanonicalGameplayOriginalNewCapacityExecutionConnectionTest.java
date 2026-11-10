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
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/** Controlled connect behavior only, never source or execution authority. */
class CanonicalGameplayOriginalNewCapacityExecutionConnectionTest {
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
    StatefulRedisConnection<byte[], byte[]> late = mock();
    when(late.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionOwner.MAX_IN_FLIGHT_CONNECTS;
    CountDownLatch entered = new CountDownLatch(maximum), release = new CountDownLatch(1);
    when(redis.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            invocation -> {
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
    var owner = owner(mock(), redis, mock());
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    var callers = new java.util.ArrayList<CompletableFuture<Void>>();
    try {
      for (int index = 0; index < maximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () -> {
                  assertThatThrownBy(
                          () ->
                              Context.current()
                                  .withDeadlineAfter(200, TimeUnit.MILLISECONDS, scheduler)
                                  .call(owner::connectForRead))
                      .isInstanceOf(IllegalStateException.class);
                }));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
      // All four call deadlines have returned, but their blocked connects still occupy the cap.
      assertThatThrownBy(owner::connectForRead).isInstanceOf(IllegalStateException.class);
      verify(redis, org.mockito.Mockito.times(maximum)).connect(ByteArrayCodec.INSTANCE);
      verifyNoInteractions(late);
    } finally {
      release.countDown();
      scheduler.shutdownNow();
      verify(late, timeout(2000).times(maximum)).closeAsync();
    }
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
