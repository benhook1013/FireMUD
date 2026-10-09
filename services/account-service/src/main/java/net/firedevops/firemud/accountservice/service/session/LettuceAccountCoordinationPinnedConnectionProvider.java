package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.util.Objects;

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
  private boolean closed;

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
  public synchronized StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
    if (closed) {
      throw new IllegalStateException("Account Coordination Redis client is closed");
    }
    return client.connect(ByteArrayCodec.INSTANCE);
  }

  /** Shuts down this provider's dedicated client; callers close each returned connection. */
  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      client.shutdown();
    }
  }
}
