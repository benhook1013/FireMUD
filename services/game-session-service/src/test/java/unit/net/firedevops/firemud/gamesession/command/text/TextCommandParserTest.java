package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
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
  void parserResolvesAliasesThroughRegistryMetadata() {
    TextCommandParser parser =
        new TextCommandParser(
            new TextCommandRegistry() {
              @Override
              public Optional<TextCommandDefinition> findDefinition(TextCommandType type) {
                return Optional.empty();
              }

              @Override
              public Optional<TextCommandDefinition> findDefinitionByAlias(String alias) {
                if (!"peer".equalsIgnoreCase(alias)) {
                  return Optional.empty();
                }
                return Optional.of(
                    new TextCommandDefinition(
                        TextCommandType.WHO,
                        List.of("peer"),
                        TextCommandDispatchGroup.WHO,
                        TextCommandStageRequirement.GAMEPLAY,
                        TextCommandPromptPolicy.WHEN_GAMEPLAY,
                        TextCommandActionCategory.META,
                        TextCommandSource.EXTENSION));
              }
            });

    TextCommand command = parser.parse("peer");

    assertEquals(TextCommandType.WHO, command.type());
    assertEquals("peer", command.aliasUsed());
  }

  @Test
  void parserCarriesAuthoredCommandIdentityAndInvocationPayload() {
    TextCommandParser parser =
        new TextCommandParser(
            new TextCommandRegistry() {
              @Override
              public Optional<TextCommandDefinition> findDefinition(TextCommandType type) {
                return Optional.empty();
              }

              @Override
              public Optional<TextCommandDefinition> findDefinitionByAlias(String alias) {
                if (!"salute".equalsIgnoreCase(alias)) {
                  return Optional.empty();
                }
                return Optional.of(
                    new TextCommandDefinition(
                        "wave-salute",
                        TextCommandType.AUTHORED,
                        List.of("salute"),
                        TextCommandDispatchGroup.AUTHORED,
                        TextCommandStageRequirement.GAMEPLAY,
                        TextCommandPromptPolicy.WHEN_GAMEPLAY,
                        TextCommandActionCategory.GAMEPLAY,
                        List.of(),
                        TextCommandSource.GAME_AUTHORED));
              }
            });

    TextCommand command = parser.parse("salute captain");

    assertEquals(TextCommandType.AUTHORED, command.type());
    assertEquals("wave-salute", command.commandId());
    assertEquals("salute", command.aliasUsed());
    assertEquals(List.of("captain"), command.args());
    assertTrue(command.authoredActionPayload().isPresent());
    assertEquals("wave-salute", command.authoredActionPayload().orElseThrow().commandId());
    assertEquals(List.of("captain"), command.authoredActionPayload().orElseThrow().args());
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
  void parsesLogoutAliasesAsSimpleSessionCommand() {
    TextCommand command = parser.parse("quit");

    assertEquals(TextCommandType.LOGOUT, command.type());
    assertEquals("quit", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("quit", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.None);
  }

  @Test
  void parsesAfkDefaultAsOnRequest() {
    TextCommand command = parser.parse("AFK");

    assertEquals(TextCommandType.AFK, command.type());
    assertEquals("AFK", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertTrue(command.payload() instanceof TextCommandPayload.AfkRequest);
    assertEquals(Boolean.TRUE, ((TextCommandPayload.AfkRequest) command.payload()).enabled());
  }

  @Test
  void parsesAfkOffAsDisableRequest() {
    TextCommand command = parser.parse("afk off");

    assertEquals(TextCommandType.AFK, command.type());
    assertEquals(List.of("off"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.AfkRequest);
    assertEquals(Boolean.FALSE, ((TextCommandPayload.AfkRequest) command.payload()).enabled());
  }

  @Test
  void parsesPlayWithWorldAndOptionalRealmOrCharacter() {
    TextCommand command = parser.parse("PLAY demo Emberline");

    assertEquals(TextCommandType.PLAY, command.type());
    assertEquals("PLAY", command.aliasUsed());
    assertEquals(List.of("demo", "Emberline"), command.args());
    assertEquals("PLAY demo Emberline", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.PlayRequest);
    assertEquals("demo", command.playRequestPayload().orElseThrow().worldSelector());
    assertEquals("Emberline", command.playRequestPayload().orElseThrow().realmSelector());
    assertEquals(null, command.playRequestPayload().orElseThrow().characterSelector());
  }

  @Test
  void parsesPlayWithExplicitRealmAndCharacter() {
    TextCommand command = parser.parse("PLAY sandbox preview Emberline");

    assertEquals(TextCommandType.PLAY, command.type());
    assertEquals(List.of("sandbox", "preview", "Emberline"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.PlayRequest);
    assertEquals("sandbox", command.playRequestPayload().orElseThrow().worldSelector());
    assertEquals("preview", command.playRequestPayload().orElseThrow().realmSelector());
    assertEquals("Emberline", command.playRequestPayload().orElseThrow().characterSelector());
  }

  @Test
  void parsesRealmsWithWorldSelector() {
    TextCommand command = parser.parse("REALMS sandbox");

    assertEquals(TextCommandType.REALMS, command.type());
    assertEquals(List.of("sandbox"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.RealmBrowseRequest);
    assertEquals("sandbox", command.realmBrowsePayload().orElseThrow().worldSelector());
  }

  @Test
  void parsesCharsWithOptionalRealmSelector() {
    TextCommand command = parser.parse("CHARS sandbox preview");

    assertEquals(TextCommandType.CHARS, command.type());
    assertEquals(List.of("sandbox", "preview"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.CharacterBrowseRequest);
    assertEquals("sandbox", command.characterBrowsePayload().orElseThrow().worldSelector());
    assertEquals("preview", command.characterBrowsePayload().orElseThrow().realmSelector());
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
  void parsesWhoAsViewRequest() {
    TextCommand command = parser.parse("WHO");

    assertEquals(TextCommandType.WHO, command.type());
    assertEquals("WHO", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("WHO", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("WHO", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesFriendsAsViewRequest() {
    TextCommand command = parser.parse("FRIENDS");

    assertEquals(TextCommandType.FRIENDS, command.type());
    assertEquals("FRIENDS", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("FRIENDS", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("FRIENDS", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void preservesFriendsMutationArguments() {
    TextCommand command = parser.parse("FRIENDS ADD 77");

    assertEquals(TextCommandType.FRIENDS, command.type());
    assertEquals("FRIENDS", command.aliasUsed());
    assertEquals(List.of("ADD", "77"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertEquals("FRIENDS", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void preservesFriendsMutationCharacterNameArguments() {
    TextCommand command = parser.parse("FRIENDS REMOVE Sora");

    assertEquals(TextCommandType.FRIENDS, command.type());
    assertEquals("FRIENDS", command.aliasUsed());
    assertEquals(List.of("REMOVE", "Sora"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertEquals("FRIENDS", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void preservesFriendsFilterArguments() {
    TextCommand command = parser.parse("FRIENDS ONLINE");

    assertEquals(TextCommandType.FRIENDS, command.type());
    assertEquals("FRIENDS", command.aliasUsed());
    assertEquals(List.of("ONLINE"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertEquals("FRIENDS", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void preservesFriendsDetailArguments() {
    TextCommand command = parser.parse("FRIENDS SHOW #1");

    assertEquals(TextCommandType.FRIENDS, command.type());
    assertEquals("FRIENDS", command.aliasUsed());
    assertEquals(List.of("SHOW", "#1"), command.args());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertEquals("FRIENDS", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesInventoryAsViewRequest() {
    TextCommand command = parser.parse("inv");

    assertEquals(TextCommandType.INVENTORY, command.type());
    assertEquals("inv", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("inv", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("INVENTORY", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void preservesInventoryViewArguments() {
    TextCommand command = parser.parse("INV HERE");

    assertEquals(TextCommandType.INVENTORY, command.type());
    assertEquals("INV", command.aliasUsed());
    assertEquals(List.of("HERE"), command.args());
    assertEquals("INV HERE", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertEquals("INVENTORY", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesEquipmentAsViewRequest() {
    TextCommand command = parser.parse("eq");

    assertEquals(TextCommandType.EQUIPMENT, command.type());
    assertEquals("eq", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("eq", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ViewRequest);
    assertTrue(command.viewRequestPayload().isPresent());
    assertEquals("EQUIPMENT", command.viewRequestPayload().orElseThrow().viewName());
  }

  @Test
  void parsesContainerAsViewRequest() {
    TextCommand command = parser.parse("container old chest");

    assertEquals(TextCommandType.CONTAINER, command.type());
    assertEquals("container", command.aliasUsed());
    assertEquals(List.of("old", "chest"), command.args());
    assertEquals("container old chest", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ContainerView);
    assertTrue(command.containerViewPayload().isPresent());
    assertEquals("old chest", command.containerViewPayload().orElseThrow().containerReference());
  }

  @Test
  void parsesGetAsItemReference() {
    TextCommand command = parser.parse("GET rough iron key");

    assertEquals(TextCommandType.GET, command.type());
    assertEquals("GET", command.aliasUsed());
    assertEquals(List.of("rough", "iron", "key"), command.args());
    assertEquals("GET rough iron key", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ItemReference);
    assertEquals("rough iron key", command.itemReferencePayload().orElseThrow().reference());
    assertEquals(1, command.itemReferencePayload().orElseThrow().quantity());
  }

  @Test
  void parsesDropAsItemReference() {
    TextCommand command = parser.parse("DROP iron key");

    assertEquals(TextCommandType.DROP, command.type());
    assertEquals("DROP", command.aliasUsed());
    assertEquals(List.of("iron", "key"), command.args());
    assertEquals("DROP iron key", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ItemReference);
    assertEquals("iron key", command.itemReferencePayload().orElseThrow().reference());
    assertEquals(1, command.itemReferencePayload().orElseThrow().quantity());
  }

  @Test
  void parsesWearAsItemReference() {
    TextCommand command = parser.parse("WEAR iron key");

    assertEquals(TextCommandType.WEAR, command.type());
    assertEquals("WEAR", command.aliasUsed());
    assertEquals(List.of("iron", "key"), command.args());
    assertEquals("WEAR iron key", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ItemReference);
    assertEquals("iron key", command.itemReferencePayload().orElseThrow().reference());
    assertEquals(1, command.itemReferencePayload().orElseThrow().quantity());
  }

  @Test
  void parsesRemoveAsItemReference() {
    TextCommand command = parser.parse("REMOVE iron key");

    assertEquals(TextCommandType.REMOVE, command.type());
    assertEquals("REMOVE", command.aliasUsed());
    assertEquals(List.of("iron", "key"), command.args());
    assertEquals("REMOVE iron key", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ItemReference);
    assertEquals("iron key", command.itemReferencePayload().orElseThrow().reference());
    assertEquals(1, command.itemReferencePayload().orElseThrow().quantity());
  }

  @Test
  void parsesPutAsContainerTransfer() {
    TextCommand command = parser.parse("PUT 2 rough iron key INTO old chest");

    assertEquals(TextCommandType.PUT, command.type());
    assertEquals("PUT", command.aliasUsed());
    assertEquals(List.of("2", "rough", "iron", "key", "INTO", "old", "chest"), command.args());
    assertEquals("PUT 2 rough iron key INTO old chest", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ContainerTransfer);
    assertEquals(
        "rough iron key", command.containerTransferPayload().orElseThrow().itemReference());
    assertEquals(2, command.containerTransferPayload().orElseThrow().quantity());
    assertEquals(
        "old chest", command.containerTransferPayload().orElseThrow().containerReference());
  }

  @Test
  void parsesTakeAsContainerTransfer() {
    TextCommand command = parser.parse("TAKE torch FROM old chest");

    assertEquals(TextCommandType.TAKE, command.type());
    assertEquals("TAKE", command.aliasUsed());
    assertEquals(List.of("torch", "FROM", "old", "chest"), command.args());
    assertEquals("TAKE torch FROM old chest", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ContainerTransfer);
    assertEquals("torch", command.containerTransferPayload().orElseThrow().itemReference());
    assertEquals(1, command.containerTransferPayload().orElseThrow().quantity());
    assertEquals(
        "old chest", command.containerTransferPayload().orElseThrow().containerReference());
  }

  @Test
  void parsesGetWithQuantityAsItemReference() {
    TextCommand command = parser.parse("GET 2 rough iron key");

    assertEquals(TextCommandType.GET, command.type());
    assertEquals("GET", command.aliasUsed());
    assertEquals(List.of("2", "rough", "iron", "key"), command.args());
    assertEquals("GET 2 rough iron key", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ItemReference);
    assertEquals("rough iron key", command.itemReferencePayload().orElseThrow().reference());
    assertEquals(2, command.itemReferencePayload().orElseThrow().quantity());
  }

  @Test
  void parsesDropWithQuantityAsItemReference() {
    TextCommand command = parser.parse("DROP 3 iron key");

    assertEquals(TextCommandType.DROP, command.type());
    assertEquals("DROP", command.aliasUsed());
    assertEquals(List.of("3", "iron", "key"), command.args());
    assertEquals("DROP 3 iron key", command.rawLine());
    assertTrue(command.payload() instanceof TextCommandPayload.ItemReference);
    assertEquals("iron key", command.itemReferencePayload().orElseThrow().reference());
    assertEquals(3, command.itemReferencePayload().orElseThrow().quantity());
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
  void parsesLookSingleLetterAliasAsViewRequest() {
    TextCommand command = parser.parse("l");

    assertEquals(TextCommandType.LOOK, command.type());
    assertEquals("l", command.aliasUsed());
    assertTrue(command.args().isEmpty());
    assertEquals("l", command.rawLine());
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
