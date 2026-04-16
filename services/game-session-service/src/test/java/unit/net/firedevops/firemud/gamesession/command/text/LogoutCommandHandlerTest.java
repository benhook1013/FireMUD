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
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final ScreenBufferService screenBufferService = Mockito.mock(ScreenBufferService.class);

  private final LogoutCommandHandler handler =
      new LogoutCommandHandler(
          sessionAuthenticationService,
          sessionContextService,
          gameInstanceService,
          gameplayPresenceLifecycleService,
          firstPartyConnectContextRegistry,
          screenBufferService);

  @Test
  void logoutClearsGameplayAndReplayState() {
    SessionContext context =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "1021", "jwt");
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.of(context));

    LogoutCommandHandlingResult result = handler.handle("41");

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    verify(gameInstanceService).stopSession(1L);
    verify(screenBufferService).clear(22L, 1L, 123L);
    verify(gameplayPresenceLifecycleService)
        .recordDisconnected(41L, AccountRecentPresenceDisposition.LOGOUT);
    verify(firstPartyConnectContextRegistry).unregister(41L);
    verify(sessionContextService).deleteBySessionId(22L, 41L);
    verifyNoMoreInteractions(gameplayPresenceLifecycleService);
  }

  @Test
  void logoutBeforeLoginReturnsBoundedFailure() {
    when(sessionAuthenticationService.resolveSessionContext("41")).thenReturn(Optional.empty());

    LogoutCommandHandlingResult result = handler.handle("41");

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    verify(gameInstanceService, never()).stopSession(Mockito.anyLong());
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(sessionContextService, never()).deleteBySessionId(Mockito.anyLong(), Mockito.anyLong());
  }
}
