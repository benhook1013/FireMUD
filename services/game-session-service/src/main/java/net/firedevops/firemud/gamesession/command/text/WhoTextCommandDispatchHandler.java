package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class WhoTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final WhoCommandHandler whoHandler;

  WhoTextCommandDispatchHandler(WhoCommandHandler whoHandler) {
    this.whoHandler = whoHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.WHO;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return whoHandler.handle(request.sessionContext().orElseThrow());
  }
}
