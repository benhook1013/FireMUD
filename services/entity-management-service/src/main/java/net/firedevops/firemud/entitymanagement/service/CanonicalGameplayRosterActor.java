package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Persisted primary controllable actor returned by canonical roster discovery. */
public record CanonicalGameplayRosterActor(UUID characterUuid, String displayName) {
  public CanonicalGameplayRosterActor {
    Objects.requireNonNull(characterUuid, "characterUuid");
    if (characterUuid.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("characterUuid must be non-nil");
    }
    if (displayName == null
        || displayName.isBlank()
        || !displayName.equals(displayName.trim())
        || displayName.length() > 100
        || displayName.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("displayName is incomplete");
    }
  }
}
