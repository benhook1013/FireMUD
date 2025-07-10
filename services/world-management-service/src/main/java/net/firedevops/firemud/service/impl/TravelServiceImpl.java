package net.firedevops.firemud.service.impl;

import java.util.*;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entity.RoomExit;
import net.firedevops.firemud.repository.RoomExitRepository;
import net.firedevops.firemud.service.TravelService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TravelServiceImpl implements TravelService {
  private final RoomExitRepository exitRepository;

  @Override
  public List<Long> findPath(Long tenantId, Long startRoomId, Long targetRoomId) {
    if (startRoomId.equals(targetRoomId)) {
      return List.of(startRoomId);
    }
    List<RoomExit> exits = exitRepository.findByTenantId(tenantId);
    Map<Long, List<Long>> graph = buildGraph(exits);
    return dijkstra(graph, startRoomId, targetRoomId);
  }

  private Map<Long, List<Long>> buildGraph(List<RoomExit> exits) {
    Map<Long, List<Long>> graph = new HashMap<>();
    for (RoomExit exit : exits) {
      graph
          .computeIfAbsent(exit.getFromRoom().getId(), k -> new ArrayList<>())
          .add(exit.getToRoom().getId());
      graph
          .computeIfAbsent(exit.getToRoom().getId(), k -> new ArrayList<>())
          .add(exit.getFromRoom().getId());
    }
    return graph;
  }

  private List<Long> dijkstra(Map<Long, List<Long>> graph, Long start, Long end) {
    Map<Long, Integer> dist = new HashMap<>();
    Map<Long, Long> prev = new HashMap<>();
    PriorityQueue<Long> queue = new PriorityQueue<>(Comparator.comparingInt(dist::get));

    for (Long node : graph.keySet()) {
      dist.put(node, Integer.MAX_VALUE);
    }
    dist.put(start, 0);
    queue.add(start);

    while (!queue.isEmpty()) {
      Long current = queue.poll();
      if (current.equals(end)) {
        break;
      }
      for (Long neighbor : graph.getOrDefault(current, Collections.emptyList())) {
        int alt = dist.get(current) + 1;
        if (alt < dist.getOrDefault(neighbor, Integer.MAX_VALUE)) {
          dist.put(neighbor, alt);
          prev.put(neighbor, current);
          queue.add(neighbor);
        }
      }
    }

    if (!prev.containsKey(end) && !start.equals(end)) {
      return List.of();
    }
    LinkedList<Long> path = new LinkedList<>();
    Long node = end;
    path.addFirst(node);
    while (prev.containsKey(node)) {
      node = prev.get(node);
      path.addFirst(node);
    }
    return path;
  }
}
