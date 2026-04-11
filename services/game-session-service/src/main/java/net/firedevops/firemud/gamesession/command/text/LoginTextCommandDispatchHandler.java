package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class LoginTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final LoginCommandHandler loginHandler;

  LoginTextCommandDispatchHandler(LoginCommandHandler loginHandler) {
    this.loginHandler = loginHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.LOGIN;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    LoginCommandHandlingResult loginResult =
        loginHandler.handle(request.sessionId(), request.command(), request.requiresSoloTick());
    return new TextCommandInterpretationResult(loginResult.commandResult(), loginResult.outputs());
  }
}
