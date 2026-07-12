package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerCommandHistoryRecorderTest {
  private final PlayerCommandHistoryStorageService storageService =
      Mockito.mock(PlayerCommandHistoryStorageService.class);
  private final EffectiveCommandHistorySettingsResolver settingsResolver =
      Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
  private final PlayerCommandHistoryRecorder recorder =
      new PlayerCommandHistoryRecorder(storageService, settingsResolver);

  @Test
  void recordsAcceptedGameplayCommandWithEffectiveRetention() {
    SessionContext context = gameplayContext(17L);
    when(settingsResolver.commandHistory(context))
        .thenReturn(new FiremudCommandHistoryProperties(true, 12));

    recorder.record(
        new TextCommand(TextCommandType.LOOK, List.of(), "  LOOK  "),
        CommandEnqueueResult.success(),
        Optional.of(context),
        Optional.of(context));

    verify(storageService).append(7L, 11L, 17L, "LOOK", 12);
  }

  @Test
  void prefersPostDispatchIdentityForAcceptedPlay() {
    SessionContext before = gameplayContext(17L);
    SessionContext after = gameplayContext(19L);
    when(settingsResolver.commandHistory(after))
        .thenReturn(new FiremudCommandHistoryProperties(true, 10));

    recorder.record(
        new TextCommand(
            TextCommandType.PLAY, List.of("demo", "realm", "next"), "PLAY demo realm next"),
        CommandEnqueueResult.success(),
        Optional.of(before),
        Optional.of(after));

    verify(storageService).append(7L, 11L, 19L, "PLAY demo realm next", 10);
  }

  @Test
  void fallsBackToPreDispatchIdentityForAcceptedLogout() {
    SessionContext before = gameplayContext(17L);
    when(settingsResolver.commandHistory(before))
        .thenReturn(new FiremudCommandHistoryProperties(true, 10));

    recorder.record(
        new TextCommand(TextCommandType.LOGOUT, List.of(), "LOGOUT"),
        CommandEnqueueResult.success(),
        Optional.of(before),
        Optional.empty());

    verify(storageService).append(7L, 11L, 17L, "LOGOUT", 10);
  }

  @Test
  void doesNotRecordRejectedOrCredentialCommands() {
    SessionContext context = gameplayContext(17L);

    recorder.record(
        new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
        CommandEnqueueResult.failure("FAILED", "no"),
        Optional.of(context),
        Optional.of(context));
    recorder.record(
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("player@example.com", "secret", "123456"),
            "LOGIN player@example.com secret 123456"),
        CommandEnqueueResult.success(),
        Optional.of(context),
        Optional.of(context));

    verify(storageService, never())
        .append(
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyString(),
            Mockito.anyInt());
    verify(settingsResolver, never()).commandHistory(Mockito.any());
  }

  @Test
  void doesNotRecordWhenCapabilityIsDisabled() {
    SessionContext context = gameplayContext(17L);
    when(settingsResolver.commandHistory(context))
        .thenReturn(new FiremudCommandHistoryProperties(false, 10));

    recorder.record(
        new TextCommand(TextCommandType.HELP, List.of(), "HELP"),
        CommandEnqueueResult.success(),
        Optional.of(context),
        Optional.of(context));

    verify(storageService, never())
        .append(
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyString(),
            Mockito.anyInt());
  }

  @Test
  void quarantinesHistoryPersistenceFailure() {
    SessionContext context = gameplayContext(17L);
    when(settingsResolver.commandHistory(context))
        .thenReturn(new FiremudCommandHistoryProperties(true, 10));
    Mockito.doThrow(new IllegalStateException("history storage unavailable"))
        .when(storageService)
        .append(7L, 11L, 17L, "LOOK", 10);

    assertDoesNotThrow(
        () ->
            recorder.record(
                new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
                CommandEnqueueResult.success(),
                Optional.of(context),
                Optional.of(context)));
  }

  private SessionContext gameplayContext(long characterId) {
    return new SessionContext(1L, 7L, 9L, characterId, 11L, "R-1", "token");
  }
}
