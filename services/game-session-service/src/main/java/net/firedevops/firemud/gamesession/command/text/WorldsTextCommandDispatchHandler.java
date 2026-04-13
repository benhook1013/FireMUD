package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class WorldsTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final WorldsCommandHandler worldsHandler;

  WorldsTextCommandDispatchHandler(WorldsCommandHandler worldsHandler) {
    this.worldsHandler = worldsHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.WORLDS;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return switch (request.command().type()) {
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
}
