package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class WorldsTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final WorldsCommandHandler worldsHandler;

  WorldsTextCommandDispatchHandler(WorldsCommandHandler worldsHandler) {
    this.worldsHandler = worldsHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.WORLDS;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return new TextCommandInterpretationResult(
        net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
        List.of(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                worldsHandler.browseView())));
  }
}
