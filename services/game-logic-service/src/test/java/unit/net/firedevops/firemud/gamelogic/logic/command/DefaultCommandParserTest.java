package net.firedevops.firemud.gamelogic.logic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultCommandParserTest {
  private CommandParser parser;

  @BeforeEach
  void setUp() {
    parser = new DefaultCommandParser();
  }

  @Test
  void parsesAttackCommand() {
    Command cmd = parser.parse("attack goblin");
    assertEquals(ActionType.ATTACK, cmd.actionType());
    assertEquals("goblin", cmd.target());
  }

  @Test
  void returnsUnknownForEmpty() {
    Command cmd = parser.parse("   ");
    assertEquals(ActionType.UNKNOWN, cmd.actionType());
  }

  @Test
  void marksProceduralCommandsForSoloTick() {
    Command cmd = parser.parse("generate-dungeon 5");
    assertEquals(ActionType.PROCEDURAL, cmd.actionType());
    assertEquals(true, cmd.requiresSoloTick());
  }
}
