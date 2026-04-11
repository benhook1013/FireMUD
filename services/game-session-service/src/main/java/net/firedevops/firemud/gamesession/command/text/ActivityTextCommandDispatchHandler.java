package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class ActivityTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final AfkCommandHandler afkCommandHandler;

  ActivityTextCommandDispatchHandler(AfkCommandHandler afkCommandHandler) {
    this.afkCommandHandler = afkCommandHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.ACTIVITY;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return switch (request.command().type()) {
      case AFK -> {
        AfkCommandHandlingResult afkResult =
            afkCommandHandler.handle(request.sessionId(), request.command());
        yield new TextCommandInterpretationResult(afkResult.commandResult(), afkResult.outputs());
      }
      default ->
          throw new IllegalArgumentException(
              "Unsupported activity command type: " + request.command().type());
    };
  }
}
