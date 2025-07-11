package net.firedevops.firemud.service.generator;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/** Basic procedural generator that creates rooms connected in a random tree. */
@Component
public class DungeonGenerator implements ProceduralWorldGenerator {

  @Override
  public List<GeneratedRoom> generateDungeon(int rooms, long seed) {
    Random rnd = new SecureRandom();
    rnd.setSeed(seed);
    List<GeneratedRoom> result = new ArrayList<>();
    if (rooms <= 0) {
      return result;
    }
    // first room has no connection
    result.add(new GeneratedRoom(1, 0));
    for (int i = 2; i <= rooms; i++) {
      long connectTo = result.get(rnd.nextInt(result.size())).getId();
      result.add(new GeneratedRoom(i, connectTo));
    }
    return result;
  }
}
