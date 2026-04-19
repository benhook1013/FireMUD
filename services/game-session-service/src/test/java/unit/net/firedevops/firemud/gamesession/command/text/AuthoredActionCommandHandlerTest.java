package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import org.junit.jupiter.api.Test;

class AuthoredActionCommandHandlerTest {

  @Test
  void authoredActionUsesConfiguredNoticeAndReturnsSuccess() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setActionId("wave-salute");
    action.setCommandId("wave-salute");
    action.setAliases(java.util.List.of("salute"));
    action.setNoticeText("You salute smartly.");
    properties.setActions(java.util.List.of(action));

    AuthoredActionCommandHandler handler =
        new AuthoredActionCommandHandler(new ConfiguredAuthoredActionCatalog(properties));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                "wave-salute",
                TextCommandType.AUTHORED,
                java.util.List.of("captain"),
                "salute captain",
                "salute",
                new TextCommandPayload.AuthoredActionInvocation(
                    "wave-salute", java.util.List.of("captain"))));

    assertTrue(result.commandResult().accepted());
    assertEquals(PlayerOutputKind.NOTICE, result.outputs().getFirst().kind());
    assertEquals("You salute smartly.", result.outputs().getFirst().text());
  }

  @Test
  void unknownAuthoredActionFailsClosed() {
    AuthoredActionCommandHandler handler =
        new AuthoredActionCommandHandler(
            new ConfiguredAuthoredActionCatalog(new AuthoredActionProperties()));

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
}
