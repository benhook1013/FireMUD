package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Optional;

public final class GameplayAdmissionPointerSnapshots {
  private GameplayAdmissionPointerSnapshots() {}

  public static boolean hasCompleteRoutingBundle(GameplayAdmissionPointerSnapshot pointer) {
    return pointer != null
        && pointer.tenantId() > 0L
        && pointer.gameInstanceId() > 0L
        && pointer.pointerVersion() > 0L
        && pointer.worldSlug() != null
        && !pointer.worldSlug().isBlank()
        && pointer.realmSlug() != null
        && !pointer.realmSlug().isBlank()
        && pointer.stateScope() != null
        && !pointer.stateScope().isBlank();
  }

  public static Optional<GameplayAdmissionPointerSnapshot> singularCompletePointer(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    if (pointers == null) {
      return Optional.empty();
    }
    List<GameplayAdmissionPointerSnapshot> runtimePointers =
        pointers.stream().filter(pointer -> pointer != null).toList();
    if (runtimePointers.size() != 1) {
      return Optional.empty();
    }
    GameplayAdmissionPointerSnapshot pointer = runtimePointers.getFirst();
    return hasCompleteRoutingBundle(pointer) ? Optional.of(pointer) : Optional.empty();
  }
}
