package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import org.junit.jupiter.api.Test;

class TextCommandDispatcherTest {

  @Test
  void dispatchRoutesToRegisteredGroupHandler() {
    TextCommandInterpretationResult expected =
        new TextCommandInterpretationResult(CommandEnqueueResult.success());
    TextCommandDispatcher dispatcher =
        new TextCommandDispatcher(
            List.of(
                new FixedHandler(TextCommandDispatchGroup.HELP, expected),
                new FixedHandler(
                    TextCommandDispatchGroup.MOVE,
                    new TextCommandInterpretationResult(
                        CommandEnqueueResult.failure("WRONG", "wrong")))));

    TextCommandInterpretationResult actual =
        dispatcher.dispatch(
            TextCommandDispatchGroup.HELP,
            new TextCommandDispatchRequest(
                "1",
                new TextCommand(
                    TextCommandType.HELP, List.of(), "HELP", "HELP", new TextCommandPayload.None()),
                false,
                Optional.empty()));

    assertEquals(expected.commandResult(), actual.commandResult());
  }

  @Test
  void duplicateGroupRegistrationFailsFast() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new TextCommandDispatcher(
                List.of(
                    new FixedHandler(
                        TextCommandDispatchGroup.HELP,
                        new TextCommandInterpretationResult(CommandEnqueueResult.success())),
                    new FixedHandler(
                        TextCommandDispatchGroup.HELP,
                        new TextCommandInterpretationResult(CommandEnqueueResult.success())))));
  }

  private record FixedHandler(
      TextCommandDispatchGroup group, TextCommandInterpretationResult result)
      implements TextCommandDispatchHandler {

    @Override
    public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
      return result;
    }
  }
}
