package net.firedevops.firemud.gamesession.testsupport;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;

/** Shared explicit entity-management request assertions for chained gameplay proof. */
public final class GameplayEntityAssertions {

  private GameplayEntityAssertions() {}

  public static void assertPickup(
      Optional<PickupItemFromRoomRequest> maybeRequest,
      String tenantId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId,
      String itemId) {
    PickupItemFromRoomRequest request = required(maybeRequest, "pickup request");
    requireEquals(request.getTenantId(), tenantId, "pickup tenantId");
    requireEquals(request.getCharacterId(), characterId, "pickup characterId");
    if (gameInstanceId != null) {
      requireEquals(request.getGameInstanceId(), gameInstanceId, "pickup gameInstanceId");
    }
    requireEquals(request.getRoomInstanceId(), roomInstanceId, "pickup roomInstanceId");
    requireEquals(request.getItemId(), itemId, "pickup itemId");
    requireEquals(request.getQuantity(), 1, "pickup quantity");
    requireNotBlank(request.getEffectId(), "pickup effectId");
  }

  public static void assertDrop(
      Optional<DropItemToRoomRequest> maybeRequest,
      String tenantId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId,
      String itemId) {
    DropItemToRoomRequest request = required(maybeRequest, "drop request");
    requireEquals(request.getTenantId(), tenantId, "drop tenantId");
    requireEquals(request.getCharacterId(), characterId, "drop characterId");
    if (gameInstanceId != null) {
      requireEquals(request.getGameInstanceId(), gameInstanceId, "drop gameInstanceId");
    }
    requireEquals(request.getRoomInstanceId(), roomInstanceId, "drop roomInstanceId");
    requireEquals(request.getItemId(), itemId, "drop itemId");
    requireEquals(request.getQuantity(), 1, "drop quantity");
    requireNotBlank(request.getEffectId(), "drop effectId");
  }

  public static void assertPut(
      Optional<PutItemIntoContainerRequest> maybeRequest,
      String tenantId,
      String characterId,
      String containerInstanceId,
      String itemId,
      String itemInstanceId) {
    PutItemIntoContainerRequest request = required(maybeRequest, "put request");
    requireEquals(request.getTenantId(), tenantId, "put tenantId");
    requireEquals(request.getCharacterId(), characterId, "put characterId");
    requireEquals(request.getContainerInstanceId(), containerInstanceId, "put containerInstanceId");
    requireEquals(request.getItemId(), itemId, "put itemId");
    requireEquals(request.getItemInstanceId(), itemInstanceId, "put itemInstanceId");
    requireNotBlank(request.getEffectId(), "put effectId");
  }

  public static void assertTake(
      Optional<TakeItemFromContainerRequest> maybeRequest,
      String tenantId,
      String characterId,
      String containerInstanceId,
      String itemId,
      String itemInstanceId) {
    TakeItemFromContainerRequest request = required(maybeRequest, "take request");
    requireEquals(request.getTenantId(), tenantId, "take tenantId");
    requireEquals(request.getCharacterId(), characterId, "take characterId");
    requireEquals(
        request.getContainerInstanceId(), containerInstanceId, "take containerInstanceId");
    requireEquals(request.getItemId(), itemId, "take itemId");
    requireEquals(request.getItemInstanceId(), itemInstanceId, "take itemInstanceId");
    requireNotBlank(request.getEffectId(), "take effectId");
  }

  public static void assertWear(
      Optional<WearEquipmentItemRequest> maybeRequest,
      String tenantId,
      String characterId,
      String itemId,
      String itemInstanceId) {
    WearEquipmentItemRequest request = required(maybeRequest, "wear request");
    requireEquals(request.getTenantId(), tenantId, "wear tenantId");
    requireEquals(request.getCharacterId(), characterId, "wear characterId");
    requireEquals(request.getItemId(), itemId, "wear itemId");
    if (itemInstanceId != null) {
      requireEquals(request.getItemInstanceId(), itemInstanceId, "wear itemInstanceId");
    }
    requireNotBlank(request.getEffectId(), "wear effectId");
  }

  public static void assertRemove(
      Optional<RemoveEquipmentRequest> maybeRequest,
      String tenantId,
      String characterId,
      String slot) {
    RemoveEquipmentRequest request = required(maybeRequest, "remove request");
    requireEquals(request.getTenantId(), tenantId, "remove tenantId");
    requireEquals(request.getCharacterId(), characterId, "remove characterId");
    requireEquals(request.getSlot(), slot, "remove slot");
    requireNotBlank(request.getEffectId(), "remove effectId");
  }

  public static void assertMessage(
      Optional<SendMessageRequest> maybeRequest,
      ChatType chatType,
      String recipientId,
      String content,
      boolean requireEffectId) {
    SendMessageRequest request = required(maybeRequest, "social message request");
    requireEquals(request.getType(), chatType, "social chatType");
    requireEquals(request.getRecipientId(), recipientId, "social recipientId");
    if (content != null) {
      requireEquals(request.getContent(), content, "social content");
    }
    if (requireEffectId) {
      requireNotBlank(request.getEffectId(), "social effectId");
    }
  }

  private static <T> T required(Optional<T> maybeValue, String description) {
    return maybeValue.orElseThrow(() -> new AssertionError("Expected " + description));
  }

  private static void requireEquals(String actual, String expected, String description) {
    if (!actual.equals(expected)) {
      throw new AssertionError(
          "Expected " + description + " to be '" + expected + "' but was '" + actual + "'");
    }
  }

  private static void requireEquals(int actual, int expected, String description) {
    if (actual != expected) {
      throw new AssertionError(
          "Expected " + description + " to be " + expected + " but was " + actual);
    }
  }

  private static void requireEquals(ChatType actual, ChatType expected, String description) {
    if (actual != expected) {
      throw new AssertionError(
          "Expected " + description + " to be " + expected + " but was " + actual);
    }
  }

  private static void requireNotBlank(String actual, String description) {
    if (actual == null || actual.isBlank()) {
      throw new AssertionError("Expected " + description + " to be non-blank");
    }
  }
}
