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
  private static final String SESSION_ID = "session-1";
  private static final String LOOK_LINE = "LOOK";

  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final LookCommandHandler lookHandler = Mockito.mock(LookCommandHandler.class);
  private final LookTextCommandDispatchHandler handler =
      new LookTextCommandDispatchHandler(commandService, lookHandler);

  @Test
  void usesParsedViewPayloadForQuickLookShape() {
    when(commandService.enqueue(SESSION_ID, LOOK_LINE, false))
        .thenReturn(CommandEnqueueResult.success());
    when(lookHandler.describePlayerOutput(SESSION_ID, false))
        .thenReturn(PlayerOutput.message("quick look"));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                SESSION_ID,
                new TextCommand(
                    "look",
                    TextCommandType.LOOK,
                    List.of(),
                    LOOK_LINE,
                    LOOK_LINE,
                    new TextCommandPayload.ViewRequest(LOOK_LINE, false)),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(commandService).enqueue(SESSION_ID, LOOK_LINE, false);
    verify(lookHandler).describePlayerOutput(SESSION_ID, false);
  }
}
