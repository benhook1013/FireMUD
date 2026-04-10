package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the first container command surface in the text session layer. */
@Component
public class ContainerCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ContainerCommandHandler.class);

  private final EntityManagementClient entityManagementClient;

  public ContainerCommandHandler(EntityManagementClient entityManagementClient) {
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
  }

  @Timed(value = "gamesession.command.container")
  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case CONTAINER -> describeContainer(context, command);
      case PUT -> putIntoContainer(context, command);
      case TAKE -> takeFromContainer(context, command);
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
      Optional<InventoryItem> resolvedContainer =
          findInventoryItem(inventory.items(), containerReference);
      if (resolvedContainer.isEmpty()) {
        return containerInvalidArgument(
            "No carried container matches \"" + containerReference + "\"");
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
      SessionContext context, TextCommand command) {
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

      Optional<InventoryItem> resolvedContainer =
          findInventoryItem(inventory.items(), transfer.containerReference());
      if (resolvedContainer.isEmpty()) {
        return containerInvalidArgument(
            "No carried container matches \"" + transfer.containerReference() + "\"");
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
              inventoryItem, transfer.itemReference())) {
        return containerInvalidArgument("Explicit item refs require quantity 1 for PUT");
      }

      var response =
          entityManagementClient.putItemIntoContainer(
              Long.toString(context.tenantId()),
              Long.toString(context.characterId()),
              ContainerIdentitySupport.resolveContainerInstanceId(resolvedContainer.orElseThrow()),
              inventoryItem.getItemId(),
              ContainerIdentitySupport.matchesExplicitReference(
                          inventoryItem, transfer.itemReference())
                      && !inventoryItem.getItemInstanceId().isBlank()
                  ? inventoryItem.getItemInstanceId()
                  : null,
              transfer.quantity());
      if (response.hasError()) {
        return containerFailure(
            errorCode(response.getError().getCode()), response.getError().getMessage());
      }

      List<PlayerOutput> outputs = new ArrayList<>();
      outputs.add(
          PlayerOutput.message(
              "You put "
                  + displayInventoryItemName(resolvedItem.orElseThrow())
                  + quantitySuffix(transfer.quantity())
                  + " into "
                  + displayInventoryItemName(resolvedContainer.orElseThrow())
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
      SessionContext context, TextCommand command) {
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

      Optional<InventoryItem> resolvedContainer =
          findInventoryItem(inventory.items(), transfer.containerReference());
      if (resolvedContainer.isEmpty()) {
        return containerInvalidArgument(
            "No carried container matches \"" + transfer.containerReference() + "\"");
      }

      ListContainerContentsResponse contents =
          entityManagementClient.listContainerContents(
              Long.toString(context.tenantId()),
              Long.toString(context.characterId()),
              ContainerIdentitySupport.resolveContainerInstanceId(resolvedContainer.orElseThrow()));
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
              containerItem, transfer.itemReference())) {
        return containerInvalidArgument("Explicit item refs require quantity 1 for TAKE");
      }

      var response =
          entityManagementClient.takeItemFromContainer(
              Long.toString(context.tenantId()),
              Long.toString(context.characterId()),
              ContainerIdentitySupport.resolveContainerInstanceId(resolvedContainer.orElseThrow()),
              containerItem.getItemId(),
              ContainerIdentitySupport.matchesExplicitReference(
                          containerItem, transfer.itemReference())
                      && !containerItem.getItemInstanceId().isBlank()
                  ? containerItem.getItemInstanceId()
                  : null,
              transfer.quantity());
      if (response.hasError()) {
        return containerFailure(
            errorCode(response.getError().getCode()), response.getError().getMessage());
      }

      List<PlayerOutput> outputs = new ArrayList<>();
      outputs.add(
          PlayerOutput.message(
              "You take "
                  + displayContainerItemName(resolvedItem.orElseThrow())
                  + quantitySuffix(transfer.quantity())
                  + " from "
                  + displayInventoryItemName(resolvedContainer.orElseThrow())
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
      SessionContext context, InventoryItem containerItem) {
    ListContainerContentsResponse response =
        entityManagementClient.listContainerContents(
            Long.toString(context.tenantId()),
            Long.toString(context.characterId()),
            ContainerIdentitySupport.resolveContainerInstanceId(containerItem));
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
                    "Container: "
                        + displayInventoryItemName(containerItem)
                        + compactReferenceSuffix(containerItem),
                    lines))));
  }

  private InventoryResolution loadInventory(SessionContext context) {
    QueryInventoryResponse inventory =
        entityManagementClient.queryInventory(
            Long.toString(context.tenantId()), Long.toString(context.characterId()));
    if (inventory.hasError()) {
      return InventoryResolution.unavailable(
          StringUtils.hasText(inventory.getError().getMessage())
              ? inventory.getError().getMessage()
              : "Inventory service unavailable");
    }
    return InventoryResolution.available(inventory.getItemsList());
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

  private String quantitySuffix(int quantity) {
    return quantity > 1 ? " x" + quantity : "";
  }

  private String compactReferenceSuffix(InventoryItem item) {
    String compactReference = ContainerIdentitySupport.compactReference(item);
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
}
