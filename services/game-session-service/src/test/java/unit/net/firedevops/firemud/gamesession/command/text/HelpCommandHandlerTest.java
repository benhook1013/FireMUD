package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.service.GameAuthoredHelpReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
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
    assertTrue(result.outputs().get(0).text().contains("HELP WHO"));
    assertTrue(result.outputs().get(0).text().contains("HELP INVENTORY"));
    assertTrue(result.outputs().get(0).text().contains("HELP EQUIPMENT"));
  }

  @Test
  void helpWhoTopicExplainsGameplayOnlyScope() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("who"), "HELP who"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("WHO"));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains("List currently connected players in this game instance."));
    assertTrue(result.outputs().get(0).text().contains("Gods appear first, then players."));
    assertTrue(result.outputs().get(0).text().contains("You must already be in-world with PLAY."));
  }

  @Test
  void helpFriendsTopicExplainsVisibilityReadAndWriteSurface() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("friends"), "HELP friends"));

    assertTrue(result.commandResult().accepted());
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains(
                "FRIENDS VISIBILITY shows your current cross-game friend-presence policy, and FRIENDS VISIBILITY <PUBLIC|FRIENDS_ONLY|PRIVATE> updates it."));
    assertTrue(result.outputs().get(0).text().contains("FRIENDS UNSPECIFIED_VISIBILITY"));
    assertTrue(result.outputs().get(0).text().contains("FRIENDS UNSPECIFIED_SCOPE"));
    assertTrue(result.outputs().get(0).text().contains("#entryNumber removal"));
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
    assertTrue(result.outputs().get(0).text().contains("INV HERE lists room-ground items"));
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
            .contains("LOOK may show wearable:<slot> tags for items that can be worn."));
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
            .contains("Inspect a carried or nearby room-ground container's contents."));
    assertTrue(
        containerResult
            .outputs()
            .get(0)
            .text()
            .contains("LOOK may show which items are containers."));

    assertTrue(putResult.commandResult().accepted());
    assertTrue(putResult.outputs().get(0).text().contains("PUT <item> INTO <container>"));
    assertTrue(putResult.outputs().get(0).text().contains("PUT <count> <item> INTO <container>"));

    assertTrue(takeResult.commandResult().accepted());
    assertTrue(takeResult.outputs().get(0).text().contains("TAKE <item> FROM <container>"));
    assertTrue(takeResult.outputs().get(0).text().contains("TAKE <count> <item> FROM <container>"));
  }

  @Test
  void helpLookTopicExplainsActionableAffordanceTags() {
    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.HELP, List.of("look"), "HELP look"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("LOOK refreshes the current room."));
    assertTrue(
        result
            .outputs()
            .get(0)
            .text()
            .contains(
                "When available, LOOK shows lightweight item affordances like container and wearable tags."));
    assertTrue(result.outputs().get(0).text().contains("QUICKLOOK is the shorter room refresh."));
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

  @Test
  void helpIndexIncludesConfiguredAuthoredTopics() {
    HelpCommandHandler authoredHelpHandler = new HelpCommandHandler(authoredCatalog());

    TextCommandInterpretationResult result =
        authoredHelpHandler.handle(new TextCommand(TextCommandType.HELP, List.of(), "HELP"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("Authored topics:"));
    assertTrue(result.outputs().get(0).text().contains("HELP SALUTE"));
  }

  @Test
  void helpIndexHidesHistoryWhenFeatureDisabled() {
    HelpCommandHandler disabledHandler =
        new HelpCommandHandler(
            new ConfiguredAuthoredActionCatalog(new AuthoredActionProperties()),
            (context, topic) -> Optional.empty(),
            null,
            historyResolver(false));
    SessionContext context =
        new SessionContext(
            42L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        disabledHandler.handle(
            new TextCommand(TextCommandType.HELP, List.of(), "HELP"), Optional.of(context));

    assertTrue(result.commandResult().accepted());
    assertFalse(result.outputs().get(0).text().contains("- HELP HISTORY"));
  }

  @Test
  void helpHistoryTopicUnavailableWhenFeatureDisabled() {
    HelpCommandHandler disabledHandler =
        new HelpCommandHandler(
            new ConfiguredAuthoredActionCatalog(new AuthoredActionProperties()),
            (context, topic) -> Optional.empty(),
            null,
            historyResolver(false));
    SessionContext context =
        new SessionContext(
            42L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        disabledHandler.handle(
            new TextCommand(TextCommandType.HELP, List.of("history"), "HELP history"),
            Optional.of(context));

    assertFalse(result.commandResult().accepted());
    assertEquals("HELP_UNKNOWN_TOPIC", result.commandResult().errorCode());
  }

  @Test
  void helpHistoryTopicAvailableWhenFeatureEnabled() {
    HelpCommandHandler enabledHandler =
        new HelpCommandHandler(
            new ConfiguredAuthoredActionCatalog(new AuthoredActionProperties()),
            (context, topic) -> Optional.empty(),
            null,
            historyResolver(true));
    SessionContext context =
        new SessionContext(
            42L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        enabledHandler.handle(
            new TextCommand(TextCommandType.HELP, List.of("history"), "HELP history"),
            Optional.of(context));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("HISTORY [count]"));
  }

  @Test
  void helpTopicResolvesConfiguredAuthoredAction() {
    HelpCommandHandler authoredHelpHandler = new HelpCommandHandler(authoredCatalog());

    TextCommandInterpretationResult result =
        authoredHelpHandler.handle(
            new TextCommand(TextCommandType.HELP, List.of("salute"), "HELP salute"));

    assertTrue(result.commandResult().accepted());
    assertTrue(result.outputs().get(0).text().contains("SALUTE"));
    assertTrue(result.outputs().get(0).text().contains("Offer a formal greeting."));
    assertTrue(
        result.outputs().get(0).text().contains("Use this to greet nearby nobles or officers."));
    assertTrue(result.outputs().get(0).text().contains("Aliases: HAIL"));
  }

  @Test
  void publishedGameAuthoredTopicOverridesPlatformTopicForAdmittedSession() {
    GameAuthoredHelpReader reader =
        (context, topic) ->
            "look".equals(topic)
                ? Optional.of(
                    new GameAuthoredHelpReader.ResolvedTopic(
                        "Temple Etiquette", "Bow before entering the moonlit sanctuary."))
                : Optional.empty();
    HelpCommandHandler authoredHelpHandler = new HelpCommandHandler(authoredCatalog(), reader);
    SessionContext context =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        authoredHelpHandler.handle(
            new TextCommand(TextCommandType.HELP, List.of("look"), "HELP look"),
            Optional.of(context));

    assertTrue(result.commandResult().accepted());
    assertEquals(
        "Temple Etiquette\nBow before entering the moonlit sanctuary.",
        result.outputs().get(0).text());
  }

  private ConfiguredAuthoredActionCatalog authoredCatalog() {
    net.firedevops.firemud.gamesession.config.AuthoredActionProperties properties =
        new net.firedevops.firemud.gamesession.config.AuthoredActionProperties();
    net.firedevops.firemud.gamesession.config.AuthoredActionProperties.Action action =
        new net.firedevops.firemud.gamesession.config.AuthoredActionProperties.Action();
    action.setActionId("wave-salute");
    action.setCommandId("wave-salute");
    action.setAliases(List.of("salute", "hail"));
    action.setHelpSummary("Offer a formal greeting.");
    action.setHelpDetails("Use this to greet nearby nobles or officers.");
    properties.setActions(List.of(action));
    return new ConfiguredAuthoredActionCatalog(properties);
  }

  private static EffectiveCommandHistorySettingsResolver historyResolver(boolean enabled) {
    return new EffectiveCommandHistorySettingsResolver(
        new FiremudCommandHistoryProperties(enabled, 10),
        (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty());
  }
}
