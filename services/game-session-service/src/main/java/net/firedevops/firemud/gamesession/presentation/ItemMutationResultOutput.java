package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;
import org.springframework.util.StringUtils;

/** Structured payload for canonical item mutation results across inventory surfaces. */
public record ItemMutationResultOutput(
    String action, InventoryViewOutput.ItemEntry item, HolderContext source, HolderContext target)
    implements PlayerOutputPayload {
  public ItemMutationResultOutput {
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(item, "item must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(target, "target must not be null");
  }

  public String text() {
    String itemName = StringUtils.hasText(item.itemName()) ? item.itemName() : "item";
    String quantitySuffix = item.quantity() > 1 ? " x" + item.quantity() : "";
    return switch (action) {
      case "GET" -> "You pick up " + itemName + quantitySuffix + ".";
      case "DROP" -> "You drop " + itemName + quantitySuffix + ".";
      case "PUT" -> "You put " + itemName + quantitySuffix + " into " + holderName(target) + ".";
      case "TAKE" -> "You take " + itemName + quantitySuffix + " from " + holderName(source) + ".";
      case "WEAR" -> "You wear " + itemName + ".";
      case "REMOVE" -> "You remove " + itemName + ".";
      default -> "You " + action.toLowerCase() + " " + itemName + quantitySuffix + ".";
    };
  }

  private static String holderName(HolderContext holder) {
    return StringUtils.hasText(holder.displayName()) ? holder.displayName() : "container";
  }

  public record HolderContext(
      InventoryViewOutput.Source kind,
      String displayName,
      String containerInstanceId,
      String visibleRef,
      String slot) {
    public HolderContext {
      Objects.requireNonNull(kind, "kind must not be null");
      displayName = normalize(displayName);
      containerInstanceId = normalize(containerInstanceId);
      visibleRef = normalize(visibleRef);
      slot = normalize(slot);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}
