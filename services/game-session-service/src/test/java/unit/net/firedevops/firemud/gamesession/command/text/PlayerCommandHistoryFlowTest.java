package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerCommandHistoryFlowTest {
  @Test
  void doesNotRecordHistoryDisplayCommands() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    SessionAuthenticationService sessionAuthenticationService =
        Mockito.mock(SessionAuthenticationService.class);
    SessionContext context = new SessionContext(1L, 7L, 9L, 17L, 11L, "R-1", "token");
    List<String> storedCommands = new ArrayList<>(List.of("LOOK"));
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(storage.findRecent(7L, 11L, 17L, 10))
        .thenAnswer(invocation -> List.copyOf(storedCommands));
    Mockito.doAnswer(
            invocation -> {
              storedCommands.add(invocation.getArgument(3, String.class));
              return null;
            })
        .when(storage)
        .append(anyLong(), anyLong(), anyLong(), anyString(), anyInt());

    EffectiveCommandHistorySettingsResolver settingsResolver =
        new EffectiveCommandHistorySettingsResolver(
            new FiremudCommandHistoryProperties(10),
            (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty());
    HistoryCommandHandler historyHandler =
        new HistoryCommandHandler(
            storage, settingsResolver, CommandCapabilitiesTestSupport.allEnabled());
    TextCommandInterpreter interpreter =
        new TextCommandInterpreter(
            sessionAuthenticationService,
            new net.firedevops.firemud.gamesession.presentation.PromptComposer(),
            Mockito.mock(TextCommandParser.class),
            new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider())),
            new TextCommandDispatcher(
                List.of(new HistoryTextCommandDispatchHandler(historyHandler))),
            new PlayerCommandHistoryRecorder(
                storage, settingsResolver, CommandCapabilitiesTestSupport.allEnabled()));
    TextCommand history = new TextCommand(TextCommandType.HISTORY, List.of(), "HISTORY");

    TextCommandInterpretationResult first = interpreter.interpret("41", history, false);

    assertThat(historyNoticeTexts(first)).containsExactly("LOOK");
    assertThat(storedCommands).containsExactly("LOOK");

    TextCommandInterpretationResult second = interpreter.interpret("41", history, false);

    assertThat(historyNoticeTexts(second)).containsExactly("LOOK");
    assertThat(storedCommands).containsExactly("LOOK");
  }

  private List<String> historyNoticeTexts(TextCommandInterpretationResult result) {
    return result.outputs().stream()
        .filter(output -> output.kind() == PlayerOutputKind.NOTICE)
        .map(PlayerOutput::text)
        .toList();
  }
}
