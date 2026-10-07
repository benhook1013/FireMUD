package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.config.AccountGameplayCoordinationRedisBinding;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationPinnedConnectionProvider;

/**
 * Explicit Account-owned direct Lettuce provider for the private Coordination registry client.
 *
 * <p>This class is not Spring-wired and never consults an ambient template, connection factory,
 * role label, service-discovery result, or caller-selected endpoint. The supplied binding is loaded
 * from fixed protected files and is rechecked before each connection. Each returned byte connection
 * is direct, non-clustered, TLS peer-verified, and configured with automatic reconnect disabled;
 * the Account registry client owns closing each operation's physical connection. Close this
 * provider only at the explicit lifecycle boundary that owns its dedicated Lettuce client.
 */
public final class AccountGameplayCoordinationConnectionProvider
    implements AccountCoordinationPinnedConnectionProvider, AutoCloseable {
  private final AccountGameplayCoordinationRedisBinding binding;
  private final RedisClient client;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates an explicit provider over an already accepted fixed-file binding. Merely constructing
   * this object does not wire or activate it in the Account application.
   */
  public AccountGameplayCoordinationConnectionProvider(
      AccountGameplayCoordinationRedisBinding binding) {
    this.binding =
        Objects.requireNonNull(binding, "Protected Account Coordination binding is required");
    if (!AccountGameplayCoordinationRedisBinding.REQUIRED_ACL_IDENTITY.equals(
        binding.aclUsername())) {
      binding.close();
      throw unavailable();
    }
    try {
      this.client = binding.createDedicatedRedisClient();
    } catch (RuntimeException rejected) {
      binding.close();
      throw unavailable();
    }
    if (client == null) {
      binding.close();
      throw unavailable();
    }
  }

  @Override
  public StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
    if (closed.get()) throw unavailable();
    StatefulRedisConnection<byte[], byte[]> connection = null;
    try {
      binding.requireUnchangedProtectedSources();
      connection = client.connect(ByteArrayCodec.INSTANCE);
      // A trust or credential source withdrawn during TCP/TLS/AUTH establishment is not
      // accepted as a pinned connection even if the handshake itself completed successfully.
      binding.requireUnchangedProtectedSources();
      if (connection == null
          || !connection.isOpen()
          || connection.getOptions() == null
          || connection.getOptions().isAutoReconnect()) {
        throw unavailable();
      }
      return connection;
    } catch (RuntimeException rejected) {
      closeQuietly(connection);
      throw unavailable();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    RuntimeException failure = null;
    try {
      client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    } catch (RuntimeException rejected) {
      failure = rejected;
    } finally {
      binding.close();
    }
    if (failure != null) throw unavailable();
  }

  private static void closeQuietly(StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null) return;
    try {
      connection.close();
    } catch (RuntimeException ignored) {
      // The caller receives only the generic fail-closed provider error.
    }
  }

  private static ConnectionProviderUnavailableException unavailable() {
    return new ConnectionProviderUnavailableException();
  }

  public static final class ConnectionProviderUnavailableException extends RuntimeException {
    public ConnectionProviderUnavailableException() {
      super("Account Coordination Redis connection is unavailable");
    }
  }
}
