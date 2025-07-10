package net.firedevops.firemud.service;

import java.util.List;

public interface TravelService {
  /**
   * Find a path between two rooms using pathfinding.
   *
   * @return list of room IDs representing the path, including start and end
   */
  List<Long> findPath(Long tenantId, Long startRoomId, Long targetRoomId);
}
