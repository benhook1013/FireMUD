package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class LookTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final net.firedevops.firemud.gamesession.service.CommandService commandService;
  private final LookCommandHandler lookHandler;

  LookTextCommandDispatchHandler(
      net.firedevops.firemud.gamesession.service.CommandService commandService,
      LookCommandHandler lookHandler) {
    this.commandService = commandService;
    this.lookHandler = lookHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.LOOK;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    net.firedevops.firemud.gamesession.dto.CommandEnqueueResult enqueueResult =
        commandService.enqueue(
            request.sessionId(), request.command().rawLine(), request.requiresSoloTick());
    if (!enqueueResult.accepted() || request.command().viewRequestPayload().isEmpty()) {
      return new TextCommandInterpretationResult(enqueueResult);
    }
    net.firedevops.firemud.gamesession.presentation.PlayerOutput lookOutput =
        lookHandler.describePlayerOutput(
            request.sessionId(), request.command().type() != TextCommandType.QUICKLOOK);
    if (lookOutput == null) {
      return stageFailure(
          GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
          GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE);
    }
    if (lookOutput.kind() == net.firedevops.firemud.gamesession.presentation.PlayerOutputKind.ERROR
        && lookOutput.payload()
            instanceof net.firedevops.firemud.gamesession.presentation.ErrorOutput errorOutput) {
      return new TextCommandInterpretationResult(
          net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.failure(
              errorOutput.code(), errorOutput.message()),
          List.of(lookOutput));
    }
    return new TextCommandInterpretationResult(enqueueResult, List.of(lookOutput));
  }

  private TextCommandInterpretationResult stageFailure(String code, String message) {
    String messageKey =
        switch (code) {
          case GameplayStageCommandConstants.LOGIN_REQUIRED_CODE -> "error.login-required";
          case GameplayStageCommandConstants.PLAY_REQUIRED_CODE -> "error.play-required";
          default -> null;
        };
    return new TextCommandInterpretationResult(
        net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.failure(code, message),
        List.of(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(
                code, message, messageKey, java.util.Map.of())));
  }
}
