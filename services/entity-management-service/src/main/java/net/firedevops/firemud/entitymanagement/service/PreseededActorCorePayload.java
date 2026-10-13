package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;

/** Minimal Entity-owned core for a newly assigned player actor; it carries no authored state. */
public record PreseededActorCorePayload(ActorKind actorKind, String displayName) {
  public enum ActorKind {
    PLAYER
  }

  private static final int MAX_DISPLAY_NAME_CODE_POINTS = 100;

  public PreseededActorCorePayload {
    Objects.requireNonNull(actorKind, "actorKind");
    if (actorKind != ActorKind.PLAYER) {
      throw new IllegalArgumentException("Only the canonical PLAYER actor kind can be assigned");
    }
    if (displayName == null
        || displayName.isBlank()
        || !displayName.equals(displayName.trim())
        || displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_CODE_POINTS
        || hasUnpairedSurrogate(displayName)
        || displayName.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("displayName must be bounded printable text");
    }
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }
}
