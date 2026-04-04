package net.firedevops.firemud.gamesession.presentation;

import java.util.Map;
import java.util.Objects;

/** Plain message payload used for transcript-oriented player-visible lines. */
public record TextMessageOutput(String text, String messageKey, Map<String, String> arguments)
    implements PlayerOutputPayload {
  public TextMessageOutput(String text) {
    this(text, null, Map.of());
  }

  public TextMessageOutput {
    Objects.requireNonNull(text, "text must not be null");
    arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
  }
}
