package net.firedevops.firemud.gamesession.service;

import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Objects;
import java.util.function.Supplier;

/** Protected owner Coordination binding; no ambient client, defaults or runtime registration. */
public final class InitialGameplayLoginRedisBinding {
  public static final String PRINCIPAL = "gamesession_coord_app";
  private final String protectedBindingId;
  private final Supplier<StatefulRedisConnection<byte[], byte[]>> connections;
  private final AcknowledgementPolicy acknowledgementPolicy;

  public InitialGameplayLoginRedisBinding(
      String protectedBindingId,
      Supplier<StatefulRedisConnection<byte[], byte[]>> connections,
      AcknowledgementPolicy acknowledgementPolicy) {
    if (protectedBindingId == null
        || protectedBindingId.isBlank()
        || protectedBindingId.length() > 256
        || !protectedBindingId.equals(protectedBindingId.trim())
        || protectedBindingId.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Protected Coordination binding identity required");
    }
    this.protectedBindingId = protectedBindingId;
    this.connections =
        Objects.requireNonNull(connections, "Protected connection provider required");
    this.acknowledgementPolicy =
        Objects.requireNonNull(acknowledgementPolicy, "Explicit ACK policy required");
  }

  public String protectedBindingId() {
    return protectedBindingId;
  }

  public AcknowledgementPolicy acknowledgementPolicy() {
    return acknowledgementPolicy;
  }

  StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
    return connections.get();
  }

  public enum Profile {
    SINGLE_NODE_NO_FAILOVER,
    REPLICATED
  }

  /**
   * Zero replicas is admissible only for the externally qualified no-failover single-node profile.
   */
  public record AcknowledgementPolicy(
      Profile profile, int requiredLocalAofCount, int requiredReplicaAofCount, int timeoutMillis) {
    public AcknowledgementPolicy {
      Objects.requireNonNull(profile, "Protected profile required");
      if (requiredLocalAofCount != 1
          || requiredReplicaAofCount < 0
          || timeoutMillis < 1
          || timeoutMillis > 60_000
          || profile == Profile.SINGLE_NODE_NO_FAILOVER && requiredReplicaAofCount != 0
          || profile == Profile.REPLICATED && requiredReplicaAofCount < 1) {
        throw new IllegalArgumentException("Explicit Coordination ACK policy is invalid");
      }
    }
  }
}
