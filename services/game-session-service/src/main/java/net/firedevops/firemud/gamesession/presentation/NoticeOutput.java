package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Structured non-command player notice payload. */
public record NoticeOutput(String text) implements PlayerOutputPayload {
  public NoticeOutput {
    Objects.requireNonNull(text, "text must not be null");
  }
}
