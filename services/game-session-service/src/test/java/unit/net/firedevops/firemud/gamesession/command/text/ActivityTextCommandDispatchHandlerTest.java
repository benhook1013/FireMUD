package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.CommandService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ActivityTextCommandDispatchHandlerTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final ActivityTextCommandDispatchHandler handler =
      new ActivityTextCommandDispatchHandler(commandService);

  @Test
  void enqueuesBuiltInActivityCommand() {
    when(commandService.enqueue("session-1", "AFK ON", false))
        .thenReturn(CommandEnqueueResult.success());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.AFK, List.of("ON"), "AFK ON"),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(commandService).enqueue("session-1", "AFK ON", false);
  }

  @Test
  void enqueuesAuthoredActivityCommandWithoutTypeSpecificGate() {
    when(commandService.enqueue("session-1", "MEDITATE", true))
        .thenReturn(CommandEnqueueResult.success());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(
                    "meditate",
                    TextCommandType.AUTHORED,
                    List.of(),
                    "MEDITATE",
                    "MEDITATE",
                    new TextCommandPayload.AuthoredActionInvocation("meditate", List.of())),
                true,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(commandService).enqueue("session-1", "MEDITATE", true);
  }
}
