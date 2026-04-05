package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured inventory transcript for the first item-interaction loop. */
public record InventoryViewOutput(String title, List<String> lines) implements PlayerOutputPayload {
  public InventoryViewOutput {
    Objects.requireNonNull(title, "title must not be null");
    lines = List.copyOf(lines == null ? List.of() : lines);
  }
}
