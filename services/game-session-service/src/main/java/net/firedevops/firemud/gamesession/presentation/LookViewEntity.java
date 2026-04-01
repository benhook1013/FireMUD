package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Visible entity data for the normalized LOOK view payload. */
public record LookViewEntity(
    String entityType, String displayName, String role, List<String> stateFlags) {
  public LookViewEntity {
    Objects.requireNonNull(entityType, "entityType must not be null");
    Objects.requireNonNull(displayName, "displayName must not be null");
    role = role == null ? "" : role;
    stateFlags = List.copyOf(Objects.requireNonNull(stateFlags, "stateFlags must not be null"));
  }
}
