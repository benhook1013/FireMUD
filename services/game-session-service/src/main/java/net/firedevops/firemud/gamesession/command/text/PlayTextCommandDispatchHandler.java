package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class PlayTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final PlayCommandHandler playHandler;

  PlayTextCommandDispatchHandler(PlayCommandHandler playHandler) {
    this.playHandler = playHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.PLAY;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    PlayCommandHandlingResult playResult =
        playHandler.handle(request.sessionId(), request.command());
    return new TextCommandInterpretationResult(
        playResult.commandResult(), playResult.outputs(), playResult.reconnectRedrawRecommended());
  }
}
