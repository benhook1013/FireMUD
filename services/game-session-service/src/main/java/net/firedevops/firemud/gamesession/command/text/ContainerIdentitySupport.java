package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
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
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(compactReference(item), reference);
  }

  public static boolean matchesReference(EquipmentItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference)
        || matchesReference(compactReference(item), reference)
        || matchesReference(item.getSlot(), reference);
  }

  public static boolean matchesReference(RoomEntity entity, String reference) {
    return matchesReference(parseItemId(entity.getEntityId()), reference)
        || matchesReference(entity.getDisplayName(), reference)
        || matchesReference(resolveContainerInstanceId(entity), reference)
        || matchesReference(compactReference(entity), reference);
  }

  public static String compactReference(InventoryItem item) {
    return compactReference(item.getItemName(), item.getContainerInstanceId());
  }

  public static String compactReference(EquipmentItem item) {
    return compactReference(item.getItemName(), item.getContainerInstanceId());
  }

  public static String compactReference(RoomEntity entity) {
    return compactReference(entity.getDisplayName(), extractContainerInstanceId(entity));
  }

  public static String compactReference(ContainerItem item) {
    return compactReference(item.getItemName(), item.getContainerInstanceId());
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

  private static String compactReference(String itemName, String containerInstanceId) {
    if (!StringUtils.hasText(itemName) || !StringUtils.hasText(containerInstanceId)) {
      return "";
    }
    String normalizedName = normalizeReferenceToken(itemName);
    if (!StringUtils.hasText(normalizedName)) {
      return "";
    }
    String suffix = extractReferenceSuffix(containerInstanceId);
    return StringUtils.hasText(suffix) ? normalizedName + suffix : "";
  }

  private static String extractReferenceSuffix(String containerInstanceId) {
    String digits = trailingDigits(containerInstanceId);
    if (StringUtils.hasText(digits)) {
      return digits;
    }
    return normalizeReferenceToken(containerInstanceId);
  }

  private static String trailingDigits(String value) {
    int start = value.length();
    while (start > 0 && Character.isDigit(value.charAt(start - 1))) {
      start--;
    }
    return start < value.length() ? value.substring(start) : "";
  }

  private static String normalizeReferenceToken(String value) {
    StringBuilder normalized = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = Character.toLowerCase(value.charAt(i));
      if (Character.isLetterOrDigit(ch)) {
        normalized.append(ch);
      }
    }
    return normalized.toString();
  }

  private static String resolveContainerInstanceId(RoomEntity entity) {
    return StringUtils.hasText(extractContainerInstanceId(entity))
        ? extractContainerInstanceId(entity)
        : parseItemId(entity.getEntityId());
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
