package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated standalone Lettuce client for Account Coordination operations.
 *
 * <p>The trusted owner configuration is supplied explicitly as a Redis URI. This class does not
 * consult Spring's ambient Redis connection factory, choose an endpoint, or supply credentials. The
 * provider owns the client; each returned physical connection is owned by the invocation and must
 * be closed by its caller.
 */
public final class LettuceAccountCoordinationPinnedConnectionProvider
    implements AccountCoordinationPinnedConnectionProvider, AutoCloseable {
  private final RedisClient client;
  private final AtomicBoolean closed = new AtomicBoolean();

  public LettuceAccountCoordinationPinnedConnectionProvider(RedisURI coordinationRedisUri) {
    this.client =
        Objects.requireNonNull(
            RedisClient.create(
                Objects.requireNonNull(coordinationRedisUri, "coordinationRedisUri")),
            "Lettuce RedisClient creation returned no client");
    try {
      // Configure the dedicated standalone client before any physical connection is opened.
      this.client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    } catch (RuntimeException configurationFailure) {
      try {
        this.client.shutdown();
      } catch (RuntimeException shutdownFailure) {
        configurationFailure.addSuppressed(shutdownFailure);
      }
      throw configurationFailure;
    }
  }

  @Override
  public StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
    if (closed.get()) {
      throw new IllegalStateException("Account Coordination Redis client is closed");
    }
    StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
    if (closed.get()) {
      try {
        connection.close();
      } catch (RuntimeException closeFailure) {
        IllegalStateException failure =
            new IllegalStateException("Account Coordination Redis client is closed");
        failure.addSuppressed(closeFailure);
        throw failure;
      }
      throw new IllegalStateException("Account Coordination Redis client is closed");
    }
    return connection;
  }

  /** Shuts down this provider's dedicated client; callers close each returned connection. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      client.shutdown();
    }
  }
}
