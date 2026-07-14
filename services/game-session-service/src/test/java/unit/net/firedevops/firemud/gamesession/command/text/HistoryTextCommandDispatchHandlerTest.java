package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HistoryTextCommandDispatchHandlerTest {
  private final PlayerCommandHistoryStorageService historyStorageService =
      Mockito.mock(PlayerCommandHistoryStorageService.class);
  private final HistoryCommandHandler historyHandler =
      new HistoryCommandHandler(
          historyStorageService,
          new EffectiveCommandHistorySettingsResolver(
              new FiremudCommandHistoryProperties(10),
              (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()),
          CommandCapabilitiesTestSupport.allEnabled());
  private final HistoryTextCommandDispatchHandler handler =
      new HistoryTextCommandDispatchHandler(historyHandler);

  @Test
  void dispatchesToHistoryHandler() {
    Mockito.when(historyStorageService.findRecent(7L, 9L, 7001L, 2))
        .thenReturn(List.of("LOOK", "SAY hi"));
    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "1",
                new TextCommand(
                    TextCommandType.HISTORY,
                    List.of("2"),
                    "HISTORY 2",
                    "HISTORY",
                    new TextCommandPayload.HistoryRequest(2)),
                false,
                Optional.of(context)));

    assertThat(handler.group()).isEqualTo(TextCommandDispatchGroup.HISTORY);
    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("LOOK\nSAY hi");
  }
}
