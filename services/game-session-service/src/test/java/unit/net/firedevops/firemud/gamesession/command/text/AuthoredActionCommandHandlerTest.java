package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class AuthoredActionCommandHandlerTest {

  @Test
  void unknownAuthoredActionFailsClosed() {
    AuthoredActionCommandHandler handler = new AuthoredActionCommandHandler();

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                "missing",
                TextCommandType.AUTHORED,
                java.util.List.of(),
                "salute",
                "salute",
                new TextCommandPayload.AuthoredActionInvocation("missing", java.util.List.of())));

    assertEquals(false, result.commandResult().accepted());
    assertEquals("UNKNOWN_AUTHORED_ACTION", result.commandResult().errorCode());
    assertEquals(PlayerOutputKind.ERROR, result.outputs().getFirst().kind());
  }

  @Test
  void admittedAuthoredActionFailsClosedUntilItsDeclaredEffectHasARuntimeHandler() {
    AuthoredActionCommandHandler handler = new AuthoredActionCommandHandler();
    TextCommand command =
        new TextCommand(
            "wave-salute",
            TextCommandType.AUTHORED,
            java.util.List.of("captain"),
            "salute captain",
            "salute",
            new TextCommandPayload.AuthoredActionInvocation(
                "wave-salute", java.util.List.of("captain")));

    TextCommandInterpretationResult result =
        handler.handle(
            new SessionContext(1L, 1L, 2L, "player@example.com", 3L, "Player", 4L, "room", "jwt"),
            command);

    assertFalse(result.commandResult().accepted());
    assertEquals("AUTHORED_ACTION_EXECUTION_UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(PlayerOutputKind.ERROR, result.outputs().getFirst().kind());
  }
}
