package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class EnqueueOnlyTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final net.firedevops.firemud.gamesession.service.CommandService commandService;

  EnqueueOnlyTextCommandDispatchHandler(
      net.firedevops.firemud.gamesession.service.CommandService commandService) {
    this.commandService = commandService;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.ENQUEUE_ONLY;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return new TextCommandInterpretationResult(
        commandService.enqueue(
            request.sessionId(), request.command().rawLine(), request.requiresSoloTick()));
  }
}
