package net.firedevops.firemud.gamesession.presentation;

import java.util.Map;
import java.util.Objects;

/** Structured non-command player notice payload. */
public record NoticeOutput(String text, String messageKey, Map<String, String> arguments)
    implements PlayerOutputPayload {
  public NoticeOutput(String text) {
    this(text, null, Map.of());
  }

  public NoticeOutput {
    Objects.requireNonNull(text, "text must not be null");
    arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
  }
}
