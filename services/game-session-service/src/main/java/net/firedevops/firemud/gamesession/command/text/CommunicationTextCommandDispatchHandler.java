package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class CommunicationTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final CommunicationCommandHandler communicationHandler;

  CommunicationTextCommandDispatchHandler(CommunicationCommandHandler communicationHandler) {
    this.communicationHandler = communicationHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.COMMUNICATION;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    CommunicationCommandHandlingResult result =
        communicationHandler.handle(request.sessionContext().orElseThrow(), request.command());
    return new TextCommandInterpretationResult(result.commandResult(), result.outputs());
  }
}
