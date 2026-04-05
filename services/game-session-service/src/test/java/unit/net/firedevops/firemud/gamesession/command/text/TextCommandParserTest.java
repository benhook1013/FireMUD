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
    assertEquals("LoGiN", command.aliasUsed());
    assertEquals(List.of("DemoUser", "swordfish"), command.args());
    assertEquals("LoGiN DemoUser swordfish", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Credentials);
    TextCommandPayload.Credentials payload = (TextCommandPayload.Credentials) command.payload();
    assertEquals("DemoUser", payload.loginName());
    assertEquals("swordfish", payload.password());
  }

  @Test
  void parsesWorldsAsPublicBrowseCommand() {
    TextCommand command = parser.parse("WORLDS");

    assertEquals(TextCommandType.WORLDS, command.type());
    assertEquals("WORLDS", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("WORLDS", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("WORLDS", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesPlayWithWorldAndOptionalCharacter() {
    TextCommand command = parser.parse("PLAY demo Emberline");

    assertEquals(TextCommandType.PLAY, command.type());
    assertEquals("PLAY", command.aliasUsed());
    assertEquals(List.of("demo", "Emberline"), command.args());
    assertEquals("PLAY demo Emberline", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Selection);
  }

  @Test
  void parsesHelpWithoutTopic() {
    TextCommand command = parser.parse("HELP");

    assertEquals(TextCommandType.HELP, command.type());
    assertEquals("HELP", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("HELP", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.None);
  }

  @Test
  void parsesHelpTopicLookup() {
    TextCommand command = parser.parse("HELP walk");

    assertEquals(TextCommandType.HELP, command.type());
    assertEquals("HELP", command.aliasUsed());
    assertEquals(List.of("walk"), command.args());
    assertEquals("HELP walk", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Tokens);
  }

  @Test
  void parsesLookWithWhitespace() {
    TextCommand command = parser.parse("   LOOK   ");

    assertEquals(TextCommandType.LOOK, command.type());
    assertEquals("LOOK", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("   LOOK   ", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("LOOK", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesQuickLookAliasAsViewRequest() {
    TextCommand command = parser.parse("qlook");

    assertEquals(TextCommandType.QUICKLOOK, command.type());
    assertEquals("qlook", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("qlook", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("QUICKLOOK", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesSayAndPreservesMessage() {
    TextCommand command = parser.parse("say   Hello there traveler");

    assertEquals(TextCommandType.SAY, command.type());
    assertEquals("say", command.aliasUsed());
    assertEquals(List.of("Hello there traveler"), command.args());
    assertEquals("say   Hello there traveler", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Message);
  }

  @Test
  void parsesWhisperTargetAndMessage() {
    TextCommand command = parser.parse("WHISPER Sora Hello");

    assertEquals(TextCommandType.WHISPER, command.type());
    assertEquals("WHISPER", command.aliasUsed());
    assertEquals(List.of("Sora", "Hello"), command.args());
    assertEquals("WHISPER Sora Hello", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.TargetedMessage);
  }

  @Test
  void parsesTellTargetAndMessage() {
    TextCommand command = parser.parse("TELL Sora Meet me later");

    assertEquals(TextCommandType.TELL, command.type());
    assertEquals("TELL", command.aliasUsed());
    assertEquals(List.of("Sora", "Meet me later"), command.args());
    assertEquals("TELL Sora Meet me later", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.TargetedMessage);
  }

  @Test
  void parsesDirectionalAliasAsMove() {
    TextCommand command = parser.parse("north");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals("north", command.aliasUsed());
    assertEquals(List.of("north"), command.args());
    assertEquals("north", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Directional);
  }

  @Test
  void parsesSingleLetterDirectionalAliasAsMove() {
    TextCommand command = parser.parse("s");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals("s", command.aliasUsed());
    assertEquals(List.of("south"), command.args());
    assertEquals("s", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Directional);
    assertEquals("south", command.directionalPayload().orElseThrow().direction());
  }

  @Test
  void parsesMoveVerbWithDirection() {
    TextCommand command = parser.parse("MOVE east");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals("MOVE", command.aliasUsed());
    assertEquals(List.of("east"), command.args());
    assertEquals("MOVE east", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Directional);
  }

  @Test
  void parsesGoAliasWithDirection() {
    TextCommand command = parser.parse("go west");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals("go", command.aliasUsed());
    assertEquals(List.of("west"), command.args());
    assertEquals("go west", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Directional);
  }

  @Test
  void parsesGoAliasWithSingleLetterDirection() {
    TextCommand command = parser.parse("go n");

    assertEquals(TextCommandType.MOVE, command.type());
    assertEquals("go", command.aliasUsed());
    assertEquals(List.of("north"), command.args());
    assertEquals("go n", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Directional);
    assertEquals("north", command.directionalPayload().orElseThrow().direction());
  }

  @Test
  void blankInputIsIgnored() {
    TextCommand command = parser.parse("    ");

    assertEquals(TextCommandType.NOOP, command.type());
    assertEquals("", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("    ", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.None);
  }

  @Test
  void nullInputIsIgnored() {
    TextCommand command = parser.parse(null);

    assertEquals(TextCommandType.NOOP, command.type());
    assertEquals("", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.None);
  }

  @Test
  void unrecognizedCommandFallsBackToUnknown() {
    TextCommand command = parser.parse("dance wildly now");

    assertEquals(TextCommandType.UNKNOWN, command.type());
    assertEquals("dance", command.aliasUsed());
    assertEquals(List.of("wildly", "now"), command.args());
    assertEquals("dance wildly now", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.Tokens);
  }
}
