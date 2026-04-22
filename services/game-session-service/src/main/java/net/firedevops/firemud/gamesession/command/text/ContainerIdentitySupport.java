package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
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
        || matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(compactReference(item), reference);
  }

  public static boolean matchesReference(EquipmentItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(compactReference(item), reference)
        || matchesReference(item.getSlot(), reference);
  }

  public static boolean matchesReference(ContainerItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference);
  }

  public static boolean matchesExplicitReference(InventoryItem item, String reference) {
    return matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(compactReference(item), reference);
  }

  public static boolean matchesExplicitReference(ContainerItem item, String reference) {
    return matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference);
  }

  public static boolean matchesReference(RoomEntity entity, String reference) {
    return matchesReference(parseItemId(entity.getEntityId()), reference)
        || matchesReference(entity.getDisplayName(), reference)
        || matchesReference(entity.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(entity), reference)
        || matchesReference(compactReference(entity), reference);
  }

  public static boolean matchesReference(RoomGroundInventoryItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(compactReference(item), reference);
  }

  public static String compactReference(InventoryItem item) {
    return item.getVisibleRef();
  }

  public static String compactReference(EquipmentItem item) {
    return item.getVisibleRef();
  }

  public static String compactReference(RoomEntity entity) {
    return entity.getVisibleRef();
  }

  public static String compactReference(RoomGroundInventoryItem item) {
    return item.getVisibleRef();
  }

  public static String compactReference(ContainerItem item) {
    return item.getVisibleRef();
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

  private static String parseItemId(String entityId) {
    if (!StringUtils.hasText(entityId)) {
      return "";
    }
    int lastColon = entityId.lastIndexOf(':');
    return lastColon < 0 ? entityId : entityId.substring(lastColon + 1);
  }

  private static String resolveContainerInstanceId(RoomEntity entity) {
    return StringUtils.hasText(extractContainerInstanceId(entity))
        ? extractContainerInstanceId(entity)
        : parseItemId(entity.getEntityId());
  }

  public static String resolveContainerInstanceId(RoomGroundInventoryItem item) {
    return resolveContainerInstanceId(item.getContainerInstanceId(), item.getItemId());
  }

  private static String extractContainerInstanceId(RoomEntity entity) {
    return entity.getStateFlagsList().stream()
        .filter(flag -> flag.startsWith("container-instance:"))
        .map(flag -> flag.substring("container-instance:".length()))
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse("");
  }
}
