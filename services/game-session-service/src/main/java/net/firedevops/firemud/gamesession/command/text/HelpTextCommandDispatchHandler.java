package net.firedevops.firemud.gamesession.command.text;

import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class HelpTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private static final Logger LOG = LoggerFactory.getLogger(HelpTextCommandDispatchHandler.class);
  private final HelpCommandHandler helpHandler;
  private final ScriptEventPublisher scriptEventPublisher;

  HelpTextCommandDispatchHandler(
      HelpCommandHandler helpHandler, ScriptEventPublisher scriptEventPublisher) {
    this.helpHandler = helpHandler;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.HELP;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    TextCommandInterpretationResult result = helpHandler.handle(request.command());
    if (result.commandResult().accepted()) {
      request
          .sessionContext()
          .ifPresent(context -> publishCommandEvent(context, request.command()));
    }
    return result;
  }

  private void publishCommandEvent(SessionContext context, TextCommand command) {
    try {
      GameplayCommand gameplayCommand = new GameplayCommand();
      gameplayCommand.setCommandId("help-" + UUID.randomUUID());
      gameplayCommand.setCommandName(command.type().name());
      scriptEventPublisher.publishCommandEvent(context, gameplayCommand);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Help script event publish failed tenantId={} gameInstanceId={} characterId={} commandType={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          command.type(),
          ex);
    }
  }
}
