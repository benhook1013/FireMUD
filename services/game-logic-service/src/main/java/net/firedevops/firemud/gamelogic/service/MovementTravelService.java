package net.firedevops.firemud.gamelogic.service;

import java.util.List;

/** Movement/Travel subsystem for computing paths and movement costs using world geometry. */
public interface MovementTravelService {
  /**
   * Find a path between two rooms using provided room exit geometry.
   *
   * @param exits room exit definitions including cost and spacing multiplier
   * @param startRoomId starting room identifier
   * @param targetRoomId target room identifier
   * @return list of room IDs representing the path, including start and end; empty if no path
   */
  List<Long> findPath(List<RoomExit> exits, Long startRoomId, Long targetRoomId);

  /**
   * Simple representation of a room exit for pathfinding.
   *
   * @param fromRoomId origin room ID
   * @param toRoomId destination room ID
   * @param cost base movement cost between rooms
   * @param spacingMultiplier region spacing multiplier applied to the cost
   */
  record RoomExit(Long fromRoomId, Long toRoomId, int cost, double spacingMultiplier) {}
}
