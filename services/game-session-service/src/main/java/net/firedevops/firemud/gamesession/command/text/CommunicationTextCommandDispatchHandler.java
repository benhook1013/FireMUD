package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class CommunicationTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final net.firedevops.firemud.gamesession.service.CommandService commandService;

  CommunicationTextCommandDispatchHandler(
      net.firedevops.firemud.gamesession.service.CommandService commandService) {
    this.commandService = commandService;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.COMMUNICATION;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return new TextCommandInterpretationResult(
        commandService.enqueue(
            request.sessionId(), request.command().rawLine(), request.requiresSoloTick()));
  }
}
