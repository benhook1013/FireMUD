package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LogoutCommandHandlerTest {
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final LogoutCommandHandler handler = new LogoutCommandHandler(sessionContextService);

  @Test
  void authenticatedSharedRuntimeLogoutFailsRetryablyWithoutMutatingContext() {
    SessionContext context = context(41L, 123L, 1L, "SHARED");
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(context));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand());

    assertUnavailable(result);
    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void authenticatedIsolatedRuntimeLogoutFailsRetryablyWithoutMutatingContext() {
    SessionContext context = context(41L, 123L, 7L, "ISOLATED");
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(context));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand());

    assertUnavailable(result);
    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void unauthenticatedContextStillReturnsNotLoggedInWithoutMutation() {
    when(sessionContextService.findBySessionId(41L))
        .thenReturn(Optional.of(context(41L, 0L, 1L, "SHARED")));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    assertThat(result.commandResult().errorMessage()).isEqualTo("You are not logged in.");
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.kind()).isEqualTo(PlayerOutputKind.ERROR);
              assertThat(output.text()).isEqualTo("ERROR NOT_LOGGED_IN You are not logged in.");
            });
    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void missingSessionStillReturnsNotLoggedIn() {
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.empty());

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void invalidSessionIdReturnsNotLoggedInWithoutLookingUpOrMutatingContext() {
    LogoutCommandHandlingResult result = handler.handle("not-a-session", logoutCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void unavailableSessionAuthorityReturnsRetryableFailure() {
    when(sessionContextService.findBySessionId(41L))
        .thenThrow(new IllegalStateException("session store unavailable"));

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand());

    assertUnavailable(result);
    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  private static void assertUnavailable(LogoutCommandHandlingResult result) {
    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("LOGOUT_UNAVAILABLE");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Logout is temporarily unavailable. Please try again.");
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.kind()).isEqualTo(PlayerOutputKind.ERROR);
              assertThat(output.text())
                  .isEqualTo(
                      "ERROR LOGOUT_UNAVAILABLE Logout is temporarily unavailable. Please try again.");
            });
  }

  private static TextCommand logoutCommand() {
    return new TextCommand(TextCommandType.LOGOUT, java.util.List.of(), "LOGOUT");
  }

  private static SessionContext context(
      long sessionId, long accountId, long gameInstanceId, String scope) {
    return new SessionContext(
        sessionId,
        22L,
        accountId,
        "demo@example.com",
        123L,
        "demo",
        gameInstanceId,
        "R-1021",
        "jwt",
        "en-NZ",
        gameInstanceId,
        "demo",
        scope.equals("ISOLATED") ? "private" : "production",
        1L,
        scope);
  }
}
