package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.firedevops.firemud.common.settings.EffectiveCommandCapabilitiesSettingsResolver;
import net.firedevops.firemud.common.settings.PlayerCommandCapability;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class TextCommandInterpreterCapabilityTest {

  @ParameterizedTest
  @MethodSource("disabledOptionalCommands")
  void rejectsDisabledStandardOptionalCommandsBeforeDispatch(
      TextCommandType type, String rawLine, String expectedCapability) {
    SessionAuthenticationService sessionAuthenticationService =
        Mockito.mock(SessionAuthenticationService.class);
    TextCommandDispatcher dispatcher = Mockito.mock(TextCommandDispatcher.class);
    SessionContext gameplayContext =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt");
    when(sessionAuthenticationService.resolveSessionContext("41"))
        .thenReturn(Optional.of(gameplayContext));
    TextCommandInterpreter interpreter =
        new TextCommandInterpreter(
            sessionAuthenticationService,
            new PromptComposer(),
            new TextCommandParser(),
            new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider())),
            null,
            dispatcher,
            CommandCapabilitiesTestSupport.resolver(false, false, false, false),
            AcceptedCommandHistoryRecorder.NOOP);

    TextCommandInterpretationResult result =
        interpreter.interpret("41", new TextCommand(type, List.of(), rawLine), false);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FEATURE_UNAVAILABLE");
    assertThat(((ErrorOutput) result.outputs().getFirst().payload()).arguments())
        .containsEntry("capability", expectedCapability);
    verifyNoInteractions(dispatcher);
  }

  @org.junit.jupiter.api.Test
  void failsClosedWhenCapabilityResolutionIsUnavailable() {
    SessionAuthenticationService sessionAuthenticationService =
        Mockito.mock(SessionAuthenticationService.class);
    TextCommandDispatcher dispatcher = Mockito.mock(TextCommandDispatcher.class);
    EffectiveCommandCapabilitiesSettingsResolver capabilitiesResolver =
        Mockito.mock(EffectiveCommandCapabilitiesSettingsResolver.class);
    SessionContext gameplayContext =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt");
    when(sessionAuthenticationService.resolveSessionContext("41"))
        .thenReturn(Optional.of(gameplayContext));
    when(capabilitiesResolver.isEnabled(PlayerCommandCapability.SOCIAL, 22L, 7L))
        .thenThrow(new IllegalStateException("settings authority unavailable"));
    TextCommandInterpreter interpreter =
        new TextCommandInterpreter(
            sessionAuthenticationService,
            new PromptComposer(),
            new TextCommandParser(),
            new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider())),
            null,
            dispatcher,
            capabilitiesResolver,
            AcceptedCommandHistoryRecorder.NOOP);

    TextCommandInterpretationResult result =
        interpreter.interpret(
            "41", new TextCommand(TextCommandType.SAY, List.of(), "SAY hello"), false);

    assertThat(result.commandResult().errorCode()).isEqualTo("FEATURE_UNAVAILABLE");
    verifyNoInteractions(dispatcher);
  }

  private static Stream<Arguments> disabledOptionalCommands() {
    return Stream.of(
        Arguments.of(TextCommandType.SAY, "SAY hello", "SOCIAL"),
        Arguments.of(TextCommandType.WHO, "WHO", "PRESENCE"),
        Arguments.of(TextCommandType.INVENTORY, "INVENTORY", "INVENTORY"),
        Arguments.of(TextCommandType.HISTORY, "HISTORY", "COMMAND_HISTORY"));
  }
}
