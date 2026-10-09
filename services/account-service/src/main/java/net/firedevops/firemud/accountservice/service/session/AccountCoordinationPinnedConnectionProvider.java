package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.api.StatefulRedisConnection;

/** Opens one private, dedicated, non-clustered Coordination connection for one Account read. */
@FunctionalInterface
public interface AccountCoordinationPinnedConnectionProvider {
  /**
   * The provider must not return an ambient Spring connection, must disable automatic reconnect,
   * and must retain one pinned physical Redis connection for the invocation. The caller checks the
   * open/no-auto-reconnect contract and authenticates its ACL identity before reading data.
   */
  StatefulRedisConnection<byte[], byte[]> openPinnedConnection();
}
