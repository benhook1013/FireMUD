package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the first inventory command surface in the text session layer. */
@Component
@RequiredArgsConstructor
public class InventoryCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(InventoryCommandHandler.class);
  private final EntityManagementClient entityManagementClient;

  @Timed(value = "gamesession.command.inventory")
  public InventoryCommandHandlingResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case INVENTORY -> describeInventory(context);
      case GET -> mutateItem(context, command, true);
      case DROP -> mutateItem(context, command, false);
      default ->
          new InventoryCommandHandlingResult(
              CommandEnqueueResult.failure("INVALID_COMMAND", "Unsupported inventory command"),
              List.of(
                  PlayerOutput.error(
                      "INVALID_COMMAND",
                      "Unsupported inventory command",
                      "error.inventory.invalid-command",
                      Map.of())));
    };
  }

  private InventoryCommandHandlingResult describeInventory(SessionContext context) {
    try {
      var response =
          entityManagementClient.queryInventory(
              Long.toString(context.tenantId()), Long.toString(context.characterId()));
      if (response.hasError()) {
        return inventoryUnavailable(
            StringUtils.hasText(response.getError().getMessage())
                ? response.getError().getMessage()
                : "Inventory service unavailable");
      }
      List<String> lines = formatInventoryLines(response.getItemsList());
      return new InventoryCommandHandlingResult(
          CommandEnqueueResult.success(),
          List.of(PlayerOutput.view(new InventoryViewOutput("Inventory:", lines))));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Inventory query failed tenantId={} characterId={}",
          context.tenantId(),
          context.characterId(),
          ex);
      return inventoryUnavailable("Inventory service unavailable");
    }
  }

  private List<String> formatInventoryLines(List<InventoryItem> items) {
    if (items.isEmpty()) {
      return List.of("You are not carrying anything.");
    }
    return items.stream().map(this::formatInventoryItem).collect(Collectors.toList());
  }

  private String formatInventoryItem(InventoryItem item) {
    StringBuilder line = new StringBuilder();
    line.append("- ").append(item.getItemName());
    if (item.getQuantity() > 1) {
      line.append(" x").append(item.getQuantity());
    }
    if (StringUtils.hasText(item.getItemDescription())) {
      line.append(" (").append(item.getItemDescription()).append(")");
    }
    return line.toString();
  }

  private InventoryCommandHandlingResult inventoryUnavailable(String reason) {
    return new InventoryCommandHandlingResult(
        CommandEnqueueResult.failure("INVENTORY_UNAVAILABLE", reason),
        List.of(
            PlayerOutput.error(
                "INVENTORY_UNAVAILABLE", reason, "error.inventory.unavailable", Map.of())));
  }

  private InventoryCommandHandlingResult mutateItem(
      SessionContext context, TextCommand command, boolean pickup) {
    String verb = pickup ? "GET" : "DROP";
    if (command.itemReferencePayload().isEmpty()) {
      return new InventoryCommandHandlingResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", verb + " command requires an item"),
          List.of(
              PlayerOutput.error(
                  "INVALID_ARGUMENT",
                  verb + " command requires an item",
                  "error.inventory.item-required",
                  Map.of("verb", verb))));
    }
    String itemReference = command.itemReferencePayload().orElseThrow().reference();
    try {
      if (pickup) {
        var roomEntities =
            entityManagementClient.listRoomEntities(
                Long.toString(context.tenantId()),
                Long.toString(context.gameInstanceId()),
                context.roomInstanceId());
        if (roomEntities.hasError()) {
          return inventoryUnavailable(
              StringUtils.hasText(roomEntities.getError().getMessage())
                  ? roomEntities.getError().getMessage()
                  : "Room entities unavailable");
        }
        Optional<ResolvedItem> resolved =
            findRoomGroundItem(roomEntities.getEntitiesList(), itemReference);
        if (resolved.isEmpty()) {
          return inventoryMutationFailure(
              "INVALID_ARGUMENT", itemReference, "No room item matches \"" + itemReference + "\"");
        }
        ResolvedItem item = resolved.orElseThrow();
        var response =
            entityManagementClient.pickupItemFromRoom(
                Long.toString(context.tenantId()),
                Long.toString(context.characterId()),
                Long.toString(context.gameInstanceId()),
                context.roomInstanceId(),
                item.itemId(),
                1);
        if (response.hasError()) {
          return inventoryMutationFailure(
              errorCode(response.getError().getCode()),
              itemReference,
              response.getError().getMessage());
        }
        return inventoryMutationSuccess(
            context, "GET", displayItemName(response.getInventoryItem(), item.itemName()));
      }

      var inventoryResponse =
          entityManagementClient.queryInventory(
              Long.toString(context.tenantId()), Long.toString(context.characterId()));
      if (inventoryResponse.hasError()) {
        return inventoryUnavailable(
            StringUtils.hasText(inventoryResponse.getError().getMessage())
                ? inventoryResponse.getError().getMessage()
                : "Inventory unavailable");
      }
      Optional<ResolvedItem> resolved =
          findCarriedItem(inventoryResponse.getItemsList(), itemReference);
      if (resolved.isEmpty()) {
        return inventoryMutationFailure(
            "INVALID_ARGUMENT", itemReference, "No carried item matches \"" + itemReference + "\"");
      }
      ResolvedItem item = resolved.orElseThrow();
      var response =
          entityManagementClient.dropItemToRoom(
              Long.toString(context.tenantId()),
              Long.toString(context.characterId()),
              Long.toString(context.gameInstanceId()),
              context.roomInstanceId(),
              item.itemId(),
              1);
      if (response.hasError()) {
        return inventoryMutationFailure(
            errorCode(response.getError().getCode()),
            itemReference,
            response.getError().getMessage());
      }
      return inventoryMutationSuccess(
          context, "DROP", displayItemName(response.getRoomGroundItem(), item.itemName()));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Inventory mutation failed tenantId={} characterId={} verb={} itemReference={}",
          context.tenantId(),
          context.characterId(),
          verb,
          itemReference,
          ex);
      return inventoryUnavailable("Inventory service unavailable");
    }
  }

  private InventoryCommandHandlingResult inventoryMutationSuccess(
      SessionContext context, String verb, String itemName) {
    List<PlayerOutput> outputs = new ArrayList<>();
    outputs.add(PlayerOutput.message(successMessage(verb, itemName)));
    InventoryCommandHandlingResult refreshedInventory = describeInventory(context);
    if (refreshedInventory.commandResult().accepted()) {
      outputs.addAll(refreshedInventory.outputs());
    }
    return new InventoryCommandHandlingResult(CommandEnqueueResult.success(), outputs);
  }

  private InventoryCommandHandlingResult inventoryMutationFailure(
      String errorCode, String itemReference, String reason) {
    String message =
        StringUtils.hasText(reason) ? reason : itemReference + " could not be completed";
    return new InventoryCommandHandlingResult(
        CommandEnqueueResult.failure(errorCode, message),
        List.of(PlayerOutput.error(errorCode, message)));
  }

  private String successMessage(String verb, String itemName) {
    String safeName = StringUtils.hasText(itemName) ? itemName : "item";
    return switch (verb) {
      case "GET" -> "You pick up " + safeName + ".";
      case "DROP" -> "You drop " + safeName + ".";
      default -> "You " + verb.toLowerCase() + " " + safeName + ".";
    };
  }

  private String displayItemName(InventoryItem item, String fallback) {
    if (item == null) {
      return fallback;
    }
    return StringUtils.hasText(item.getItemName()) ? item.getItemName() : fallback;
  }

  private String displayItemName(RoomGroundInventoryItem item, String fallback) {
    if (item == null) {
      return fallback;
    }
    return StringUtils.hasText(item.getItemName()) ? item.getItemName() : fallback;
  }

  private Optional<ResolvedItem> findRoomGroundItem(List<RoomEntity> entities, String reference) {
    return entities.stream()
        .filter(entity -> entity.getEntityType() == EntityType.ITEM)
        .filter(entity -> entity.getStateFlagsList().contains("room-ground"))
        .filter(entity -> entity.getDisplayName().equalsIgnoreCase(reference))
        .findFirst()
        .map(
            entity -> new ResolvedItem(parseItemId(entity.getEntityId()), entity.getDisplayName()));
  }

  private Optional<ResolvedItem> findCarriedItem(List<InventoryItem> items, String reference) {
    return items.stream()
        .filter(item -> item.getItemName().equalsIgnoreCase(reference))
        .findFirst()
        .map(item -> new ResolvedItem(item.getItemId(), item.getItemName()));
  }

  private String parseItemId(String entityId) {
    if (!StringUtils.hasText(entityId)) {
      return "";
    }
    int lastColon = entityId.lastIndexOf(':');
    return lastColon < 0 ? entityId : entityId.substring(lastColon + 1);
  }

  private String errorCode(String errorCode) {
    return StringUtils.hasText(errorCode) ? errorCode : "INVENTORY_UNAVAILABLE";
  }

  private record ResolvedItem(String itemId, String itemName) {}
}
