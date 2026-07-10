package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class StatusTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final StatusCommandHandler statusCommandHandler;

  StatusTextCommandDispatchHandler(StatusCommandHandler statusCommandHandler) {
    this.statusCommandHandler = statusCommandHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.STATUS;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return statusCommandHandler.handle(request.command(), request.sessionContext().orElseThrow());
  }
}
