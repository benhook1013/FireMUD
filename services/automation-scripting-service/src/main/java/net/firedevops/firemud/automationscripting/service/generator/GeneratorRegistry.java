package net.firedevops.firemud.automationscripting.service.generator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Collects and provides access to available procedural generators. */
@Component
public class GeneratorRegistry {
  private final Map<String, Generator> generators = new HashMap<>();

  public GeneratorRegistry(List<Generator> beans) {
    for (Generator g : beans) {
      generators.put(g.getName(), g);
    }
  }

  /** Returns the generator by name or {@code null} if not found. */
  public Generator get(String name) {
    return generators.get(name);
  }
}
