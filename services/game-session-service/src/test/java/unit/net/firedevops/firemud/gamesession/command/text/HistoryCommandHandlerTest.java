package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HistoryCommandHandlerTest {
  @Test
  void returnsUnavailableWhenFeatureDisabled() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(false, 10),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));

    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HISTORY, List.of(), "HISTORY"), context);

    assertFalse(result.commandResult().accepted());
    assertEquals("FEATURE_UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR FEATURE_UNAVAILABLE Command history is unavailable.",
        result.outputs().get(0).text());
  }

  @Test
  void returnsNoRecentCommandsWhenHistoryIsEmpty() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 10),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));
    Mockito.when(storage.findRecent(7L, 9L, 7001L, 10)).thenReturn(List.of());

    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HISTORY, List.of(), "HISTORY"), context);

    assertTrue(result.commandResult().accepted());
    assertEquals("No recent commands.", result.outputs().get(0).text());
  }

  @Test
  void defaultsToConfiguredMaxWhenCountIsOmitted() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 2),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));
    Mockito.when(storage.findRecent(7L, 9L, 7001L, 2)).thenReturn(List.of("LOOK", "SAY hi"));

    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HISTORY, List.of(), "HISTORY"), context);

    assertTrue(result.commandResult().accepted());
    assertEquals("LOOK\nSAY hi", result.outputs().get(0).text());
  }

  @Test
  void usesExplicitCountAndReturnsChronologicalEntries() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 10),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));
    Mockito.when(storage.findRecent(7L, 9L, 7001L, 5))
        .thenReturn(List.of("LOOK", "SAY hello", "INVENTORY"));
    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.HISTORY,
                List.of("5"),
                "HISTORY 5",
                "HISTORY",
                new TextCommandPayload.HistoryRequest(5)),
            context);

    assertTrue(result.commandResult().accepted());
    assertEquals("LOOK\nSAY hello\nINVENTORY", result.outputs().get(0).text());
    Mockito.verify(storage).findRecent(7L, 9L, 7001L, 5);
  }

  @Test
  void returnsUnavailableWhenSettingsResolutionFails() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver resolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    Mockito.when(resolver.commandHistory(Mockito.any(SessionContext.class)))
        .thenThrow(new IllegalStateException("settings unavailable"));
    HistoryCommandHandler handler = new HistoryCommandHandler(storage, resolver);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.HISTORY, List.of(), "HISTORY"), standardContext());

    assertUnavailable(result);
    Mockito.verifyNoInteractions(storage);
  }

  @Test
  void returnsUnavailableWhenHistoryStorageReadFails() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    Mockito.when(storage.findRecent(7L, 9L, 7001L, 10))
        .thenThrow(new IllegalStateException("storage unavailable"));
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 10),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.HISTORY, List.of(), "HISTORY"), standardContext());

    assertUnavailable(result);
  }

  @Test
  void rejectsRequestedCountAboveConfiguredMax() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 3),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));

    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.HISTORY,
                List.of("10"),
                "HISTORY 10",
                "HISTORY",
                new TextCommandPayload.HistoryRequest(10)),
            context);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    Mockito.verifyNoInteractions(storage);
  }

  @Test
  void rejectsMalformedHistoryCountWithoutReadingStorage() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 10),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));
    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.HISTORY,
                List.of("now"),
                "HISTORY now",
                "HISTORY",
                new TextCommandPayload.Tokens(List.of("now"))),
            context);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    Mockito.verifyNoInteractions(storage);
  }

  @Test
  void rejectsNonPositiveHistoryCountWithoutReadingStorage() {
    PlayerCommandHistoryStorageService storage =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    HistoryCommandHandler handler =
        new HistoryCommandHandler(
            storage,
            new EffectiveCommandHistorySettingsResolver(
                new FiremudCommandHistoryProperties(true, 10),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()));
    SessionContext context =
        new SessionContext(22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.HISTORY,
                List.of("0"),
                "HISTORY 0",
                "HISTORY",
                new TextCommandPayload.HistoryRequest(0)),
            context);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    Mockito.verifyNoInteractions(storage);
  }

  private SessionContext standardContext() {
    return new SessionContext(
        22L, 7L, 99L, "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");
  }

  private void assertUnavailable(TextCommandInterpretationResult result) {
    assertFalse(result.commandResult().accepted());
    assertEquals("FEATURE_UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR FEATURE_UNAVAILABLE Command history is unavailable.",
        result.outputs().get(0).text());
  }
}
