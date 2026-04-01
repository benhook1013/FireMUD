package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Structured prompt/state payload. */
public record PromptOutput(String text) implements PlayerOutputPayload {
  public PromptOutput {
    Objects.requireNonNull(text, "text must not be null");
  }
}
