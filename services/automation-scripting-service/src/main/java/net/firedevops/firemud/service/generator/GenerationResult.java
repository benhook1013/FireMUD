package net.firedevops.firemud.service.generator;

import java.util.List;

/** Result of procedural generation. */
public record GenerationResult(List<GeneratedRoom> rooms, GenerationErrorDetail error) {}
