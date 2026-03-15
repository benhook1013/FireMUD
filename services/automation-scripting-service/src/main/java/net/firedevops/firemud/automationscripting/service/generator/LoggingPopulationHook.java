package net.firedevops.firemud.automationscripting.service.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simple generation hook that logs population events. */
@Component
public class LoggingPopulationHook implements GenerationHook {
  private static final Logger logger = LoggerFactory.getLogger(LoggingPopulationHook.class);

  @Override
  public void afterGeneration(GenerationParams params, GenerationResult result) {
    logger.debug("Populating {} rooms for seed {}", result.rooms().size(), params.seed());
  }
}
