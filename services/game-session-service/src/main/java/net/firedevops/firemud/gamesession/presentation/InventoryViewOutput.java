package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured inventory transcript for the first item-interaction loop. */
public record InventoryViewOutput(Source source, String title, List<String> lines)
    implements PlayerOutputPayload {
  public enum Source {
    INVENTORY,
    EQUIPMENT,
    CONTAINER
  }

  public InventoryViewOutput {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(title, "title must not be null");
    lines = List.copyOf(lines == null ? List.of() : lines);
  }
}
