package net.firedevops.firemud.gamesession.presentation;

import java.util.Map;
import java.util.Objects;

/** Structured application-level command error prior to final protocol rendering. */
public record ErrorOutput(
    String code, String message, String messageKey, Map<String, String> arguments)
    implements PlayerOutputPayload {
  public ErrorOutput(String code, String message) {
    this(code, message, null, Map.of());
  }

  public ErrorOutput {
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(message, "message must not be null");
    arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
  }
}
