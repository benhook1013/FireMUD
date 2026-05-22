package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuthoredActionTextCommandDispatchHandlerTest {
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final AuthoredActionTextCommandDispatchHandler handler =
      new AuthoredActionTextCommandDispatchHandler(
          new AuthoredActionCommandHandler(
              new ConfiguredAuthoredActionCatalog(configuredAuthoredActions())),
          scriptEventPublisher);

  @Test
  void publishesCommandEventForGameplayScopedAuthoredAction() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "room-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                authoredAction("wave-salute", "salute captain", List.of("captain")),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "wave-salute".equals(gameplayCommand.getCommandName())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("authored-")));
  }

  @Test
  void skipsCommandEventWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                authoredAction("wave-salute", "salute captain", List.of("captain")),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  private static TextCommand authoredAction(String commandId, String rawLine, List<String> args) {
    return new TextCommand(
        commandId,
        TextCommandType.AUTHORED,
        args,
        rawLine,
        args.isEmpty() ? commandId : args.getFirst(),
        new TextCommandPayload.AuthoredActionInvocation(commandId, args));
  }

  private static AuthoredActionProperties configuredAuthoredActions() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setActionId("wave-salute");
    action.setCommandId("wave-salute");
    action.setAliases(List.of("salute"));
    action.setNoticeText("You salute smartly.");
    properties.setActions(List.of(action));
    return properties;
  }
}
