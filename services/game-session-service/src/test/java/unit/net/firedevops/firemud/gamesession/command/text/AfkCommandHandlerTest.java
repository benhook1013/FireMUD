package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AfkCommandHandlerTest {
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final GameplayPresenceService gameplayPresenceService =
      Mockito.mock(GameplayPresenceService.class);
  private final AfkCommandHandler handler =
      new AfkCommandHandler(sessionAuthenticationService, gameplayPresenceService);

  @Test
  void afkOnSetsExplicitAfkOnPresence() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1021", null, "en-NZ", 1L);
    when(sessionAuthenticationService.resolveSessionContext("7")).thenReturn(Optional.of(context));

    AfkCommandHandlingResult result =
        handler.handle("7", new TextCommand(TextCommandType.AFK, java.util.List.of(), "AFK"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).extracting(PlayerOutput::text).contains("AFK enabled.");
    verify(gameplayPresenceService).setExplicitAfk(7L, true);
  }

  @Test
  void afkOffClearsExplicitAfkOnPresence() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1021", null, "en-NZ", 1L);
    when(sessionAuthenticationService.resolveSessionContext("7")).thenReturn(Optional.of(context));

    AfkCommandHandlingResult result =
        handler.handle(
            "7",
            new TextCommand(
                TextCommandType.AFK,
                java.util.List.of("OFF"),
                "AFK OFF",
                "AFK",
                new TextCommandPayload.AfkRequest(false)));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(gameplayPresenceService).setExplicitAfk(7L, false);
  }

  @Test
  void afkRequiresGameplayContext() {
    when(sessionAuthenticationService.resolveSessionContext("7")).thenReturn(Optional.empty());

    AfkCommandHandlingResult result =
        handler.handle("7", new TextCommand(TextCommandType.AFK, java.util.List.of(), "AFK"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_PLAYING");
  }

  @Test
  void afkRejectsPartialGameplayIdentityShellFromResolvedSession() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "demo@example.com", 0L, null, 1L, "R-1021", null, "en-NZ", 1L);
    when(sessionAuthenticationService.resolveSessionContext("7")).thenReturn(Optional.of(context));

    AfkCommandHandlingResult result =
        handler.handle("7", new TextCommand(TextCommandType.AFK, java.util.List.of(), "AFK"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_PLAYING");
    verifyNoInteractions(gameplayPresenceService);
  }

  @Test
  void directAfkRejectsPartialGameplayIdentityShell() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "demo@example.com", 0L, null, 1L, "R-1021", null, "en-NZ", 1L);

    AfkCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.AFK,
                java.util.List.of("OFF"),
                "AFK OFF",
                "AFK",
                new TextCommandPayload.AfkRequest(false)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("NOT_PLAYING");
    verifyNoInteractions(gameplayPresenceService);
  }
}
