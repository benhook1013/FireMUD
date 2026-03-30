package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextCommandParserTest {
  private final TextCommandParser parser = new TextCommandParser();

  @Test
  void parsesLoginCaseInsensitive() {
    TextCommand command = parser.parse("LoGiN DemoUser swordfish");

    assertEquals(TextCommandType.LOGIN, command.type());
    assertEquals(List.of("DemoUser", "swordfish"), command.args());
    assertEquals("LoGiN DemoUser swordfish", command.rawLine());
  }

  @Test
  void parsesWorldsAsPublicBrowseCommand() {
    TextCommand command = parser.parse("WORLDS");

    assertEquals(TextCommandType.WORLDS, command.type());
    assertTrue(command.args().isEmpty());
    assertEquals("WORLDS", command.rawLine());
  }

  @Test
  void parsesPlayWithWorldAndOptionalCharacter() {
    TextCommand command = parser.parse("PLAY demo Emberline");

    assertEquals(TextCommandType.PLAY, command.type());
    assertEquals(List.of("demo", "Emberline"), command.args());
    assertEquals("PLAY demo Emberline", command.rawLine());
  }

  @Test
  void parsesLookWithWhitespace() {
    TextCommand command = parser.parse("   LOOK   ");

    assertEquals(TextCommandType.LOOK, command.type());
    assertTrue(command.args().isEmpty());
    assertEquals("   LOOK   ", command.rawLine());
  }

  @Test
  void parsesSayAndPreservesMessage() {
    TextCommand command = parser.parse("say   Hello there traveler");

    assertEquals(TextCommandType.SAY, command.type());
    assertEquals(List.of("Hello there traveler"), command.args());
    assertEquals("say   Hello there traveler", command.rawLine());
  }

  @Test
  void parsesWhisperTargetAndMessage() {
    TextCommand command = parser.parse("WHISPER Sora Hello");

    assertEquals(TextCommandType.WHISPER, command.type());
    assertEquals(List.of("Sora", "Hello"), command.args());
    assertEquals("WHISPER Sora Hello", command.rawLine());
  }

  @Test
  void parsesTellTargetAndMessage() {
    TextCommand command = parser.parse("TELL Sora Meet me later");

    assertEquals(TextCommandType.TELL, command.type());
    assertEquals(List.of("Sora", "Meet me later"), command.args());
    assertEquals("TELL Sora Meet me later", command.rawLine());
  }

  @Test
  void parsesDirectionalAliasAsMove() {
    TextCommand command = parser.parse("north");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals(List.of("north"), command.args());
    assertEquals("north", command.rawLine());
  }

  @Test
  void parsesMoveVerbWithDirection() {
    TextCommand command = parser.parse("MOVE east");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals(List.of("east"), command.args());
    assertEquals("MOVE east", command.rawLine());
  }

  @Test
  void parsesGoAliasWithDirection() {
    TextCommand command = parser.parse("go west");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals(List.of("west"), command.args());
    assertEquals("go west", command.rawLine());
  }

  @Test
  void blankInputIsIgnored() {
    TextCommand command = parser.parse("    ");

    assertEquals(TextCommandType.NOOP, command.type());
    assertTrue(command.args().isEmpty());
    assertEquals("    ", command.rawLine());
  }

  @Test
  void nullInputIsIgnored() {
    TextCommand command = parser.parse(null);

    assertEquals(TextCommandType.NOOP, command.type());
    assertTrue(command.args().isEmpty());
    assertEquals("", command.rawLine());
  }

  @Test
  void unrecognizedCommandFallsBackToUnknown() {
    TextCommand command = parser.parse("dance wildly now");

    assertEquals(TextCommandType.UNKNOWN, command.type());
    assertEquals(List.of("wildly", "now"), command.args());
    assertEquals("dance wildly now", command.rawLine());
  }
}
