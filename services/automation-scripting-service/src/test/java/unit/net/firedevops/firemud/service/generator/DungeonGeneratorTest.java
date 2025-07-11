package net.firedevops.firemud.service.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class DungeonGeneratorTest {
  @Test
  void generateDungeonCreatesRequestedRooms() {
    ProceduralWorldGenerator gen = new DungeonGenerator();
    List<GeneratedRoom> rooms = gen.generateDungeon(5, 42L);
    assertEquals(5, rooms.size());
    assertFalse(rooms.stream().skip(1).anyMatch(r -> r.getConnectedTo() == 0));
  }
}
