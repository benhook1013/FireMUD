package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

/** Handles the first inventory command surface in the text session layer. */
@Component
public class InventoryCommandHandler {

  public InventoryCommandHandlingResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case INVENTORY -> describeInventory();
      case GET -> acknowledgePendingMutation("GET", command);
      case DROP -> acknowledgePendingMutation("DROP", command);
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

  private InventoryCommandHandlingResult describeInventory() {
    return new InventoryCommandHandlingResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.view(
                new InventoryViewOutput(
                    "Inventory:",
                    List.of(
                        "This command surface is ready.",
                        "The runtime inventory contract is still being wired.")))));
  }

  private InventoryCommandHandlingResult acknowledgePendingMutation(
      String verb, TextCommand command) {
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
    return new InventoryCommandHandlingResult(
        CommandEnqueueResult.failure(
            "INVENTORY_UNAVAILABLE", verb + " is not yet wired to runtime inventory state"),
        List.of(
            PlayerOutput.error(
                "INVENTORY_UNAVAILABLE",
                verb + " " + itemReference + " is not yet wired to runtime inventory state",
                "error.inventory.unavailable",
                Map.of("verb", verb, "item", itemReference))));
  }
}
