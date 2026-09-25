package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
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
          case REALMS -> handleRealms(request);
          case JOIN -> handleJoin(request);
          case CHARS -> handleChars(request);
          default ->
              new TextCommandInterpretationResult(
                  net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.failure(
                      "INVALID_ARGUMENT", "Unsupported discovery command"),
                  List.of(
                      net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(
                          "INVALID_ARGUMENT", "Unsupported discovery command")));
        };
    if (result.commandResult().accepted() && request.command().type() != TextCommandType.JOIN) {
      request
          .sessionContext()
          .ifPresent(context -> publishCommandEvent(context, request.command()));
    }
    return result;
  }

  private TextCommandInterpretationResult handleRealms(TextCommandDispatchRequest request) {
    if (request.command().realmBrowsePayload().isEmpty() || request.sessionContext().isEmpty()) {
      return errorResult("INVALID_ARGUMENT", "REALMS requires a world selector after LOGIN.");
    }
    TextCommandPayload.RealmBrowseRequest payload =
        request.command().realmBrowsePayload().orElseThrow();
    return switch (worldsHandler.browseRealms(
        request.sessionContext().orElseThrow(), payload.worldSelector())) {
      case WorldsCommandHandler.RealmBrowseResult.Success success ->
          new TextCommandInterpretationResult(
              net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
              List.of(
                  net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                      success.output())));
      case WorldsCommandHandler.RealmBrowseResult.InvalidSelector ignored ->
          errorResult(
              "INVALID_ARGUMENT", "REALMS requires a valid world selector. Use WORLDS first.");
      case WorldsCommandHandler.RealmBrowseResult.Failure failure ->
          errorResult(failure.code(), realmBrowseFailureMessage(failure.code()));
    };
  }

  private TextCommandInterpretationResult handleJoin(TextCommandDispatchRequest request) {
    if (request.command().joinRequestPayload().isEmpty() || request.sessionContext().isEmpty()) {
      return errorResult("INVALID_ARGUMENT", "JOIN requires a world selector after LOGIN.");
    }
    TextCommandPayload.JoinRequest payload = request.command().joinRequestPayload().orElseThrow();
    return switch (worldsHandler.joinPublicProductionMembership(
        request.sessionContext().orElseThrow(), payload.worldSelector())) {
      case WorldsCommandHandler.JoinMembershipResult.Failure failure ->
          errorResult(failure.code(), joinFailureMessage(failure.code()));
      case WorldsCommandHandler.JoinMembershipResult.Response response ->
          handleJoinResponse(response.response());
    };
  }

  private TextCommandInterpretationResult handleJoinResponse(
      net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse response) {
    if (response.hasError()) {
      String code = response.getError().getCode();
      if (code.isBlank()) {
        code = "JOIN_FAILED";
      }
      return errorResult(code, joinFailureMessage(code));
    }
    if (!response.getSuccess()) {
      String code = response.getOutcomeCode().isBlank() ? "JOIN_FAILED" : response.getOutcomeCode();
      return errorResult(code, joinFailureMessage(code));
    }
    return new TextCommandInterpretationResult(
        net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
        List.of(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.notice(
                "Membership is ready. Continue with CHARS and PLAY.")));
  }

  private String realmBrowseFailureMessage(String code) {
    return switch (code) {
      case "AUTH_UNAVAILABLE", "UNAVAILABLE", "DEADLINE_EXCEEDED" ->
          "Realm authority unavailable. Retry REALMS shortly.";
      case "CONNECT_SCOPE_MISMATCH", "ADMISSION_POINTER_UNAVAILABLE" ->
          "Realm routing changed or is unavailable. Retry REALMS.";
      default -> "Realm selection is unavailable.";
    };
  }

  private String joinFailureMessage(String code) {
    return switch (code) {
      case "CONNECT_SCOPE_MISMATCH" -> "Join scope expired or changed. Run REALMS again.";
      case "AUTH_UNAVAILABLE", "UNAVAILABLE", "DEADLINE_EXCEEDED" ->
          "Account authority unavailable. Retry JOIN shortly.";
      case "ENTITLEMENT_UNAVAILABLE" ->
          "Join policy could not be checked. Use REALMS and JOIN to start a new attempt later.";
      case "LOGIN_REQUIRED" -> "Log in before joining a world.";
      default -> "The selected world could not be joined.";
    };
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
      scriptEventPublisher.publishCommandEvent(
          context, ScriptEventGameplayCommands.synthetic("worlds", command));
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
