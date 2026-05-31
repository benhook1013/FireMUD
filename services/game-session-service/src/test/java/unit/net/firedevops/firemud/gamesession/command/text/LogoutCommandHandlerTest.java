package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
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
  private final GameplayWorldCatalog gameplayWorldCatalog =
      Mockito.mock(GameplayWorldCatalog.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final ScreenBufferService screenBufferService = Mockito.mock(ScreenBufferService.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);

  private final LogoutCommandHandler handler =
      new LogoutCommandHandler(
          sessionAuthenticationService,
          sessionContextService,
          gameInstanceService,
          gameplayWorldCatalog,
          gameplayPresenceLifecycleService,
          firstPartyConnectContextRegistry,
          screenBufferService,
          scriptEventPublisher);

  @Test
  void logoutClearsGameplayAndReplayStateWithoutStoppingSharedRuntime() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            1L,
            "1021",
            "jwt",
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(gameplayWorldCatalog.resolveRealmByRuntimeTarget(22L, 7L))
        .thenReturn(
            Optional.of(
                new GameplayWorldCatalog.RealmView(
                    "private", "Private", 22L, 7L, 1L, true, false, true, "ISOLATED", "REQUIRED")));

    LogoutCommandHandlingResult result = handler.handle("41");

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    verify(scriptEventPublisher)
        .publishCommandEvent(context, command("logout-command:41:1:123", "LOGOUT"));
    verify(scriptEventPublisher, never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
    verify(screenBufferService).clear(22L, 1L, 123L);
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
            "1021",
            "jwt",
            "en-NZ",
            7L,
            "demo",
            "private",
            1L,
            "ISOLATED");
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));

    LogoutCommandHandlingResult result = handler.handle("41");

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService).stopSession(7L);
    verify(screenBufferService).clear(22L, 7L, 123L);
  }

  @Test
  void logoutDoesNotStopSharedRuntimeWhenSharedStateComesFromWorldRealmSelectors() {
    SessionContext context =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "demo",
            1L,
            "1021",
            "jwt",
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            null);
    GameplayWorldCatalog.WorldView world =
        new GameplayWorldCatalog.WorldView(
            "demo",
            "Demo",
            java.util.List.of(
                new GameplayWorldCatalog.RealmView(
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
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));
    when(gameplayWorldCatalog.resolveWorld("demo")).thenReturn(Optional.of(world));
    when(gameplayWorldCatalog.resolveRealm(world, "production"))
        .thenReturn(Optional.of(world.realms().getFirst()));
    when(gameplayWorldCatalog.resolveRealmByRuntimeTarget(22L, 1L)).thenReturn(Optional.empty());

    LogoutCommandHandlingResult result = handler.handle("41");

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
    verify(screenBufferService).clear(22L, 1L, 123L);
  }

  @Test
  void logoutBeforeLoginReturnsBoundedFailure() {
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.empty());

    LogoutCommandHandlingResult result = handler.handle("41");

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    verify(scriptEventPublisher, never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(sessionContextService, never()).deleteBySessionId(Mockito.anyLong(), Mockito.anyLong());
  }

  private static net.firedevops.firemud.gamesession.entity.GameplayCommand command(
      String commandId, String commandName) {
    net.firedevops.firemud.gamesession.entity.GameplayCommand gameplayCommand =
        new net.firedevops.firemud.gamesession.entity.GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    return gameplayCommand;
  }
}
