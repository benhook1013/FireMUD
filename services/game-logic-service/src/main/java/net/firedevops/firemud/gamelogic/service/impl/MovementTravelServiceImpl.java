package net.firedevops.firemud.gamelogic.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.*;
import net.firedevops.firemud.gamelogic.service.MovementTravelService;
import org.springframework.stereotype.Service;

@Service
public class MovementTravelServiceImpl implements MovementTravelService {
  @Override
  @Timed(value = "movement.findPath")
  public List<Long> findPath(List<RoomExit> exits, Long startRoomId, Long targetRoomId) {
    if (startRoomId.equals(targetRoomId)) {
      return List.of(startRoomId);
    }
    Map<Long, List<RoomExit>> graph = buildGraph(exits);
    return dijkstra(graph, startRoomId, targetRoomId);
  }

  private Map<Long, List<RoomExit>> buildGraph(List<RoomExit> exits) {
    Map<Long, List<RoomExit>> graph = new HashMap<>();
    for (RoomExit exit : exits) {
      graph.computeIfAbsent(exit.fromRoomId(), k -> new ArrayList<>()).add(exit);
      // add reverse edge
      RoomExit reverse =
          new RoomExit(exit.toRoomId(), exit.fromRoomId(), exit.cost(), exit.spacingMultiplier());
      graph.computeIfAbsent(exit.toRoomId(), k -> new ArrayList<>()).add(reverse);
    }
    return graph;
  }

  private List<Long> dijkstra(Map<Long, List<RoomExit>> graph, Long start, Long end) {
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
      for (RoomExit exit : graph.getOrDefault(current, Collections.emptyList())) {
        Long neighbor = exit.toRoomId();
        int cost = (int) Math.round(exit.cost() * exit.spacingMultiplier());
        int alt = dist.get(current) + cost;
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
