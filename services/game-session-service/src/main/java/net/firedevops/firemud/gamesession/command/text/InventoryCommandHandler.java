package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.ItemMutationResultOutput;
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
  private final GameLogicClient gameLogicClient;

  @Timed(value = "gamesession.command.inventory")
  public InventoryCommandHandlingResult handle(SessionContext context, TextCommand command) {
    return handle(context, command, null);
  }

  public InventoryCommandHandlingResult handle(
      SessionContext context, TextCommand command, String effectId) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case INVENTORY -> describeInventorySurface(context, command);
      case GET -> mutateItem(context, command, true, effectId);
      case DROP -> mutateItem(context, command, false, effectId);
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

  private InventoryCommandHandlingResult describeInventorySurface(
      SessionContext context, TextCommand command) {
    if (isRoomInventoryRequest(command)) {
      return describeRoomInventory(context);
    }
    return describeInventory(context);
  }

  private InventoryCommandHandlingResult describeInventory(SessionContext context) {
    try {
      var response = gameLogicClient.queryInventory(context);
      if (response.hasError()) {
        return inventoryUnavailable(
            StringUtils.hasText(response.getError().getMessage())
                ? response.getError().getMessage()
                : "Inventory service unavailable");
      }
      List<String> lines = formatInventoryLines(response.getItemsList());
      return new InventoryCommandHandlingResult(
          CommandEnqueueResult.success(),
          List.of(
              PlayerOutput.view(
                  new InventoryViewOutput(
                      InventoryViewOutput.Source.INVENTORY,
                      "Inventory:",
                      lines,
                      response.getItemsList().stream()
                          .map(ItemPayloadSupport::toItemEntry)
                          .toList()))));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Inventory query failed tenantId={} characterId={}",
          context.tenantId(),
          context.characterId(),
          ex);
      return inventoryUnavailable("Inventory service unavailable");
    }
  }

  private InventoryCommandHandlingResult describeRoomInventory(SessionContext context) {
    try {
      var response = gameLogicClient.listRoomGroundInventory(context, context.roomInstanceId());
      if (response.hasError()) {
        return inventoryUnavailable(
            StringUtils.hasText(response.getError().getMessage())
                ? response.getError().getMessage()
                : "Room inventory unavailable");
      }
      List<String> lines = formatRoomInventoryLines(response.getItemsList());
      return new InventoryCommandHandlingResult(
          CommandEnqueueResult.success(),
          List.of(
              PlayerOutput.view(
                  new InventoryViewOutput(
                      InventoryViewOutput.Source.ROOM_GROUND,
                      "Room Inventory:",
                      lines,
                      response.getItemsList().stream()
                          .map(ItemPayloadSupport::toItemEntry)
                          .toList()))));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Room inventory query failed tenantId={} gameInstanceId={} roomInstanceId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.roomInstanceId(),
          ex);
      return inventoryUnavailable("Room inventory unavailable");
    }
  }

  private List<String> formatInventoryLines(List<InventoryItem> items) {
    if (items.isEmpty()) {
      return List.of("You are not carrying anything.");
    }
    return items.stream().map(this::formatInventoryItem).collect(Collectors.toList());
  }

  private List<String> formatRoomInventoryLines(List<RoomGroundInventoryItem> items) {
    List<String> lines = items.stream().map(this::formatRoomInventoryItem).toList();
    return lines.isEmpty() ? List.of("There is nothing on the ground here.") : lines;
  }

  private String formatInventoryItem(InventoryItem item) {
    StringBuilder line = new StringBuilder();
    line.append("- ").append(item.getItemName());
    appendCompactReference(line, ContainerIdentitySupport.compactReference(item));
    if (item.getQuantity() > 1) {
      line.append(" x").append(item.getQuantity());
    }
    if (StringUtils.hasText(item.getItemDescription())) {
      line.append(" (").append(item.getItemDescription()).append(")");
    }
    return line.toString();
  }

  private String formatRoomInventoryItem(RoomGroundInventoryItem item) {
    StringBuilder line = new StringBuilder();
    line.append("- ").append(item.getItemName());
    appendCompactReference(line, item.getVisibleRef());
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
      SessionContext context, TextCommand command, boolean pickup, String effectId) {
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
    TextCommandPayload.ItemReference itemReference = command.itemReferencePayload().orElseThrow();
    if (itemReference.quantity() <= 0) {
      return inventoryMutationFailure(
          "INVALID_ARGUMENT", itemReference.reference(), verb + " quantity must be positive");
    }
    String itemReferenceValue = itemReference.reference();
    try {
      if (pickup) {
        var response =
            StringUtils.hasText(effectId)
                ? gameLogicClient.pickupVisibleRoomItem(
                    context, itemReferenceValue, itemReference.quantity(), effectId)
                : gameLogicClient.pickupVisibleRoomItem(
                    context, itemReferenceValue, itemReference.quantity());
        if (response.hasError()) {
          return inventoryMutationFailure(
              errorCode(response.getError().getCode()),
              itemReferenceValue,
              stackSelectionGuidance(
                  response.getError().getMessage(),
                  itemReferenceValue,
                  "Use INV HERE to find the explicit stack ref and retry with that ref."));
        }
        return inventoryMutationSuccess(
            context,
            "GET",
            ItemPayloadSupport.withFallback(
                ItemPayloadSupport.toItemEntry(response.getInventoryItem()),
                displayItemName(response.getInventoryItem(), itemReferenceValue),
                itemReference.quantity()),
            ItemPayloadSupport.roomGroundHolder(),
            ItemPayloadSupport.inventoryHolder());
      }

      var response =
          StringUtils.hasText(effectId)
              ? gameLogicClient.dropCarriedItem(
                  context, itemReferenceValue, itemReference.quantity(), effectId)
              : gameLogicClient.dropCarriedItem(
                  context, itemReferenceValue, itemReference.quantity());
      if (response.hasError()) {
        return inventoryMutationFailure(
            errorCode(response.getError().getCode()),
            itemReferenceValue,
            stackSelectionGuidance(
                response.getError().getMessage(),
                itemReferenceValue,
                "Use INVENTORY to find the explicit stack ref and retry with that ref."));
      }
      return inventoryMutationSuccess(
          context,
          "DROP",
          ItemPayloadSupport.withFallback(
              ItemPayloadSupport.toItemEntry(response.getRoomGroundItem()),
              displayItemName(response.getRoomGroundItem(), itemReferenceValue),
              itemReference.quantity()),
          ItemPayloadSupport.inventoryHolder(),
          ItemPayloadSupport.roomGroundHolder());
    } catch (RuntimeException ex) {
      LOG.warn(
          "Inventory mutation failed tenantId={} characterId={} verb={} itemReference={}",
          context.tenantId(),
          context.characterId(),
          verb,
          itemReferenceValue,
          ex);
      return inventoryUnavailable("Inventory service unavailable");
    }
  }

  private InventoryCommandHandlingResult inventoryMutationSuccess(
      SessionContext context,
      String verb,
      InventoryViewOutput.ItemEntry item,
      ItemMutationResultOutput.HolderContext source,
      ItemMutationResultOutput.HolderContext target) {
    List<PlayerOutput> outputs = new ArrayList<>();
    outputs.add(PlayerOutput.notice(new ItemMutationResultOutput(verb, item, source, target)));
    appendAcceptedOutputs(outputs, describeInventory(context));
    appendAcceptedOutputs(outputs, describeRoomInventory(context));
    return new InventoryCommandHandlingResult(CommandEnqueueResult.success(), outputs);
  }

  private void appendAcceptedOutputs(
      List<PlayerOutput> outputs, InventoryCommandHandlingResult refreshResult) {
    if (refreshResult.commandResult().accepted()) {
      outputs.addAll(refreshResult.outputs());
    }
  }

  private InventoryCommandHandlingResult inventoryMutationFailure(
      String errorCode, String itemReference, String reason) {
    String message =
        StringUtils.hasText(reason) ? reason : itemReference + " could not be completed";
    return new InventoryCommandHandlingResult(
        CommandEnqueueResult.failure(errorCode, message),
        List.of(PlayerOutput.error(errorCode, message)));
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

  private void appendCompactReference(StringBuilder line, String compactReference) {
    if (StringUtils.hasText(compactReference)) {
      line.append(" [").append(compactReference).append("]");
    }
  }

  private boolean isRoomInventoryRequest(TextCommand command) {
    return command.args().size() == 1 && "HERE".equalsIgnoreCase(command.args().get(0));
  }

  private String stackSelectionGuidance(String reason, String itemReference, String guidance) {
    if (reason != null && reason.contains("explicit stack selection required")) {
      return "Multiple stack families match \"" + itemReference + "\". " + guidance;
    }
    return reason;
  }

  private String errorCode(String errorCode) {
    return StringUtils.hasText(errorCode) ? errorCode : "INVENTORY_UNAVAILABLE";
  }
}
