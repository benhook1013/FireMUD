package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import org.springframework.util.StringUtils;

/** Utilities for resolving and forwarding durable container identities. */
public final class ContainerIdentitySupport {
  private ContainerIdentitySupport() {}

  public static String resolveContainerInstanceId(InventoryItem item) {
    return resolveContainerInstanceId(item.getContainerInstanceId(), item.getItemId());
  }

  public static String resolveContainerInstanceId(EquipmentItem item) {
    return resolveContainerInstanceId(item.getContainerInstanceId(), item.getItemId());
  }

  public static boolean matchesReference(InventoryItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference);
  }

  public static boolean matchesReference(EquipmentItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(item.getSlot(), reference);
  }

  private static String resolveContainerInstanceId(String containerInstanceId, String fallback) {
    return StringUtils.hasText(containerInstanceId)
        ? containerInstanceId
        : (StringUtils.hasText(fallback) ? fallback : "");
  }

  private static boolean matchesReference(String candidate, String reference) {
    return StringUtils.hasText(candidate)
        && StringUtils.hasText(reference)
        && candidate.equalsIgnoreCase(reference);
  }
}
