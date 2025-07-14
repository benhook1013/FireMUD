package net.firedevops.firemud.service.generator;

import java.util.Map;

/** Parameters for procedural generation. */
public record GenerationParams(long seed, int rooms, Map<String, String> params) {}
