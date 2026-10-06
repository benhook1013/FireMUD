package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.time.Duration;
import net.firedevops.firemud.accountservice.config.AccountGameplayCoordinationRedisBinding;
import org.junit.jupiter.api.Test;

class AccountGameplayCoordinationConnectionProviderTest {
  @Test
  void usesDedicatedByteConnectionAndClosesClientAtExplicitLifecycleBoundary() {
    AccountGameplayCoordinationRedisBinding binding = binding();
    RedisClient redisClient = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    when(binding.createDedicatedRedisClient()).thenReturn(redisClient);
    when(redisClient.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());

    AccountGameplayCoordinationConnectionProvider provider =
        new AccountGameplayCoordinationConnectionProvider(binding);

    assertThat(provider.openPinnedConnection()).isSameAs(connection);
    provider.close();
    provider.close();

    verify(binding, times(2)).requireUnchangedProtectedSources();
    verify(redisClient).connect(ByteArrayCodec.INSTANCE);
    verify(redisClient).shutdown(Duration.ZERO, Duration.ofSeconds(2));
    verify(binding).close();
    verify(connection, never()).close();
  }

  @Test
  void changedProtectedSourcesFailBeforeOpeningANetworkConnection() {
    AccountGameplayCoordinationRedisBinding binding = binding();
    RedisClient redisClient = mock(RedisClient.class);
    when(binding.createDedicatedRedisClient()).thenReturn(redisClient);
    AccountGameplayCoordinationConnectionProvider provider =
        new AccountGameplayCoordinationConnectionProvider(binding);
    doThrow(new AccountGameplayCoordinationRedisBinding.BindingRejectedException())
        .when(binding)
        .requireUnchangedProtectedSources();

    assertThatThrownBy(provider::openPinnedConnection)
        .isInstanceOf(
            AccountGameplayCoordinationConnectionProvider.ConnectionProviderUnavailableException
                .class);

    verify(redisClient, never()).connect(ByteArrayCodec.INSTANCE);
    provider.close();
  }

  @Test
  void rejectsWrongConfiguredRoleAndUnexpectedReconnectableConnection() {
    AccountGameplayCoordinationRedisBinding wrongRole = binding();
    when(wrongRole.aclUsername()).thenReturn("gamesession_coord_app");

    assertThatThrownBy(() -> new AccountGameplayCoordinationConnectionProvider(wrongRole))
        .isInstanceOf(
            AccountGameplayCoordinationConnectionProvider.ConnectionProviderUnavailableException
                .class);
    verify(wrongRole, never()).createDedicatedRedisClient();
    verify(wrongRole).close();

    AccountGameplayCoordinationRedisBinding binding = binding();
    RedisClient redisClient = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    when(binding.createDedicatedRedisClient()).thenReturn(redisClient);
    when(redisClient.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    AccountGameplayCoordinationConnectionProvider provider =
        new AccountGameplayCoordinationConnectionProvider(binding);

    assertThatThrownBy(provider::openPinnedConnection)
        .isInstanceOf(
            AccountGameplayCoordinationConnectionProvider.ConnectionProviderUnavailableException
                .class);

    verify(connection).close();
    provider.close();
  }

  private static AccountGameplayCoordinationRedisBinding binding() {
    AccountGameplayCoordinationRedisBinding binding =
        mock(AccountGameplayCoordinationRedisBinding.class);
    when(binding.aclUsername())
        .thenReturn(AccountGameplayCoordinationRedisBinding.REQUIRED_ACL_IDENTITY);
    return binding;
  }
}
