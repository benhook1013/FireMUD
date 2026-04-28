package net.firedevops.firemud.entitymanagement.dto;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;

public record RoomEntityDto(
    String entityId,
    String displayName,
    EntityType entityType,
    String role,
    List<String> stateFlags,
    int visionPriority,
    ReloadHint reloadHint,
    boolean visible,
    String visibleRef) {
  public RoomEntityDto {
    stateFlags = stateFlags == null ? List.of() : List.copyOf(stateFlags);
  }
}
