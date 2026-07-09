package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.CommandService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LookTextCommandDispatchHandlerTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final LookCommandHandler lookHandler = Mockito.mock(LookCommandHandler.class);
  private final LookTextCommandDispatchHandler handler =
      new LookTextCommandDispatchHandler(commandService, lookHandler);

  @Test
  void usesParsedViewPayloadForQuickLookShape() {
    when(commandService.enqueue("session-1", "LOOK", false))
        .thenReturn(CommandEnqueueResult.success());
    when(lookHandler.describePlayerOutput("session-1", false))
        .thenReturn(PlayerOutput.message("quick look"));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(
                    "look",
                    TextCommandType.LOOK,
                    List.of(),
                    "LOOK",
                    "LOOK",
                    new TextCommandPayload.ViewRequest("LOOK", false)),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(commandService).enqueue("session-1", "LOOK", false);
    verify(lookHandler).describePlayerOutput("session-1", false);
  }
}
