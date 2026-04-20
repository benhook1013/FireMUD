package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class MoveTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final net.firedevops.firemud.gamesession.service.CommandService commandService;

  MoveTextCommandDispatchHandler(
      net.firedevops.firemud.gamesession.service.CommandService commandService,
      MoveCommandHandler moveHandler) {
    this.commandService = commandService;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.MOVE;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    net.firedevops.firemud.gamesession.dto.CommandEnqueueResult enqueueResult =
        commandService.enqueue(
            request.sessionId(), request.command().rawLine(), request.requiresSoloTick());
    return new TextCommandInterpretationResult(enqueueResult, List.of());
  }
}
