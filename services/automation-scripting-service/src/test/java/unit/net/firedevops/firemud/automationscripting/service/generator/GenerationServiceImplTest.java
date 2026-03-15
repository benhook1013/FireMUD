package net.firedevops.firemud.automationscripting.service.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationServiceImplTest {
  @Test
  void generatorAndHookInvoked() {
    Generator generator = new DungeonGenerator();
    GenerationHook hook = mock(GenerationHook.class);
    GeneratorRegistry registry = new GeneratorRegistry(List.of(generator));
    GenerationService service = new GenerationServiceImpl(registry, List.of(hook));
    GenerationParams params = new GenerationParams(1L, 2, Map.of());
    GenerationResult result = service.generate(generator.getName(), params);
    assertEquals(2, result.rooms().size());
    verify(hook).afterGeneration(params, result);
  }
}
