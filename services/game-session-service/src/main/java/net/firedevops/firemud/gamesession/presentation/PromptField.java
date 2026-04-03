package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Minimal structured prompt/status field carried alongside rendered prompt text. */
public record PromptField(String key, String value) {
  public PromptField {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(value, "value must not be null");
  }
}
