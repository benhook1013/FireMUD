package net.firedevops.firemud.automationscripting.service.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/** Basic procedural generator that creates rooms connected in a random tree. */
@Component
public class DungeonGenerator implements Generator {

  @Override
  public GenerationResult generate(GenerationParams params) {
    List<String> errors = validateParams(params);
    if (!errors.isEmpty()) {
      return new GenerationResult(
          List.of(), new GenerationErrorDetail("VALIDATION_FAILED", String.join(";", errors)));
    }
    Random rnd = new Random(params.seed());
    int rooms = params.rooms();
    List<GeneratedRoom> result = new ArrayList<>();
    if (rooms <= 0) {
      return new GenerationResult(result, null);
    }
    // first room has no connection
    result.add(
        new GeneratedRoom(
            1, 0, 0, 0, java.util.Map.of(), java.util.List.of("start"), "cave", 0, null));
    for (int i = 2; i <= rooms; i++) {
      long connectTo = result.get(rnd.nextInt(result.size())).roomId();
      result.add(
          new GeneratedRoom(
              i, connectTo, i - 1, 0, java.util.Map.of(), java.util.List.of(), "cave", 0, null));
    }
    return new GenerationResult(result, null);
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
