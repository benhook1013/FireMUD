package net.firedevops.firemud.gamesession.command.text;

import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
final class AuthoredActionTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private static final Logger LOG =
      LoggerFactory.getLogger(AuthoredActionTextCommandDispatchHandler.class);
  private final AuthoredActionCommandHandler handler;
  private final ConfiguredAuthoredActionCatalog catalog;
  private final ScriptEventPublisher scriptEventPublisher;

  AuthoredActionTextCommandDispatchHandler(
      AuthoredActionCommandHandler handler,
      ConfiguredAuthoredActionCatalog catalog,
      ScriptEventPublisher scriptEventPublisher) {
    this.handler = handler;
    this.catalog = catalog;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.AUTHORED;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    TextCommandInterpretationResult result = handler.handle(request.command());
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
      String executionHook =
          catalog
              .find(command.commandId())
              .map(ConfiguredAuthoredActionCatalog.ConfiguredAuthoredAction::executionHook)
              .orElse(null);
      gameplayCommand.setCommandId("authored-" + UUID.randomUUID());
      gameplayCommand.setCommandName(command.commandId());
      if (StringUtils.hasText(executionHook)) {
        gameplayCommand.setExecutionHook(executionHook);
      }
      scriptEventPublisher.publishCommandEvent(context, gameplayCommand);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Authored script event publish failed tenantId={} gameInstanceId={} characterId={} commandId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          command.commandId(),
          ex);
    }
  }
}
