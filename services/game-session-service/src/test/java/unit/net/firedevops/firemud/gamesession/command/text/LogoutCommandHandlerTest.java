package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LogoutCommandHandlerTest {
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);

  private final LogoutCommandHandler handler =
      new LogoutCommandHandler(
          sessionAuthenticationService,
          sessionContextService,
          gameInstanceService,
          gameplayAdmissionPointerAuthorityService,
          gameplayPresenceLifecycleService,
          firstPartyConnectContextRegistry,
          scriptEventPublisher);

  @Test
  void logoutPreservesDurableReplayContextWithoutStoppingSharedRuntime() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1021",
            "jwt",
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("quit"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    verify(scriptEventPublisher)
        .publishCommandEvent(context, command("logout-command:41:1:123", "LOGOUT", "quit"));
    verify(scriptEventPublisher, never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
    verify(gameplayPresenceLifecycleService)
        .recordDisconnected(41L, AccountRecentPresenceDisposition.LOGOUT);
    verify(firstPartyConnectContextRegistry).unregister(41L);
    verify(sessionContextService).deleteBySessionId(22L, 41L);
    verifyNoMoreInteractions(gameplayPresenceLifecycleService);
  }

  @Test
  void logoutStopsIsolatedRuntime() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            7L,
            "R-1021",
            "jwt",
            "en-NZ",
            7L,
            "demo",
            "private",
            1L,
            "ISOLATED");
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService).stopSession(7L);
  }

  @Test
  void logoutDoesNotStopSharedRuntimeWhenCurrentRuntimeAuthorityIsShared() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1021",
            "jwt",
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            null);
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(
            java.util.List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    1L,
                    1L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
  }

  @Test
  void logoutDoesNotStopSharedRuntimeWhenNormalizationClearsProjectedBinding() {
    SessionContext normalizedContext =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1021",
            "jwt",
            "en-NZ",
            1L,
            null,
            null,
            0L,
            null);
    when(sessionAuthenticationService.resolveSessionContext("41"))
        .thenReturn(Optional.of(normalizedContext));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
  }

  @Test
  void logoutDoesNotStopRuntimeWhenPointerAuthorityIsUnknown() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            7L,
            "R-1021",
            "jwt",
            "en-NZ",
            7L,
            null,
            null,
            0L,
            null);
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(java.util.List.of());

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
  }

  @Test
  void logoutDoesNotStopRuntimeWhenCurrentRuntimeAuthorityIsAmbiguous() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            7L,
            "R-1021",
            "jwt",
            "en-NZ",
            7L,
            null,
            null,
            0L,
            null);
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            java.util.List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    1L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW"),
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "private",
                    "Private",
                    22L,
                    7L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
  }

  @Test
  void logoutDoesNotStopRuntimeWhenSingularRuntimeAuthorityIsIncomplete() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            7L,
            "R-1021",
            "jwt",
            "en-NZ",
            7L,
            null,
            null,
            0L,
            null);
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            java.util.List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    1L,
                    true,
                    true,
                    false,
                    "",
                    "ALLOW_NEW")));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
  }

  @Test
  void logoutSkipsGameplayCleanupForPartialGameplayShell() {
    SessionContext partial =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            0L,
            null,
            "jwt",
            "en-NZ",
            1L,
            null,
            null,
            0L,
            null);
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(partial));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(scriptEventPublisher, never()).publishCommandEvent(Mockito.any(), Mockito.any());
    verify(gameplayPresenceLifecycleService)
        .recordDisconnected(41L, AccountRecentPresenceDisposition.LOGOUT);
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
  }

  @Test
  void logoutBeforeLoginReturnsBoundedFailure() {
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.empty());

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand("LOGOUT"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    verify(scriptEventPublisher, never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
    verify(sessionContextService, never()).deleteBySessionId(Mockito.anyLong(), Mockito.anyLong());
  }

  private static TextCommand logoutCommand(String rawLine) {
    return new TextCommand(TextCommandType.LOGOUT, java.util.List.of(), rawLine);
  }

  private static net.firedevops.firemud.gamesession.entity.GameplayCommand command(
      String commandId, String commandName, String commandText) {
    net.firedevops.firemud.gamesession.entity.GameplayCommand gameplayCommand =
        new net.firedevops.firemud.gamesession.entity.GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    gameplayCommand.setCommandText(commandText);
    return gameplayCommand;
  }
}
