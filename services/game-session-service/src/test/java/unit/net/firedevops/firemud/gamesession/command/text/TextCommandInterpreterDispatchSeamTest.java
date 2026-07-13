package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TextCommandInterpreterDispatchSeamTest {

  @Test
  void interpretUsesRegistryAndDispatcherInsteadOfCommandTypeBranches() {
    SessionAuthenticationService authenticationService =
        Mockito.mock(SessionAuthenticationService.class);
    SessionContext gameplayContext =
        new SessionContext(
            1L, 7L, 8L, "player@example.com", 9L, "Demo", 10L, "R-1", "token", "en-NZ", 10L);
    Mockito.when(authenticationService.resolveSessionContext("1"))
        .thenReturn(Optional.of(gameplayContext));

    PromptComposer promptComposer = Mockito.mock(PromptComposer.class);
    AtomicReference<TextCommandDispatchGroup> dispatchedGroup = new AtomicReference<>();
    AtomicReference<TextCommandType> dispatchedType = new AtomicReference<>();
    TextCommandInterpretationResult expectedDispatchResult =
        new TextCommandInterpretationResult(
            CommandEnqueueResult.success(), List.of(PlayerOutput.notice("handled-by-help-group")));
    TextCommandDispatcher dispatcher =
        new TextCommandDispatcher(
            List.of(
                new TextCommandDispatchHandler() {
                  @Override
                  public TextCommandDispatchGroup group() {
                    return TextCommandDispatchGroup.HELP;
                  }

                  @Override
                  public TextCommandInterpretationResult handle(
                      TextCommandDispatchRequest request) {
                    dispatchedGroup.set(group());
                    dispatchedType.set(request.command().type());
                    return expectedDispatchResult;
                  }
                }));
    TextCommandRegistry registry =
        new TextCommandRegistry() {
          @Override
          public Optional<TextCommandDefinition> findDefinition(TextCommandType type) {
            return Optional.of(
                new TextCommandDefinition(
                    type,
                    List.of("look"),
                    TextCommandDispatchGroup.HELP,
                    TextCommandStageRequirement.GAMEPLAY,
                    TextCommandPromptPolicy.NEVER,
                    TextCommandActionCategory.META,
                    TextCommandSource.PLATFORM_BUILT_IN));
          }

          @Override
          public Optional<TextCommandDefinition> findDefinitionByAlias(String alias) {
            return Optional.empty();
          }
        };
    AcceptedCommandHistoryRecorder commandHistoryRecorder =
        Mockito.mock(AcceptedCommandHistoryRecorder.class);
    TextCommandInterpreter interpreter =
        new TextCommandInterpreter(
            authenticationService,
            promptComposer,
            new TextCommandParser(),
            registry,
            dispatcher,
            commandHistoryRecorder);

    TextCommandInterpretationResult actual = interpreter.interpret("1", "LOOK", false);

    assertEquals(TextCommandDispatchGroup.HELP, dispatchedGroup.get());
    assertEquals(TextCommandType.LOOK, dispatchedType.get());
    assertEquals(expectedDispatchResult.commandResult(), actual.commandResult());
    assertEquals(expectedDispatchResult.outputs(), actual.outputs());
    assertEquals(
        expectedDispatchResult.reconnectRedrawRecommended(), actual.reconnectRedrawRecommended());
    assertEquals(false, actual.meaningfulGameplayActivity());
    ArgumentCaptor<TextCommand> recordedCommand = ArgumentCaptor.forClass(TextCommand.class);
    verify(commandHistoryRecorder)
        .record(
            recordedCommand.capture(),
            Mockito.eq(true),
            Mockito.eq(expectedDispatchResult.commandResult()),
            Mockito.eq(Optional.of(gameplayContext)),
            Mockito.eq(Optional.of(gameplayContext)));
    assertEquals(TextCommandType.LOOK, recordedCommand.getValue().type());
    assertEquals("LOOK", recordedCommand.getValue().rawLine());
    assertEquals(List.of(), recordedCommand.getValue().args());
  }
}
