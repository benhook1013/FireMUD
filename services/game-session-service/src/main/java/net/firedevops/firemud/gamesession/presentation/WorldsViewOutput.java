package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured WORLDS browse payload for late rendering. */
public record WorldsViewOutput(List<WorldEntry> worlds) implements PlayerOutputPayload {
  public WorldsViewOutput {
    worlds = List.copyOf(Objects.requireNonNull(worlds, "worlds must not be null"));
  }

  public record WorldEntry(
      int ordinal,
      String slug,
      String displayName,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    public WorldEntry {
      if (ordinal < 1) {
        throw new IllegalArgumentException("ordinal must be at least 1");
      }
      Objects.requireNonNull(slug, "slug must not be null");
      Objects.requireNonNull(displayName, "displayName must not be null");
    }
  }
}
