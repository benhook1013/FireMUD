package unit.net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
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
  void parsesAliasesAsSay() {
    TextCommand command = parser.parse("YELL Hello");

    assertEquals(TextCommandType.SAY, command.type());
    assertEquals(List.of("Hello"), command.args());
    assertEquals("YELL Hello", command.rawLine());
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
