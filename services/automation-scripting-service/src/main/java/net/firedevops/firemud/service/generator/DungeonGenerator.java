package net.firedevops.firemud.service.generator;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/** Basic procedural generator that creates rooms connected in a random tree. */
@Component
public class DungeonGenerator implements Generator {

  @Override
  public GenerationResult generate(GenerationParams params) {
    Random rnd = new SecureRandom();
    rnd.setSeed(params.seed());
    int rooms = params.rooms();
    List<GeneratedRoom> result = new ArrayList<>();
    if (rooms <= 0) {
      return new GenerationResult(result);
    }
    // first room has no connection
    result.add(new GeneratedRoom(1, 0));
    for (int i = 2; i <= rooms; i++) {
      long connectTo = result.get(rnd.nextInt(result.size())).id();
      result.add(new GeneratedRoom(i, connectTo));
    }
    return new GenerationResult(result);
  }

  @Override
  public List<String> validateParams(GenerationParams params) {
    List<String> errors = new ArrayList<>();
    if (params.rooms() <= 0) {
      errors.add("rooms must be positive");
    }
    return errors;
  }

  @Override
  public String getName() {
    return "SimpleDungeonGenerator";
  }
}
