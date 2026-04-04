package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured prompt/state payload. */
public record PromptOutput(String text, List<PromptField> fields) implements PlayerOutputPayload {
  public PromptOutput {
    Objects.requireNonNull(text, "text must not be null");
    fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
  }
}
