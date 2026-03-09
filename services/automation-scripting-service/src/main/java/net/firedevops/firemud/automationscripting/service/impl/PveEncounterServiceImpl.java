package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.SplittableRandom;
import lombok.extern.slf4j.Slf4j;
import net.firedevops.firemud.automationscripting.model.PveEvent;
import net.firedevops.firemud.automationscripting.service.PveEncounterService;
import org.springframework.stereotype.Service;

/**
 * Basic PvE encounter generator that uses a predefined set of events per region. Encounters are
 * seeded so results can be reproduced during testing.
 */
@Service
@Slf4j
public class PveEncounterServiceImpl implements PveEncounterService {
  private static final List<String> FOREST_EVENTS =
      List.of("wild boar attack", "bandit ambush", "swarm of insects");
  private static final List<String> CAVE_EVENTS =
      List.of("goblin raiders", "loose rocks", "toxic fumes");

  @Override
  @Timed(value = "pve.generateEvent")
  public PveEvent generateEvent(String region, long seed) {
    SplittableRandom rnd = new SplittableRandom(seed);
    List<String> pool;
    switch (region.toLowerCase()) {
      case "forest" -> pool = FOREST_EVENTS;
      case "cave" -> pool = CAVE_EVENTS;
      default -> {
        log.warn("Unknown region '{}' using generic encounter", region);
        pool = List.of("sudden storm", "minor quake", "roaming beast");
      }
    }
    String desc = pool.get(rnd.nextInt(pool.size()));
    return new PveEvent(region, desc);
  }
}
