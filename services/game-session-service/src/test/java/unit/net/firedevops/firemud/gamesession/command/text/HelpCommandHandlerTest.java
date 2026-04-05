package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HelpCommandHandlerTest {
  private final HelpCommandHandler handler = new HelpCommandHandler();

  @Test
  void helpWithoutTopicShowsTopicIndex() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of(), "HELP"));

    assertTrue(result.commandResult().accepted());
    assertEquals(1, result.outputs().size());
    assertTrue(result.outputs().get(0).text().contains("HELP MOVEMENT"));
    assertTrue(result.outputs().get(0).text().contains("HELP SAY"));
  }

  @Test
  void helpMovementAliasResolvesToMovementTopic() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("walk"), "HELP walk"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("Shorthand aliases: N, S, E, W, U, D"));
    assertTrue(result.outputs().get(0).text().contains("GO <direction>"));
  }

  @Test
  void helpUnknownTopicReturnsStructuredError() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("banana"), "HELP banana"));

    assertFalse(result.commandResult().accepted());
    assertEquals("HELP_UNKNOWN_TOPIC", result.commandResult().errorCode());
    assertTrue(result.outputs().get(0).text().contains("Unknown help topic: banana"));
  }
}
