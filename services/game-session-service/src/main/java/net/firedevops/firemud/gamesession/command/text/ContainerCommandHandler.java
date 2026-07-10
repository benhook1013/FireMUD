package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the first container command surface in the text session layer. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
public class ContainerCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ContainerCommandHandler.class);

  private final GameLogicClient gameLogicClient;

  public ContainerCommandHandler(GameLogicClient gameLogicClient) {
    this.gameLogicClient =
        Objects.requireNonNull(gameLogicClient, "gameLogicClient must not be null");
  }

  @Timed(value = "gamesession.command.container")
  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    return handle(context, command, null);
  }

  public TextCommandInterpretationResult handle(
      SessionContext context, TextCommand command, String effectId) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case CONTAINER -> describeContainer(context, command);
      case PUT -> putIntoContainer(context, command, effectId);
      case TAKE -> takeFromContainer(context, command, effectId);
      default ->
          new TextCommandInterpretationResult(
              CommandEnqueueResult.failure("INVALID_COMMAND", "Unsupported container command"),
              List.of(
                  PlayerOutput.error(
                      "INVALID_COMMAND",
                      "Unsupported container command",
                      "error.container.invalid-command",
                      Map.of())));
    };
  }

  private TextCommandInterpretationResult describeContainer(
      SessionContext context, TextCommand command) {
    if (command.containerViewPayload().isEmpty()) {
      return invalidArgument("CONTAINER <container>");
    }
    String containerReference = command.containerViewPayload().orElseThrow().containerReference();
    try {
      InventoryResolution inventory = loadInventory(context);
      if (inventory.unavailable()) {
        return containerUnavailable(inventory.reason());
      }
      Optional<AccessibleContainer> resolvedContainer =
          resolveAccessibleContainer(context, inventory, containerReference);
      if (resolvedContainer.isEmpty()) {
        return containerInvalidArgument(
            "No accessible container matches \"" + containerReference + "\"");
      }
      return describeResolvedContainer(context, resolvedContainer.orElseThrow());
    } catch (RuntimeException ex) {
      LOG.warn(
          "Container describe failed tenantId={} characterId={} containerReference={}",
          context.tenantId(),
          context.characterId(),
          containerReference,
          ex);
      return containerUnavailable("Container service unavailable");
    }
  }

  private TextCommandInterpretationResult putIntoContainer(
      SessionContext context, TextCommand command, String effectId) {
    if (command.containerTransferPayload().isEmpty()) {
      return invalidArgument("PUT <item> INTO <container>");
    }

    TextCommandPayload.ContainerTransfer transfer =
        command.containerTransferPayload().orElseThrow();
    if (transfer.quantity() <= 0) {
      return containerInvalidArgument(
          "PUT quantity must be positive for " + transfer.itemReference());
    }

    try {
      InventoryResolution inventory = loadInventory(context);
      if (inventory.unavailable()) {
        return containerUnavailable(inventory.reason());
      }

      Optional<AccessibleContainer> resolvedContainer =
          resolveAccessibleContainer(context, inventory, transfer.containerReference());
      if (resolvedContainer.isEmpty()) {
        return containerInvalidArgument(
            "No accessible container matches \"" + transfer.containerReference() + "\"");
      }
      Optional<InventoryItem> resolvedItem =
          findInventoryItem(inventory.items(), transfer.itemReference());
      if (resolvedItem.isEmpty()) {
        return containerInvalidArgument(
            "No carried item matches \"" + transfer.itemReference() + "\"");
      }

      InventoryItem inventoryItem = resolvedItem.orElseThrow();
      if (transfer.quantity() > 1
          && ContainerIdentitySupport.matchesExplicitReference(
              inventoryItem, transfer.itemReference())
          && !isStackSelection(inventoryItem, transfer.itemReference())) {
        return containerInvalidArgument("Explicit item refs require quantity 1 for PUT");
      }

      String selectedItemInstanceId =
          ContainerIdentitySupport.matchesExplicitReference(inventoryItem, transfer.itemReference())
                  && !isStackSelection(inventoryItem, transfer.itemReference())
                  && !inventoryItem.getItemInstanceId().isBlank()
              ? inventoryItem.getItemInstanceId()
              : null;
      String selectedStackFamilyKey = stackFamilyKey(inventoryItem, transfer.itemReference());
      String containerInstanceId = resolvedContainer.orElseThrow().containerInstanceId();
      var response =
          StringUtils.hasText(effectId)
              ? gameLogicClient.putItemIntoContainer(
                  context,
                  containerInstanceId,
                  inventoryItem.getItemId(),
                  selectedItemInstanceId,
                  selectedStackFamilyKey,
                  transfer.quantity(),
                  effectId)
              : gameLogicClient.putItemIntoContainer(
                  context,
                  containerInstanceId,
                  inventoryItem.getItemId(),
                  selectedItemInstanceId,
                  selectedStackFamilyKey,
                  transfer.quantity());
      if (response.hasError()) {
        return containerFailure(
            errorCode(response.getError().getCode()),
            stackSelectionGuidance(
                response.getError().getMessage(),
                transfer.itemReference(),
                "Use INVENTORY to find the explicit stack ref and retry with that ref."));
      }

      List<PlayerOutput> outputs = new ArrayList<>();
      outputs.add(
          PlayerOutput.message(
              "You put "
                  + displayInventoryItemName(inventoryItem)
                  + quantitySuffix(transfer.quantity())
                  + " into "
                  + resolvedContainer.orElseThrow().displayName()
                  + "."));
      outputs.addAll(describeResolvedContainer(context, resolvedContainer.orElseThrow()).outputs());
      return new TextCommandInterpretationResult(CommandEnqueueResult.success(), outputs);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Container put failed tenantId={} characterId={} itemReference={} containerReference={}",
          context.tenantId(),
          context.characterId(),
          transfer.itemReference(),
          transfer.containerReference(),
          ex);
      return containerUnavailable("Container service unavailable");
    }
  }

  private TextCommandInterpretationResult takeFromContainer(
      SessionContext context, TextCommand command, String effectId) {
    if (command.containerTransferPayload().isEmpty()) {
      return invalidArgument("TAKE <item> FROM <container>");
    }

    TextCommandPayload.ContainerTransfer transfer =
        command.containerTransferPayload().orElseThrow();
    if (transfer.quantity() <= 0) {
      return containerInvalidArgument(
          "TAKE quantity must be positive for " + transfer.itemReference());
    }

    try {
      InventoryResolution inventory = loadInventory(context);
      if (inventory.unavailable()) {
        return containerUnavailable(inventory.reason());
      }

      Optional<AccessibleContainer> resolvedContainer =
          resolveAccessibleContainer(context, inventory, transfer.containerReference());
      if (resolvedContainer.isEmpty()) {
        return containerInvalidArgument(
            "No accessible container matches \"" + transfer.containerReference() + "\"");
      }

      ListContainerContentsResponse contents =
          gameLogicClient.listContainerContents(
              context, resolvedContainer.orElseThrow().containerInstanceId());
      if (contents.hasError()) {
        return containerFailure(
            errorCode(contents.getError().getCode()), contents.getError().getMessage());
      }

      Optional<ContainerItem> resolvedItem =
          findContainerItem(contents.getItemsList(), transfer.itemReference());
      if (resolvedItem.isEmpty()) {
        return containerInvalidArgument(
            "No container item matches \"" + transfer.itemReference() + "\"");
      }
      ContainerItem containerItem = resolvedItem.orElseThrow();
      if (transfer.quantity() > 1
          && ContainerIdentitySupport.matchesExplicitReference(
              containerItem, transfer.itemReference())
          && !isStackSelection(containerItem, transfer.itemReference())) {
        return containerInvalidArgument("Explicit item refs require quantity 1 for TAKE");
      }

      String selectedItemInstanceId =
          ContainerIdentitySupport.matchesExplicitReference(containerItem, transfer.itemReference())
                  && !isStackSelection(containerItem, transfer.itemReference())
                  && !containerItem.getItemInstanceId().isBlank()
              ? containerItem.getItemInstanceId()
              : null;
      String selectedStackFamilyKey = stackFamilyKey(containerItem, transfer.itemReference());
      String containerInstanceId = resolvedContainer.orElseThrow().containerInstanceId();
      var response =
          StringUtils.hasText(effectId)
              ? gameLogicClient.takeItemFromContainer(
                  context,
                  containerInstanceId,
                  containerItem.getItemId(),
                  selectedItemInstanceId,
                  selectedStackFamilyKey,
                  transfer.quantity(),
                  effectId)
              : gameLogicClient.takeItemFromContainer(
                  context,
                  containerInstanceId,
                  containerItem.getItemId(),
                  selectedItemInstanceId,
                  selectedStackFamilyKey,
                  transfer.quantity());
      if (response.hasError()) {
        return containerFailure(
            errorCode(response.getError().getCode()),
            stackSelectionGuidance(
                response.getError().getMessage(),
                transfer.itemReference(),
                "Use CONTAINER "
                    + transfer.containerReference()
                    + " to find the explicit stack ref and retry with that ref."));
      }

      List<PlayerOutput> outputs = new ArrayList<>();
      outputs.add(
          PlayerOutput.message(
              "You take "
                  + displayContainerItemName(containerItem)
                  + quantitySuffix(transfer.quantity())
                  + " from "
                  + resolvedContainer.orElseThrow().displayName()
                  + "."));
      outputs.addAll(describeResolvedContainer(context, resolvedContainer.orElseThrow()).outputs());
      return new TextCommandInterpretationResult(CommandEnqueueResult.success(), outputs);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Container take failed tenantId={} characterId={} itemReference={} containerReference={}",
          context.tenantId(),
          context.characterId(),
          transfer.itemReference(),
          transfer.containerReference(),
          ex);
      return containerUnavailable("Container service unavailable");
    }
  }

  private TextCommandInterpretationResult describeResolvedContainer(
      SessionContext context, AccessibleContainer containerItem) {
    ListContainerContentsResponse response =
        gameLogicClient.listContainerContents(context, containerItem.containerInstanceId());
    if (response.hasError()) {
      return containerFailure(
          errorCode(response.getError().getCode()), response.getError().getMessage());
    }
    List<String> lines =
        response.getItemsList().isEmpty()
            ? List.of("It is empty.")
            : response.getItemsList().stream().map(this::formatContainerItem).toList();
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.view(
                new InventoryViewOutput(
                    InventoryViewOutput.Source.CONTAINER,
                    "Container: "
                        + containerItem.displayName()
                        + compactReferenceSuffix(containerItem.compactReference()),
                    lines,
                    new InventoryViewOutput.ViewContext(
                        containerItem.containerInstanceId(),
                        containerItem.displayName(),
                        containerItem.compactReference()),
                    response.getItemsList().stream().map(this::toContainerEntry).toList()))));
  }

  private InventoryResolution loadInventory(SessionContext context) {
    QueryInventoryResponse inventory = gameLogicClient.queryInventory(context);
    if (inventory.hasError()) {
      return InventoryResolution.unavailable(
          StringUtils.hasText(inventory.getError().getMessage())
              ? inventory.getError().getMessage()
              : "Inventory service unavailable");
    }
    return InventoryResolution.available(inventory.getItemsList());
  }

  private RoomGroundResolution loadRoomGround(SessionContext context) {
    ListRoomGroundInventoryResponse roomGround =
        gameLogicClient.listRoomGroundInventory(context, context.roomInstanceId());
    if (roomGround.hasError()) {
      return RoomGroundResolution.unavailable(
          StringUtils.hasText(roomGround.getError().getMessage())
              ? roomGround.getError().getMessage()
              : "Room inventory service unavailable");
    }
    return RoomGroundResolution.available(roomGround.getItemsList());
  }

  private Optional<AccessibleContainer> resolveAccessibleContainer(
      SessionContext context, InventoryResolution inventory, String reference) {
    Optional<AccessibleContainer> carried =
        inventory.items().stream()
            .filter(item -> ContainerIdentitySupport.matchesReference(item, reference))
            .map(this::toAccessibleContainer)
            .findFirst();
    if (carried.isPresent()) {
      return carried;
    }
    RoomGroundResolution roomGround = loadRoomGround(context);
    if (roomGround.unavailable()) {
      throw new IllegalStateException(roomGround.reason());
    }
    return roomGround.items().stream()
        .filter(item -> StringUtils.hasText(item.getContainerInstanceId()))
        .filter(item -> ContainerIdentitySupport.matchesReference(item, reference))
        .map(this::toAccessibleContainer)
        .findFirst();
  }

  private Optional<InventoryItem> findInventoryItem(List<InventoryItem> items, String reference) {
    return items.stream()
        .filter(item -> ContainerIdentitySupport.matchesReference(item, reference))
        .findFirst();
  }

  private Optional<ContainerItem> findContainerItem(List<ContainerItem> items, String reference) {
    return items.stream()
        .filter(item -> ContainerIdentitySupport.matchesReference(item, reference))
        .findFirst();
  }

  private AccessibleContainer toAccessibleContainer(InventoryItem item) {
    return new AccessibleContainer(
        ContainerIdentitySupport.resolveContainerInstanceId(item),
        displayInventoryItemName(item),
        ContainerIdentitySupport.compactReference(item));
  }

  private AccessibleContainer toAccessibleContainer(RoomGroundInventoryItem item) {
    return new AccessibleContainer(
        ContainerIdentitySupport.resolveContainerInstanceId(item),
        StringUtils.hasText(item.getItemName()) ? item.getItemName() : "item",
        ContainerIdentitySupport.compactReference(item));
  }

  private String formatContainerItem(ContainerItem item) {
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

  private String displayInventoryItemName(InventoryItem item) {
    return StringUtils.hasText(item.getItemName()) ? item.getItemName() : "item";
  }

  private String displayContainerItemName(ContainerItem item) {
    return StringUtils.hasText(item.getItemName()) ? item.getItemName() : "item";
  }

  private InventoryViewOutput.ItemEntry toContainerEntry(ContainerItem item) {
    return new InventoryViewOutput.ItemEntry(
        item.getItemId(),
        item.getItemInstanceId(),
        item.getContainerInstanceId(),
        item.getVisibleRef(),
        item.getItemName(),
        item.getItemDescription(),
        item.getQuantity(),
        "");
  }

  private String quantitySuffix(int quantity) {
    return quantity > 1 ? " x" + quantity : "";
  }

  private boolean isStackSelection(InventoryItem item, String reference) {
    return !item.getItemInstanceId().isBlank()
        ? false
        : ContainerIdentitySupport.matchesExplicitReference(item, reference);
  }

  private boolean isStackSelection(ContainerItem item, String reference) {
    return !item.getItemInstanceId().isBlank()
        ? false
        : ContainerIdentitySupport.matchesExplicitReference(item, reference);
  }

  private String stackFamilyKey(InventoryItem item, String reference) {
    return isStackSelection(item, reference) ? item.getVisibleRef() : null;
  }

  private String stackFamilyKey(ContainerItem item, String reference) {
    return isStackSelection(item, reference) ? item.getVisibleRef() : null;
  }

  private String stackSelectionGuidance(String reason, String itemReference, String guidance) {
    if (reason != null && reason.contains("explicit stack selection required")) {
      return "Multiple stack families match \"" + itemReference + "\". " + guidance;
    }
    return reason;
  }

  private String compactReferenceSuffix(String compactReference) {
    return StringUtils.hasText(compactReference) ? " [" + compactReference + "]" : "";
  }

  private void appendCompactReference(StringBuilder line, String compactReference) {
    if (StringUtils.hasText(compactReference)) {
      line.append(" [").append(compactReference).append("]");
    }
  }

  private String errorCode(String candidate) {
    return StringUtils.hasText(candidate) ? candidate : "CONTAINER_UNAVAILABLE";
  }

  private TextCommandInterpretationResult invalidArgument(String syntax) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("INVALID_ARGUMENT", syntax),
        List.of(
            PlayerOutput.error(
                "INVALID_ARGUMENT",
                syntax,
                "error.container.invalid-argument",
                Map.of("syntax", syntax))));
  }

  private TextCommandInterpretationResult containerInvalidArgument(String reason) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("INVALID_ARGUMENT", reason),
        List.of(
            PlayerOutput.error(
                "INVALID_ARGUMENT", reason, "error.container.invalid-argument", Map.of())));
  }

  private TextCommandInterpretationResult containerFailure(String code, String reason) {
    String message =
        StringUtils.hasText(reason) ? reason : "Container service is temporarily unavailable";
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, "error.container.unavailable", Map.of())));
  }

  private TextCommandInterpretationResult containerUnavailable(String reason) {
    return containerFailure("CONTAINER_UNAVAILABLE", reason);
  }

  private record InventoryResolution(
      List<InventoryItem> items, boolean unavailable, String reason) {
    static InventoryResolution available(List<InventoryItem> items) {
      return new InventoryResolution(List.copyOf(items), false, null);
    }

    static InventoryResolution unavailable(String reason) {
      return new InventoryResolution(List.of(), true, reason);
    }
  }

  private record RoomGroundResolution(
      List<RoomGroundInventoryItem> items, boolean unavailable, String reason) {
    static RoomGroundResolution available(List<RoomGroundInventoryItem> items) {
      return new RoomGroundResolution(List.copyOf(items), false, null);
    }

    static RoomGroundResolution unavailable(String reason) {
      return new RoomGroundResolution(List.of(), true, reason);
    }
  }

  private record AccessibleContainer(
      String containerInstanceId, String displayName, String compactReference) {}
}
