package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class SessionTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final LoginCommandHandler loginHandler;
  private final LogoutCommandHandler logoutHandler;
  private final PlayCommandHandler playHandler;

  SessionTextCommandDispatchHandler(
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler) {
    this.loginHandler = loginHandler;
    this.logoutHandler = logoutHandler;
    this.playHandler = playHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.SESSION;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return switch (request.command().type()) {
      case LOGIN -> {
        LoginCommandHandlingResult loginResult =
            loginHandler.handle(request.sessionId(), request.command(), request.requiresSoloTick());
        yield new TextCommandInterpretationResult(
            loginResult.commandResult(), loginResult.outputs());
      }
      case LOGOUT -> {
        LogoutCommandHandlingResult logoutResult =
            logoutHandler.handle(request.sessionId(), request.command());
        yield new TextCommandInterpretationResult(
            logoutResult.commandResult(), logoutResult.outputs());
      }
      case PLAY -> {
        PlayCommandHandlingResult playResult =
            playHandler.handle(request.sessionId(), request.command());
        yield new TextCommandInterpretationResult(
            playResult.commandResult(),
            playResult.outputs(),
            playResult.reconnectRedrawRecommended());
      }
      default ->
          throw new IllegalArgumentException(
              "Unsupported session command type: " + request.command().type());
    };
  }
}
