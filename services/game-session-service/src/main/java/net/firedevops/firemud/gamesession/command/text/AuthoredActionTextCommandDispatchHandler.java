package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class AuthoredActionTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final AuthoredActionCommandHandler handler;

  AuthoredActionTextCommandDispatchHandler(AuthoredActionCommandHandler handler) {
    this.handler = handler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.AUTHORED;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return handler.handle(request.command());
  }
}
