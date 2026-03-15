package net.firedevops.firemud.automationscripting.service.generator;

import java.util.List;

/** Result of procedural generation. */
public record GenerationResult(List<GeneratedRoom> rooms, GenerationErrorDetail error) {
  /**
   * Creates a new generation result.
   *
   * <p>The provided list is defensively copied to avoid exposing internal mutable state.
   *
   * @param rooms generated rooms
   * @param error optional generation error
   */
  public GenerationResult(List<GeneratedRoom> rooms, GenerationErrorDetail error) {
    this.rooms = List.copyOf(rooms);
    this.error = error;
  }
}
