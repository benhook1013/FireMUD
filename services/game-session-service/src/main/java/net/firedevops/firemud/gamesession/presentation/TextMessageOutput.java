package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Plain message payload used for transcript-oriented player-visible lines. */
public record TextMessageOutput(String text) implements PlayerOutputPayload {
  public TextMessageOutput {
    Objects.requireNonNull(text, "text must not be null");
  }
}
