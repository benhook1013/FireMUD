package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured inventory transcript for the first item-interaction loop. */
public record InventoryViewOutput(
    Source source, String title, List<String> lines, ViewContext context, List<ItemEntry> entries)
    implements PlayerOutputPayload {
  public enum Source {
    INVENTORY,
    ROOM_GROUND,
    EQUIPMENT,
    CONTAINER
  }

  public InventoryViewOutput(Source source, String title, List<String> lines) {
    this(source, title, lines, null, List.of());
  }

  public InventoryViewOutput(
      Source source, String title, List<String> lines, List<ItemEntry> entries) {
    this(source, title, lines, null, entries);
  }

  public InventoryViewOutput {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(title, "title must not be null");
    lines = List.copyOf(lines == null ? List.of() : lines);
    entries = List.copyOf(entries == null ? List.of() : entries);
  }

  public record ViewContext(String containerInstanceId, String displayName, String visibleRef) {
    public ViewContext {
      containerInstanceId = normalize(containerInstanceId);
      displayName = normalize(displayName);
      visibleRef = normalize(visibleRef);
    }
  }

  public record ItemEntry(
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String visibleRef,
      String itemName,
      String itemDescription,
      int quantity,
      String slot) {
    public ItemEntry {
      itemId = normalize(itemId);
      itemInstanceId = normalize(itemInstanceId);
      containerInstanceId = normalize(containerInstanceId);
      visibleRef = normalize(visibleRef);
      itemName = normalize(itemName);
      itemDescription = normalize(itemDescription);
      slot = normalize(slot);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}
