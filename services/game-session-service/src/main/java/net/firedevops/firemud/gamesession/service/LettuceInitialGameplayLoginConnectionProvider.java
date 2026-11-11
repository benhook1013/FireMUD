package net.firedevops.firemud.gamesession.service;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the dedicated initial-LOGIN Coordination client, never the ambient Spring Redis client. Each
 * invocation owns its returned physical connection. Reconnect cannot move a mutation's
 * acknowledgement or readback onto another physical connection.
 */
public final class LettuceInitialGameplayLoginConnectionProvider implements AutoCloseable {
  private final RedisClient client;
  private final AtomicBoolean closed = new AtomicBoolean();

  public LettuceInitialGameplayLoginConnectionProvider(RedisURI coordinationRedisUri) {
    this.client =
        Objects.requireNonNull(
            RedisClient.create(
                Objects.requireNonNull(coordinationRedisUri, "Explicit Coordination URI required")),
            "Dedicated Coordination client required");
    try {
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    } catch (RuntimeException failure) {
      try {
        client.shutdown();
      } catch (RuntimeException shutdownFailure) {
        failure.addSuppressed(shutdownFailure);
      }
      throw failure;
    }
  }

  public StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
    if (closed.get()) throw unavailable();
    StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
    if (closed.get()) {
      try {
        connection.close();
      } catch (RuntimeException closeFailure) {
        IllegalStateException failure = unavailable();
        failure.addSuppressed(closeFailure);
        throw failure;
      }
      throw unavailable();
    }
    return connection;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) client.shutdown();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Initial LOGIN Coordination client is closed");
  }
}
