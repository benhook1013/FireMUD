package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class WorldsTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private static final Logger LOG = LoggerFactory.getLogger(WorldsTextCommandDispatchHandler.class);
  private final WorldsCommandHandler worldsHandler;
  private final ScriptEventPublisher scriptEventPublisher;

  WorldsTextCommandDispatchHandler(
      WorldsCommandHandler worldsHandler, ScriptEventPublisher scriptEventPublisher) {
    this.worldsHandler = worldsHandler;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.WORLDS;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    TextCommandInterpretationResult result =
        switch (request.command().type()) {
          case WORLDS ->
              new TextCommandInterpretationResult(
                  net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
                  List.of(
                      net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                          worldsHandler.browseView())));
          case REALMS -> handleRealms(request.command());
          case CHARS -> handleChars(request);
          default ->
              new TextCommandInterpretationResult(
                  net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.failure(
                      "INVALID_ARGUMENT", "Unsupported discovery command"),
                  List.of(
                      net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(
                          "INVALID_ARGUMENT", "Unsupported discovery command")));
        };
    if (result.commandResult().accepted()) {
      request
          .sessionContext()
          .ifPresent(context -> publishCommandEvent(context, request.command()));
    }
    return result;
  }

  private TextCommandInterpretationResult handleRealms(TextCommand command) {
    return command
        .realmBrowsePayload()
        .flatMap(payload -> worldsHandler.browseRealms(payload.worldSelector()))
        .map(
            view ->
                new TextCommandInterpretationResult(
                    net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
                    List.of(
                        net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(view))))
        .orElseGet(
            () ->
                errorResult(
                    "INVALID_ARGUMENT",
                    "REALMS requires a valid world selector. Use WORLDS first."));
  }

  private TextCommandInterpretationResult handleChars(TextCommandDispatchRequest request) {
    if (request.command().characterBrowsePayload().isEmpty()
        || request.sessionContext().isEmpty()) {
      return errorResult("INVALID_ARGUMENT", "CHARS requires a world selector after LOGIN.");
    }
    TextCommandPayload.CharacterBrowseRequest payload =
        request.command().characterBrowsePayload().orElseThrow();
    return switch (worldsHandler.browseCharacters(
        request.sessionContext().orElseThrow(), payload.worldSelector(), payload.realmSelector())) {
      case WorldsCommandHandler.CharacterBrowseResult.Success success ->
          new TextCommandInterpretationResult(
              net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
              List.of(
                  net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                      success.output())));
      case WorldsCommandHandler.CharacterBrowseResult.InvalidWorld ignored ->
          errorResult(
              "INVALID_ARGUMENT", "CHARS requires a valid world selector. Use WORLDS first.");
      case WorldsCommandHandler.CharacterBrowseResult.InvalidRealm invalidRealm ->
          errorResult(
              "INVALID_ARGUMENT",
              "Use REALMS " + invalidRealm.worldSlug() + " to choose a visible realm first.");
      case WorldsCommandHandler.CharacterBrowseResult.RealmSelectionRequired
              realmSelectionRequired ->
          errorResult(
              "PLAY_SELECTION_REQUIRED",
              "Selection required. Use REALMS "
                  + realmSelectionRequired.worldSlug()
                  + " before CHARS.");
      case WorldsCommandHandler.CharacterBrowseResult.Unavailable ignored ->
          errorResult(
              "CHARACTER_LIST_UNAVAILABLE", "Character list unavailable. Retry CHARS shortly.");
    };
  }

  private TextCommandInterpretationResult errorResult(String code, String message) {
    return new TextCommandInterpretationResult(
        net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.failure(code, message),
        List.of(net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(code, message)));
  }

  private void publishCommandEvent(SessionContext context, TextCommand command) {
    try {
      GameplayCommand gameplayCommand = new GameplayCommand();
      gameplayCommand.setCommandId("worlds-" + UUID.randomUUID());
      gameplayCommand.setCommandName(command.type().name());
      scriptEventPublisher.publishCommandEvent(context, gameplayCommand);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Discovery script event publish failed tenantId={} gameInstanceId={} characterId={} commandType={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          command.type(),
          ex);
    }
  }
}
