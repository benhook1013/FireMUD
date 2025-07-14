package net.firedevops.firemud.service.generator;

/** Service that executes registered procedural generators. */
public interface GenerationService {
  GenerationResult generate(String generatorName, GenerationParams params);
}
