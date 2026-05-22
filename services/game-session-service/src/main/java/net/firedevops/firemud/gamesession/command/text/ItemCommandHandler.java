package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ItemCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ItemCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.item.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.item.failures";

  private final InventoryCommandHandler inventoryHandler;
  private final EquipmentCommandHandler equipmentHandler;
  private final ContainerCommandHandler containerHandler;
  private final MeterRegistry meterRegistry;
  private final ScriptEventPublisher scriptEventPublisher;

  public ItemCommandHandler(
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      MeterRegistry meterRegistry,
      ScriptEventPublisher scriptEventPublisher) {
    this.inventoryHandler =
        Objects.requireNonNull(inventoryHandler, "inventoryHandler must not be null");
    this.equipmentHandler =
        Objects.requireNonNull(equipmentHandler, "equipmentHandler must not be null");
    this.containerHandler =
        Objects.requireNonNull(containerHandler, "containerHandler must not be null");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    this.scriptEventPublisher =
        Objects.requireNonNull(scriptEventPublisher, "scriptEventPublisher must not be null");
  }

  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    return handle(context, command, null);
  }

  public TextCommandInterpretationResult handle(
      SessionContext context, TextCommand command, String effectId) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    String typeTag = command.type().name().toLowerCase(Locale.ROOT);
    meterRegistry.counter(INVOCATIONS_METRIC, "type", typeTag).increment();
    TextCommandInterpretationResult result =
        switch (command.type()) {
          case INVENTORY, GET, DROP ->
              toInterpretationResult(inventoryHandler.handle(context, command, effectId));
          case EQUIPMENT, WEAR, REMOVE -> equipmentHandler.handle(context, command, effectId);
          case CONTAINER, PUT, TAKE -> containerHandler.handle(context, command, effectId);
          default ->
              new TextCommandInterpretationResult(
                  CommandEnqueueResult.failure("INVALID_COMMAND", "Unsupported item command"));
        };
    if (!result.commandResult().accepted()) {
      recordFailure(context, typeTag, result.commandResult().errorCode());
    } else if (!isMutation(command.type())) {
      publishCommandEvent(context, command);
    }
    return result;
  }

  private static TextCommandInterpretationResult toInterpretationResult(
      InventoryCommandHandlingResult result) {
    return new TextCommandInterpretationResult(result.commandResult(), result.outputs());
  }

  private boolean isMutation(TextCommandType type) {
    return switch (type) {
      case GET, DROP, PUT, TAKE, WEAR, REMOVE -> true;
      default -> false;
    };
  }

  private void recordFailure(SessionContext context, String typeTag, String errorCode) {
    String errorTag = errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode;
    meterRegistry.counter(FAILURES_METRIC, "type", typeTag, "error", errorTag).increment();
    LOG.warn(
        "Item command failed tenantId={} gameInstanceId={} characterId={} type={} error={}",
        context.tenantId(),
        context.gameInstanceId(),
        context.characterId(),
        typeTag,
        errorTag);
  }

  private void publishCommandEvent(SessionContext context, TextCommand command) {
    try {
      scriptEventPublisher.publishCommandEvent(context, scriptEventCommand(command));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Item script event publish failed tenantId={} gameInstanceId={} characterId={} type={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          command.type(),
          ex);
    }
  }

  private GameplayCommand scriptEventCommand(TextCommand command) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId("item-" + UUID.randomUUID());
    gameplayCommand.setCommandName(command.type().name());
    return gameplayCommand;
  }
}
