package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Exit data for the normalized LOOK view payload. */
public record LookViewExit(String label, String description) {
  public LookViewExit {
    Objects.requireNonNull(label, "label must not be null");
    Objects.requireNonNull(description, "description must not be null");
  }
}
