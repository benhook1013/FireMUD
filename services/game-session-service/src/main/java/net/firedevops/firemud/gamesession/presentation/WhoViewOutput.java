package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured WHO payload for text and first-party rendering. */
public record WhoViewOutput(List<Entry> gods, List<Entry> players) implements PlayerOutputPayload {
  public WhoViewOutput {
    gods = List.copyOf(Objects.requireNonNull(gods, "gods must not be null"));
    players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
  }

  public record Entry(int ordinal, String characterName, String activityState) {
    public Entry {
      if (ordinal < 1) {
        throw new IllegalArgumentException("ordinal must be at least 1");
      }
      Objects.requireNonNull(characterName, "characterName must not be null");
      Objects.requireNonNull(activityState, "activityState must not be null");
    }
  }
}
