package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HelpTextCommandDispatchHandlerTest {
  private final HelpCommandHandler helpCommandHandler = new HelpCommandHandler();
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final HelpTextCommandDispatchHandler handler =
      new HelpTextCommandDispatchHandler(helpCommandHandler, scriptEventPublisher);

  @Test
  void publishesCommandEventForGameplayScopedHelp() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.HELP, List.of("who"), "HELP who"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "HELP".equals(gameplayCommand.getCommandName())
                        && "HELP who".equals(gameplayCommand.getCommandText())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("help-")));
  }

  @Test
  void skipsCommandEventWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.HELP, List.of(), "HELP"),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }
}
