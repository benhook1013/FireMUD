package net.firedevops.firemud.accountservice.service.session;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

class LettuceAccountCoordinationPinnedConnectionProviderTest {
  @Test
  void disablesReconnectBeforeOpeningDedicatedBinaryConnectionAndOwnsClientLifecycle() {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = connection();
    RedisURI uri = RedisURI.Builder.redis("coordination.example.invalid").build();
    when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    try (MockedStatic<RedisClient> redisClientFactory = mockStatic(RedisClient.class)) {
      redisClientFactory.when(() -> RedisClient.create(uri)).thenReturn(client);
      LettuceAccountCoordinationPinnedConnectionProvider provider =
          new LettuceAccountCoordinationPinnedConnectionProvider(uri);

      assertThat(provider.openPinnedConnection()).isSameAs(connection);

      ArgumentCaptor<ClientOptions> options = ArgumentCaptor.forClass(ClientOptions.class);
      InOrder order = inOrder(client);
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
      redisClientFactory.verify(() -> RedisClient.create(uri), times(1));
    }
  }

  @Test
  void shutsDownClientIfReconnectConfigurationCannotBeApplied() {
    RedisClient client = mock(RedisClient.class);
    IllegalStateException failure = new IllegalStateException("options rejected");
    doThrow(failure).when(client).setOptions(any(ClientOptions.class));
    RedisURI uri = RedisURI.Builder.redis("coordination.example.invalid").build();

    try (MockedStatic<RedisClient> redisClientFactory = mockStatic(RedisClient.class)) {
      redisClientFactory.when(() -> RedisClient.create(uri)).thenReturn(client);
      assertThatThrownBy(() -> new LettuceAccountCoordinationPinnedConnectionProvider(uri))
          .isSameAs(failure);

      verify(client).shutdown();
    }
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> connection() {
    return mock(StatefulRedisConnection.class);
  }
}
