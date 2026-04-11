package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class ItemTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final ItemCommandHandler itemHandler;

  ItemTextCommandDispatchHandler(ItemCommandHandler itemHandler) {
    this.itemHandler = itemHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.ITEM;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return itemHandler.handle(request.sessionContext().orElseThrow(), request.command());
  }
}
