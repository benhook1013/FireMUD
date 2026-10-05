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
  private static final String ACCOUNT_UUID = "f2ed193b-12c1-4c96-bcad-c162229af440";
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final LogoutCommandHandler handler = new LogoutCommandHandler(sessionContextService);

  @Test
  void authenticatedSharedRuntimeLogoutFailsWithoutMutatingContext() {
    when(sessionContextService.findBySessionId(41L))
        .thenReturn(Optional.of(context(41L, ACCOUNT_UUID, 1L, "SHARED")));

    assertUnavailable(handler.handle("41", logoutCommand()));

    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void authenticatedIsolatedRuntimeLogoutFailsWithoutMutatingContext() {
    when(sessionContextService.findBySessionId(41L))
        .thenReturn(Optional.of(context(41L, ACCOUNT_UUID, 7L, "ISOLATED")));

    assertUnavailable(handler.handle("41", logoutCommand()));

    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void loggedOutContextRemainsNotLoggedInWithoutMutation() {
    when(sessionContextService.findBySessionId(41L))
        .thenReturn(Optional.of(context(41L, null, 1L, "SHARED")));

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
  void missingSessionRemainsNotLoggedIn() {
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.empty());

    LogoutCommandHandlingResult result = handler.handle("41", logoutCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    assertThat(result.commandResult().errorMessage()).isEqualTo("You are not logged in.");
    verify(sessionContextService).findBySessionId(41L);
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void invalidSessionIdDoesNotLookUpOrMutateContext() {
    LogoutCommandHandlingResult result = handler.handle("not-a-session", logoutCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_LOGGED_IN");
    verifyNoMoreInteractions(sessionContextService);
  }

  @Test
  void unavailableSessionAuthorityReturnsRetryableFailureWithoutMutation() {
    when(sessionContextService.findBySessionId(41L))
        .thenThrow(new IllegalStateException("session store unavailable"));

    assertUnavailable(handler.handle("41", logoutCommand()));

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
      long sessionId, String accountId, long gameInstanceId, String scope) {
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
