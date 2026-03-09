package net.firedevops.firemud.automationscripting.service.generator;

import java.util.Map;

/** Parameters for procedural generation. */
public record GenerationParams(long seed, int rooms, Map<String, String> params) {
  /**
   * Creates generation parameters. The provided map is defensively copied to avoid exposing mutable
   * internal state.
   *
   * @param seed random seed for generation
   * @param rooms number of rooms to create
   * @param params additional arbitrary parameters
   */
  public GenerationParams {
    params = Map.copyOf(params);
  }
}
