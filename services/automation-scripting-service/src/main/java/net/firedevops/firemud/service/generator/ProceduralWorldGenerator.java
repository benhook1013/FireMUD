package net.firedevops.firemud.service.generator;

import java.util.List;

/** Generates simple dungeon layouts. */
public interface ProceduralWorldGenerator {
  /**
   * Generate a dungeon with the given number of rooms.
   *
   * @param rooms number of rooms to create
   * @param seed random seed for repeatability
   * @return list of generated rooms
   */
  List<GeneratedRoom> generateDungeon(int rooms, long seed);
}
