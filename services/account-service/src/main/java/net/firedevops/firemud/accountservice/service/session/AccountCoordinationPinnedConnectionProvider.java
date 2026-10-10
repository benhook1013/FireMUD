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

  /** Bounded durability acknowledgement requirements for one owner Coordination operation. */
  record AcknowledgementRequirements(
      int requiredLocalAofCount, int requiredReplicaAofCount, int timeoutMillis) {
    public AcknowledgementRequirements {
      if (requiredLocalAofCount != 1
          || requiredReplicaAofCount < 1
          || requiredReplicaAofCount > 16
          || timeoutMillis < 1
          || timeoutMillis > 60_000) {
        throw new IllegalArgumentException("WAITAOF requirements are outside the supported bound");
      }
    }
  }
}
