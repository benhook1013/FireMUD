package net.firedevops.firemud.automationscripting.service.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DungeonGeneratorTest {
  @Test
  void generateDungeonCreatesRequestedRooms() {
    Generator gen = new DungeonGenerator();
    GenerationParams params = new GenerationParams(42L, 5, Map.of());
    GenerationResult result = gen.generate(params);
    List<GeneratedRoom> rooms = result.rooms();
    assertEquals(5, rooms.size());
    assertFalse(rooms.stream().skip(1).anyMatch(r -> r.connectedTo() == 0));
    assertEquals(0, rooms.get(0).x());
  }

  @Test
  void invalidParamsReturnError() {
    Generator gen = new DungeonGenerator();
    GenerationParams params = new GenerationParams(42L, 0, Map.of());
    GenerationResult result = gen.generate(params);
    assertEquals(0, result.rooms().size());
    assertEquals("VALIDATION_FAILED", result.error().code());
  }
}
