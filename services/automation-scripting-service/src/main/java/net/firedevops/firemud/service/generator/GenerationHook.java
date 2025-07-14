package net.firedevops.firemud.service.generator;

/** Hook invoked after a generator successfully produces rooms. */
public interface GenerationHook {
  void afterGeneration(GenerationParams params, GenerationResult result);
}
