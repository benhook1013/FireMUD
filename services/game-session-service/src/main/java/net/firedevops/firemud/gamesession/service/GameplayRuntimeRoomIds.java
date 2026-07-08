package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.common.security.RequestIdValidation;

/** Shared canonical runtime room reader for Game Session room-scoped downstream calls. */
public final class GameplayRuntimeRoomIds {
  private GameplayRuntimeRoomIds() {}

  public static String requireCanonical(String roomInstanceId, String fieldName) {
    return RequestIdValidation.requireCanonicalRuntimeRoomId(roomInstanceId, fieldName);
  }

  public static boolean isCanonical(String roomInstanceId) {
    if (roomInstanceId == null || roomInstanceId.isBlank()) {
      return false;
    }
    try {
      requireCanonical(roomInstanceId, "roomInstanceId");
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
