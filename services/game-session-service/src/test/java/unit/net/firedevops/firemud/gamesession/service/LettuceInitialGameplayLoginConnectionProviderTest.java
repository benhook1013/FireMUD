package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LettuceInitialGameplayLoginConnectionProviderTest {
  @Test
  void disablesReconnectBeforeOpeningDedicatedBinaryConnectionAndOwnsClientLifecycle() {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = connection();
    RedisURI uri = RedisURI.Builder.redis("coordination.example.invalid").build();
    when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    try (var factory = mockStatic(RedisClient.class)) {
      factory.when(() -> RedisClient.create(uri)).thenReturn(client);
      var provider = new LettuceInitialGameplayLoginConnectionProvider(uri);

      assertThat(provider.openPinnedConnection()).isSameAs(connection);

      var options = ArgumentCaptor.forClass(ClientOptions.class);
      var order = inOrder(client);
      order.verify(client).setOptions(options.capture());
      order.verify(client).connect(ByteArrayCodec.INSTANCE);
      assertThat(options.getValue().isAutoReconnect()).isFalse();

      provider.close();
      provider.close();
      verify(client, times(1)).shutdown();
      assertThatThrownBy(provider::openPinnedConnection)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
      verify(client, times(1)).connect(ByteArrayCodec.INSTANCE);
      factory.verify(() -> RedisClient.create(uri), times(1));
    }
  }

  @Test
  void shutsDownClientIfReconnectConfigurationCannotBeApplied() {
    RedisClient client = mock(RedisClient.class);
    var failure = new IllegalStateException("options rejected");
    doThrow(failure).when(client).setOptions(any(ClientOptions.class));
    RedisURI uri = RedisURI.Builder.redis("coordination.example.invalid").build();

    try (var factory = mockStatic(RedisClient.class)) {
      factory.when(() -> RedisClient.create(uri)).thenReturn(client);
      assertThatThrownBy(() -> new LettuceInitialGameplayLoginConnectionProvider(uri))
          .isSameAs(failure);
      verify(client).shutdown();
    }
  }

  @Test
  void preservesConfigurationFailureIfClientShutdownAlsoFails() {
    RedisClient client = mock(RedisClient.class);
    var failure = new IllegalStateException("options rejected");
    var shutdownFailure = new IllegalStateException("shutdown rejected");
    doThrow(failure).when(client).setOptions(any(ClientOptions.class));
    doThrow(shutdownFailure).when(client).shutdown();
    RedisURI uri = RedisURI.Builder.redis("coordination.example.invalid").build();

    try (var factory = mockStatic(RedisClient.class)) {
      factory.when(() -> RedisClient.create(uri)).thenReturn(client);
      assertThatThrownBy(() -> new LettuceInitialGameplayLoginConnectionProvider(uri))
          .isSameAs(failure)
          .hasSuppressedException(shutdownFailure);
    }
  }

  @Test
  void disposesConnectionThatFinishesAfterConcurrentProviderClose() throws Exception {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = connection();
    RedisURI uri = RedisURI.Builder.redis("coordination.example.invalid").build();
    var connectStarted = new CountDownLatch(1);
    var finishConnect = new CountDownLatch(1);
    when(client.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            ignored -> {
              connectStarted.countDown();
              if (!finishConnect.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test connection was not released");
              }
              return connection;
            });
    var executor = Executors.newSingleThreadExecutor();
    try (var factory = mockStatic(RedisClient.class)) {
      factory.when(() -> RedisClient.create(uri)).thenReturn(client);
      var provider = new LettuceInitialGameplayLoginConnectionProvider(uri);
      var opening = executor.submit(provider::openPinnedConnection);

      assertThat(connectStarted.await(5, TimeUnit.SECONDS)).isTrue();
      provider.close();
      finishConnect.countDown();

      assertThatThrownBy(() -> opening.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
      verify(connection).close();
      provider.close();
      verify(client, times(1)).shutdown();
    } finally {
      finishConnect.countDown();
      executor.shutdownNow();
    }
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> connection() {
    return mock(StatefulRedisConnection.class);
  }
}
