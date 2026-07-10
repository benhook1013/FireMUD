package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the first equipment command surface in the text session layer. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
public class EquipmentCommandHandler {
  private final GameLogicClient gameLogicClient;

  public EquipmentCommandHandler(GameLogicClient gameLogicClient) {
    this.gameLogicClient =
        Objects.requireNonNull(gameLogicClient, "gameLogicClient must not be null");
  }

  @Timed(value = "gamesession.command.equipment")
  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    return handle(context, command, null);
  }

  public TextCommandInterpretationResult handle(
      SessionContext context, TextCommand command, String effectId) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case EQUIPMENT -> describeEquipment(context);
      case WEAR -> wear(context, command, effectId);
      case REMOVE -> remove(context, command, effectId);
      default ->
          new TextCommandInterpretationResult(
              CommandEnqueueResult.failure("INVALID_COMMAND", "Unsupported equipment command"),
              List.of(
                  PlayerOutput.error(
                      "INVALID_COMMAND",
                      "Unsupported equipment command",
                      "error.equipment.invalid-command",
                      Map.of())));
    };
  }

  private TextCommandInterpretationResult describeEquipment(SessionContext context) {
    ListEquipmentResponse equipment = gameLogicClient.listEquipment(context);
    if (equipment.hasError()) {
      return equipmentUnavailable(equipment.getError().getMessage());
    }
    List<String> lines =
        equipment.getItemsList().isEmpty()
            ? List.of("You have nothing equipped.")
            : equipment.getItemsList().stream().map(this::formatEquipmentItem).toList();
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.view(
                new InventoryViewOutput(
                    InventoryViewOutput.Source.EQUIPMENT,
                    "Equipment:",
                    lines,
                    equipment.getItemsList().stream().map(this::toEquipmentEntry).toList()))));
  }

  private TextCommandInterpretationResult wear(
      SessionContext context, TextCommand command, String effectId) {
    if (command.itemReferencePayload().isEmpty()) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", "WEAR command requires an item"),
          List.of(
              PlayerOutput.error(
                  "INVALID_ARGUMENT",
                  "WEAR command requires an item",
                  "error.equipment.item-required",
                  Map.of("verb", "WEAR"))));
    }

    TextCommandPayload.ItemReference itemReference = command.itemReferencePayload().orElseThrow();
    if (itemReference.quantity() != 1) {
      return equipmentInvalidArgument("WEAR", "WEAR equips a single carried item at a time");
    }
    EquipmentResolution resolution = resolveCarriedItem(context, itemReference.reference());
    if (resolution.queryUnavailable()) {
      return equipmentUnavailable(resolution.reason());
    }
    if (resolution.item().isEmpty()) {
      return equipmentInvalidArgument(
          "WEAR", "No carried item matches \"" + itemReference.reference() + "\"");
    }

    InventoryItem carried = resolution.item().orElseThrow();
    WearEquipmentItemResponse response =
        StringUtils.hasText(effectId)
            ? gameLogicClient.wearEquipment(
                context, carried.getItemId(), carried.getItemInstanceId(), effectId)
            : gameLogicClient.wearEquipment(
                context, carried.getItemId(), carried.getItemInstanceId());
    if (response.hasError()) {
      return equipmentFailure(response.getError().getCode(), response.getError().getMessage());
    }
    if (!response.hasEquipmentItem()) {
      return equipmentUnavailable("Equipment service unavailable");
    }
    return equipmentSuccess("You wear " + response.getEquipmentItem().getItemName() + ".");
  }

  private TextCommandInterpretationResult remove(
      SessionContext context, TextCommand command, String effectId) {
    if (command.itemReferencePayload().isEmpty()) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", "REMOVE command requires an item"),
          List.of(
              PlayerOutput.error(
                  "INVALID_ARGUMENT",
                  "REMOVE command requires an item",
                  "error.equipment.item-required",
                  Map.of("verb", "REMOVE"))));
    }

    TextCommandPayload.ItemReference itemReference = command.itemReferencePayload().orElseThrow();
    if (itemReference.quantity() != 1) {
      return equipmentInvalidArgument("REMOVE", "REMOVE takes a single equipped item at a time");
    }
    ListEquipmentResponse equipment = gameLogicClient.listEquipment(context);
    if (equipment.hasError()) {
      return equipmentUnavailable(equipment.getError().getMessage());
    }
    Optional<EquipmentItem> resolved =
        findEquippedItem(equipment.getItemsList(), itemReference.reference());
    if (resolved.isEmpty()) {
      return equipmentInvalidArgument(
          "REMOVE", "No equipped item matches \"" + itemReference.reference() + "\"");
    }
    EquipmentItem worn = resolved.orElseThrow();
    RemoveEquipmentResponse response =
        StringUtils.hasText(effectId)
            ? gameLogicClient.removeEquipment(context, worn.getSlot(), effectId)
            : gameLogicClient.removeEquipment(context, worn.getSlot());
    if (response.hasError()) {
      return equipmentFailure(response.getError().getCode(), response.getError().getMessage());
    }
    if (!response.hasEquipmentItem()) {
      return equipmentUnavailable("Equipment service unavailable");
    }
    return equipmentSuccess("You remove " + response.getEquipmentItem().getItemName() + ".");
  }

  private TextCommandInterpretationResult equipmentSuccess(String message) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(), List.of(PlayerOutput.message(message)));
  }

  private TextCommandInterpretationResult equipmentUnavailable(String reason) {
    String message =
        StringUtils.hasText(reason) ? reason : "Equipment service is temporarily unavailable";
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("EQUIPMENT_UNAVAILABLE", message),
        List.of(
            PlayerOutput.error(
                "EQUIPMENT_UNAVAILABLE", message, "error.equipment.unavailable", Map.of())));
  }

  private TextCommandInterpretationResult equipmentFailure(String errorCode, String reason) {
    String code = StringUtils.hasText(errorCode) ? errorCode : "EQUIPMENT_UNAVAILABLE";
    String message =
        StringUtils.hasText(reason) ? reason : "Equipment action could not be completed";
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
  }

  private TextCommandInterpretationResult equipmentInvalidArgument(String verb, String reason) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("INVALID_ARGUMENT", reason),
        List.of(
            PlayerOutput.error(
                "INVALID_ARGUMENT",
                reason,
                "error.equipment.invalid-argument",
                Map.of("verb", verb))));
  }

  private EquipmentResolution resolveCarriedItem(SessionContext context, String reference) {
    var inventory = gameLogicClient.queryInventory(context);
    if (inventory.hasError()) {
      return EquipmentResolution.unavailable(
          StringUtils.hasText(inventory.getError().getMessage())
              ? inventory.getError().getMessage()
              : "Inventory service unavailable");
    }
    Optional<InventoryItem> item =
        inventory.getItemsList().stream()
            .filter(carried -> ContainerIdentitySupport.matchesReference(carried, reference))
            .findFirst();
    return EquipmentResolution.available(item);
  }

  private Optional<EquipmentItem> findEquippedItem(List<EquipmentItem> items, String reference) {
    return items.stream()
        .filter(item -> ContainerIdentitySupport.matchesReference(item, reference))
        .findFirst();
  }

  private String formatEquipmentItem(EquipmentItem item) {
    StringBuilder line = new StringBuilder();
    line.append("- ").append(item.getSlot()).append(": ").append(item.getItemName());
    String compactReference = ContainerIdentitySupport.compactReference(item);
    if (StringUtils.hasText(compactReference)) {
      line.append(" [").append(compactReference).append("]");
    }
    if (StringUtils.hasText(item.getItemDescription())) {
      line.append(" (").append(item.getItemDescription()).append(")");
    }
    return line.toString();
  }

  private InventoryViewOutput.ItemEntry toEquipmentEntry(EquipmentItem item) {
    return new InventoryViewOutput.ItemEntry(
        item.getItemId(),
        item.getItemInstanceId(),
        item.getContainerInstanceId(),
        item.getVisibleRef(),
        item.getItemName(),
        item.getItemDescription(),
        1,
        item.getSlot());
  }

  private record EquipmentResolution(
      Optional<InventoryItem> item, boolean queryUnavailable, String reason) {
    static EquipmentResolution available(Optional<InventoryItem> item) {
      return new EquipmentResolution(item, false, null);
    }

    static EquipmentResolution unavailable(String reason) {
      return new EquipmentResolution(Optional.empty(), true, reason);
    }
  }
}
