package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.gamesession.test.GameplayCommandTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class WorldsTextCommandDispatchHandlerTest {
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final WorldsTextCommandDispatchHandler handler =
      new WorldsTextCommandDispatchHandler(
          new WorldsCommandHandler(
              TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties)),
          scriptEventPublisher);

  @Test
  void preparedJoinExplicitlyRefusesWithoutDiscoveryOrGameplayEffects() {
    var command = new TextCommandParser().parse("JOIN 1");
    assertThat(command.type()).isEqualTo(TextCommandType.JOIN);
    var result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                command,
                false,
                Optional.of(GameplayCommandTestFixtures.authoredDispatchEmberline()),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FEATURE_UNAVAILABLE");
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output ->
                assertThat(output.text())
                    .isEqualTo("ERROR FEATURE_UNAVAILABLE JOIN is not available."));
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @Test
  void nonmatchingNumericWorldSelectorReturnsRediscoveryGuidanceWithoutPublishingAnEvent() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    var result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommandParser().parse("REALMS 1"),
                false,
                Optional.of(GameplayCommandTestFixtures.authoredDispatchEmberline()),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output ->
                assertThat(output.text())
                    .isEqualTo(
                        "ERROR INVALID_ARGUMENT REALMS requires a valid world selector. Use WORLDS first."));
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @Test
  void charsReturnsTheSameWireRefusalForUndiscoverableAndUnknownTargets() {
    var demo = world("demo", 22L, 41L, false);
    var preview = world("preview", 22L, 42L, false).getRealms().getFirst();
    preview.setSlug("preview");
    preview.setPublicProductionRealm(false);
    demo.setRealms(List.of(demo.getRealms().getFirst(), preview));
    var privateOnly = world("private-only", 22L, 43L, false);
    privateOnly.getRealms().getFirst().setPublicProductionRealm(false);
    gameplayCatalogProperties.setWorlds(List.of(demo, privateOnly));
    var context = GameplayCommandTestFixtures.authoredDispatchEmberline();
    var parser = new TextCommandParser();

    var unknownWorld =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                parser.parse("CHARS unknown"),
                false,
                Optional.of(context),
                Optional.empty()));
    var privateWorld =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                parser.parse("CHARS private-only"),
                false,
                Optional.of(context),
                Optional.empty()));
    var unknownRealm =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                parser.parse("CHARS demo unknown"),
                false,
                Optional.of(context),
                Optional.empty()));
    var privateRealm =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                parser.parse("CHARS demo preview"),
                false,
                Optional.of(context),
                Optional.empty()));

    assertThat(unknownWorld.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(unknownRealm.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(privateWorld.outputs()).isEqualTo(unknownWorld.outputs());
    assertThat(privateRealm.outputs()).isEqualTo(unknownRealm.outputs());
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void discoveryPreservesThePointerAuthorityFailureClass(boolean unreachable) {
    var authority = Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    if (unreachable) {
      Mockito.when(authority.listPointers())
          .thenThrow(new DataAccessResourceFailureException("db"));
    } else {
      Mockito.when(authority.listPointers()).thenReturn(null);
    }
    var localHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(new GameplayWorldCatalog(authority)), scriptEventPublisher);
    var actor = GameplayCommandTestFixtures.authoredDispatchEmberline();
    var parser = new TextCommandParser();
    String expected = unreachable ? "AUTH_UNAVAILABLE" : "ADMISSION_POINTER_UNAVAILABLE";

    for (String command : List.of("WORLDS", "REALMS demo", "CHARS demo")) {
      var result =
          localHandler.handle(
              new TextCommandDispatchRequest(
                  "session-1", parser.parse(command), false, Optional.of(actor), Optional.empty()));
      assertThat(result.commandResult().accepted()).isFalse();
      assertThat(result.commandResult().errorCode()).isEqualTo(expected);
      assertThat(result.outputs())
          .singleElement()
          .satisfies(output -> assertThat(output.text()).contains(expected));
    }
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @Test
  void dispatchAcceptsExactNumericWorldSlugWithoutTreatingItAsAListPosition() {
    gameplayCatalogProperties.setWorlds(
        List.of(world("other", 23L, 40L, false), world("1", 22L, 41L, false)));
    SessionContext context = GameplayCommandTestFixtures.authoredDispatchEmberline();

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommandParser().parse("REALMS 1"),
                false,
                Optional.of(context),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.payload()).isInstanceOf(RealmBrowseViewOutput.class);
              assertThat(((RealmBrowseViewOutput) output.payload()).worldSlug()).isEqualTo("1");
            });
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "REALMS".equals(gameplayCommand.getCommandName())
                        && "REALMS 1".equals(gameplayCommand.getCommandText())));
  }

  @Test
  void publishesCommandEventForGameplayScopedWorldsBrowse() {
    SessionContext context = GameplayCommandTestFixtures.authoredDispatchEmberline();

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
                Optional.of(context),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(WorldsViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "WORLDS".equals(gameplayCommand.getCommandName())
                        && "WORLDS".equals(gameplayCommand.getCommandText())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("worlds-")));
  }

  @Test
  void publishesCommandEventForGameplayScopedRealmsBrowse() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    SessionContext context = GameplayCommandTestFixtures.authoredDispatchEmberline();

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.REALMS, List.of("demo"), "REALMS demo"),
                false,
                Optional.of(context),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(RealmBrowseViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "REALMS".equals(gameplayCommand.getCommandName())
                        && "REALMS demo".equals(gameplayCommand.getCommandText())));
  }

  @Test
  void refusesCharacterBrowseWithoutPublishingCommandEvent() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    SessionContext context = GameplayCommandTestFixtures.helpCapabilityEmberline(7L);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.CHARS, List.of("demo"), "CHARS demo"),
                false,
                Optional.of(context),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CHARACTER_LIST_UNAVAILABLE");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(
            "Character browsing is unavailable. Use PLAY <world> [realm] <character> "
                + "with a known character name.");
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @Test
  void skipsCommandEventWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
                Optional.empty(),
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  private static GameplayCatalogProperties.World world(
      String slug, long tenantId, long gameInstanceId, boolean requiresCharacterSelection) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(slug);
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug("production");
    realm.setDisplayName("Live Realm");
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(true);
    realm.setPublicProductionRealm(true);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
  }
}
