package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import org.springframework.stereotype.Component;

@Component
final class MoveTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final net.firedevops.firemud.gamesession.service.CommandService commandService;

  MoveTextCommandDispatchHandler(
      net.firedevops.firemud.gamesession.service.CommandService commandService) {
    this.commandService = commandService;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.MOVE;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    if (!hasExactlyOneCanonicalDirection(request.command())) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", "MOVE requires exactly one direction."),
          List.of(PlayerOutput.error("INVALID_ARGUMENT", "MOVE requires exactly one direction.")));
    }
    CommandEnqueueResult enqueueResult =
        commandService.enqueue(
            request.sessionId(), request.command().rawLine(), request.requiresSoloTick());
    return new TextCommandInterpretationResult(enqueueResult, List.of());
  }

  private boolean hasExactlyOneCanonicalDirection(TextCommand command) {
    if (command.args().size() != 1 || command.directionalPayload().isEmpty()) {
      return false;
    }
    String argumentDirection = TextCommandDirections.canonicalDirection(command.args().get(0));
    String payloadDirection =
        TextCommandDirections.canonicalDirection(
            command.directionalPayload().orElseThrow().direction());
    return !argumentDirection.isEmpty() && argumentDirection.equals(payloadDirection);
  }
}
