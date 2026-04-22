package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamesession.service.CommandService;
import org.springframework.stereotype.Component;

@Component
final class ItemTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final CommandService commandService;
  private final ItemCommandHandler itemHandler;

  ItemTextCommandDispatchHandler(CommandService commandService, ItemCommandHandler itemHandler) {
    this.commandService = commandService;
    this.itemHandler = itemHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.ITEM;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    if (isMutation(request.command().type())) {
      return new TextCommandInterpretationResult(
          commandService.enqueue(
              request.sessionId(), request.command().rawLine(), request.requiresSoloTick()));
    }
    return itemHandler.handle(request.sessionContext().orElseThrow(), request.command());
  }

  private boolean isMutation(TextCommandType type) {
    return switch (type) {
      case GET, DROP, PUT, TAKE, WEAR, REMOVE -> true;
      default -> false;
    };
  }
}
