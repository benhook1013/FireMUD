package net.firedevops.firemud.automationscripting.service.generator;

import java.util.List;
import java.util.Map;

/** DTO representing a procedurally generated room with metadata for editor overlays. */
public record GeneratedRoom(
    long roomId,
    long connectedTo,
    int x,
    int y,
    Map<String, Long> exitMap,
    List<String> tags,
    String biome,
    int elevation,
    Long regionId) {
  /**
   * Creates a generated room. The provided collections are defensively copied to avoid exposing
   * mutable internal state to callers.
   *
   * @param roomId unique room identifier
   * @param connectedTo adjacent room identifier
   * @param x x-coordinate within the generated map
   * @param y y-coordinate within the generated map
   * @param exitMap mapping of exits to neighboring room ids
   * @param tags list of metadata tags
   * @param biome biome type name
   * @param elevation elevation relative to map origin
   * @param regionId optional region identifier
   */
  public GeneratedRoom {
    exitMap = Map.copyOf(exitMap);
    tags = List.copyOf(tags);
  }
}
