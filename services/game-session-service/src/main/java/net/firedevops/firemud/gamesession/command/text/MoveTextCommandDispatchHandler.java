package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class MoveTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final MoveCommandHandler moveHandler;

  MoveTextCommandDispatchHandler(MoveCommandHandler moveHandler) {
    this.moveHandler = moveHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.MOVE;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    MoveCommandHandlingResult moveResult =
        moveHandler.handle(request.sessionContext().orElseThrow(), request.command());
    List<net.firedevops.firemud.gamesession.presentation.PlayerOutput> outputs =
        moveResult.responseOutput() == null ? List.of() : List.of(moveResult.responseOutput());
    return new TextCommandInterpretationResult(moveResult.commandResult(), outputs);
  }
}
