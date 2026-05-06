package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsTextCommandDispatchHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final WorldsTextCommandDispatchHandler handler =
      new WorldsTextCommandDispatchHandler(
          new WorldsCommandHandler(
              new GameplayWorldCatalog(gameplayCatalogProperties), entityManagementClient),
          scriptEventPublisher);

  @Test
  void publishesCommandEventForGameplayScopedWorldsBrowse() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "room-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
                Optional.of(context)));

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
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("worlds-")));
  }

  @Test
  void publishesCommandEventForGameplayScopedRealmsBrowse() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "room-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.REALMS, List.of("sandbox"), "REALMS sandbox"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(RealmBrowseViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(gameplayCommand -> "REALMS".equals(gameplayCommand.getCommandName())));
  }

  @Test
  void publishesCommandEventForGameplayScopedCharsBrowse() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("7001")
                        .setName("Emberline")
                        .setLevel(12)
                        .build())
                .build());
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "room-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.CHARS, List.of("demo"), "CHARS demo"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(CharacterBrowseViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(gameplayCommand -> "CHARS".equals(gameplayCommand.getCommandName())));
  }

  @Test
  void skipsCommandEventWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
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
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
  }
}
