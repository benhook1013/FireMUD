package net.firedevops.firemud.gamesession.client;

import java.util.Objects;
import java.util.UUID;

/** Scoped numeric identity only; constructing a value supplies no authenticated owner evidence. */
public record CanonicalRuntimeEntityId(UUID tenantId, String gameInstanceId, long value) {
  public CanonicalRuntimeEntityId {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(gameInstanceId, "gameInstanceId");
    if (tenantId.equals(new UUID(0L, 0L)) || gameInstanceId.isBlank() || value <= 0) {
      throw new IllegalArgumentException("Positive runtime identity and complete scope required");
    }
  }
}
