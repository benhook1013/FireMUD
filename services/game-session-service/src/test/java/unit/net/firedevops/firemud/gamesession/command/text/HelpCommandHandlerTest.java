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
    assertTrue(result.outputs().get(0).text().contains("HELP EQUIPMENT"));
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
            .contains(
                "GET <item> picks up a matching room-ground item and refreshes your inventory."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains("GET <count> <item> picks up that many matching room-ground items."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains(
                "DROP <item> places a carried item on the room ground and refreshes your inventory."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains("DROP <count> <item> drops that many carried items."));
  }

  @Test
  void helpEquipmentTopicExplainsTheReservedCommands() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.HELP, List.of("equipment"), "HELP equipment"));

    assertTrue(result.commandResult().accepted());
    assertTrue(
        result.outputs().get(0).text().contains("EQUIPMENT shows what you are currently wearing."));
    assertTrue(result.outputs().get(0).text().contains("EQ is a short alias for EQUIPMENT."));
    assertTrue(result.outputs().get(0).text().contains("WEAR <item> equips a carried item."));
    assertTrue(
        result.outputs().get(0).text().contains("REMOVE <item|slot> takes an equipped item off."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains("The command surface is now backed by the equipment service."));
  }

  @Test
  void helpContainerTopicsExplainTheFirstContainerLoop() {
    TextCommandInterpretationResult containerResult =
        handler.handle(
            new TextCommand(TextCommandType.HELP, List.of("container"), "HELP container"));
    TextCommandInterpretationResult putResult =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("put"), "HELP put"));
    TextCommandInterpretationResult takeResult =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("take"), "HELP take"));

    assertTrue(containerResult.commandResult().accepted());
    assertTrue(containerResult.outputs().get(0).text().contains("CONTAINER <container>"));
    assertTrue(
        containerResult
            .outputs()
            .get(0)
            .text()
            .contains("Inspect a carried container's contents."));

    assertTrue(putResult.commandResult().accepted());
    assertTrue(putResult.outputs().get(0).text().contains("PUT <item> INTO <container>"));
    assertTrue(putResult.outputs().get(0).text().contains("PUT <count> <item> INTO <container>"));

    assertTrue(takeResult.commandResult().accepted());
    assertTrue(takeResult.outputs().get(0).text().contains("TAKE <item> FROM <container>"));
    assertTrue(takeResult.outputs().get(0).text().contains("TAKE <count> <item> FROM <container>"));
  }

  @Test
  void helpGetTopicExplainsPickupBehavior() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("get"), "HELP get"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("GET <item>"));
    assertTrue(result.outputs().get(0).text().contains("Pick up a matching room-ground item"));
    assertTrue(result.outputs().get(0).text().contains("GET <count> <item>"));
  }

  @Test
  void helpDropTopicExplainsDropBehavior() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("drop"), "HELP drop"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("DROP <item>"));
    assertTrue(result.outputs().get(0).text().contains("Place a carried item on the room ground"));
    assertTrue(result.outputs().get(0).text().contains("DROP <count> <item>"));
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
