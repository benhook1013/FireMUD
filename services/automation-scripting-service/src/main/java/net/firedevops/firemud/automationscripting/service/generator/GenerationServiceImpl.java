package net.firedevops.firemud.automationscripting.service.generator;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Default implementation invoking a generator then running hooks. */
@Service
public class GenerationServiceImpl implements GenerationService {
  private static final Logger logger = LoggerFactory.getLogger(GenerationServiceImpl.class);

  private final GeneratorRegistry registry;
  private final List<GenerationHook> hooks;

  public GenerationServiceImpl(GeneratorRegistry registry, List<GenerationHook> hooks) {
    this.registry = registry;
    this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
  }

  @Override
  @Timed(value = "generation.generate")
  public GenerationResult generate(String generatorName, GenerationParams params) {
    Generator generator = registry.get(generatorName);
    if (generator == null) {
      return new GenerationResult(
          List.of(), new GenerationErrorDetail("UNKNOWN_GENERATOR", generatorName));
    }
    GenerationResult result = generator.generate(params);
    if (result.error() == null) {
      for (GenerationHook hook : hooks) {
        try {
          hook.afterGeneration(params, result);
        } catch (Exception ex) {
          logger.debug("Generation hook failed", ex);
        }
      }
    }
    return result;
  }
}
