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
    assertTrue(result.outputs().get(0).text().contains("HELP INVENTORY"));
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
  void helpInventoryTopicExplainsTheNewLoop() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.HELP, List.of("inventory"), "HELP inventory"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("INVENTORY shows what you are carrying."));
    assertTrue(
        result.outputs().get(0).text().contains("If nothing is listed, you are empty-handed."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains("GET <item> picks up a matching room-ground item."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains("DROP <item> places a carried item on the room ground."));
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
