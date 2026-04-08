package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
public class ItemCommandHandler {
  private final InventoryCommandHandler inventoryHandler;
  private final EquipmentCommandHandler equipmentHandler;
  private final ContainerCommandHandler containerHandler;

  public ItemCommandHandler(
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler) {
    this.inventoryHandler =
        Objects.requireNonNull(inventoryHandler, "inventoryHandler must not be null");
    this.equipmentHandler =
        Objects.requireNonNull(equipmentHandler, "equipmentHandler must not be null");
    this.containerHandler =
        Objects.requireNonNull(containerHandler, "containerHandler must not be null");
  }

  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case INVENTORY, GET, DROP ->
          toInterpretationResult(inventoryHandler.handle(context, command));
      case EQUIPMENT, WEAR, REMOVE -> equipmentHandler.handle(context, command);
      case CONTAINER, PUT, TAKE -> containerHandler.handle(context, command);
      default ->
          new TextCommandInterpretationResult(
              CommandEnqueueResult.failure("INVALID_COMMAND", "Unsupported item command"));
    };
  }

  private static TextCommandInterpretationResult toInterpretationResult(
      InventoryCommandHandlingResult result) {
    return new TextCommandInterpretationResult(result.commandResult(), result.outputs());
  }
}
