package net.firedevops.firemud.common.world;

import java.util.Objects;
import java.util.UUID;

/** Canonical owner-authored World room identity; never a runtime instance or private row key. */
public record RoomTemplateRef(UUID tenantId, UUID versionId, UUID roomTemplateId) {
  private static final UUID NIL = new UUID(0L, 0L);

  public RoomTemplateRef {
    requireNonNil(tenantId, "tenantId");
    requireNonNil(versionId, "versionId");
    requireNonNil(roomTemplateId, "roomTemplateId");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil canonical UUID");
    }
  }
}
