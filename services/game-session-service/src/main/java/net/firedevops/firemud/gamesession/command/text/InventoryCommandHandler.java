package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
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
      return List.of("(empty)");
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
            "INVENTORY_UNAVAILABLE", verb + " is not yet wired to runtime inventory mutations"),
        List.of(
            PlayerOutput.error(
                "INVENTORY_UNAVAILABLE",
                verb + " " + itemReference + " is not yet wired to runtime inventory mutations",
                "error.inventory.unavailable",
                Map.of("verb", verb, "item", itemReference))));
  }
}
