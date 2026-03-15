package net.firedevops.firemud.automationscripting.service.generator;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.service.PveEncounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simple population hook that spawns NPC encounters based on biome. */
@Component
@RequiredArgsConstructor
public class SpawnPopulationHook implements GenerationHook {
  private static final Logger logger = LoggerFactory.getLogger(SpawnPopulationHook.class);

  private final PveEncounterService encounterService;

  @Override
  public void afterGeneration(GenerationParams params, GenerationResult result) {
    for (GeneratedRoom room : result.rooms()) {
      String biome = room.biome() != null ? room.biome() : "default";
      try {
        encounterService.generateEvent(biome, params.seed() + room.roomId());
      } catch (Exception ex) {
        logger.debug("Failed to populate room {}", room.roomId(), ex);
      }
    }
  }
}
