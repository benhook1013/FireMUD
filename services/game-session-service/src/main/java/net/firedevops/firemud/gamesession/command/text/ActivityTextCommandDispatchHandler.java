package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class ActivityTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final net.firedevops.firemud.gamesession.service.CommandService commandService;

  ActivityTextCommandDispatchHandler(
      net.firedevops.firemud.gamesession.service.CommandService commandService) {
    this.commandService = commandService;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.ACTIVITY;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return new TextCommandInterpretationResult(
        commandService.enqueue(
            request.sessionId(), request.command().rawLine(), request.requiresSoloTick()));
  }
}
