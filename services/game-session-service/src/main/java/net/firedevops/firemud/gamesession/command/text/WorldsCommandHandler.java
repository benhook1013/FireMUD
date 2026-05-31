package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles public world-browse commands before login and after login. */
@Component
public class WorldsCommandHandler {
  private final GameplayWorldCatalog worldCatalog;
  private final EntityManagementClient entityManagementClient;

  public WorldsCommandHandler(
      GameplayWorldCatalog worldCatalog, EntityManagementClient entityManagementClient) {
    this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
  }

  public WorldsViewOutput browseView() {
    return worldCatalog.browseView();
  }

  public java.util.Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    return worldCatalog.browseRealms(worldSelector);
  }

  public CharacterBrowseResult browseCharacters(
      SessionContext sessionContext, String worldSelector, String realmSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    java.util.Optional<GameplayWorldCatalog.WorldView> maybeWorld =
        worldCatalog.resolveWorld(worldSelector);
    if (maybeWorld.isEmpty()) {
      return CharacterBrowseResult.invalidWorld();
    }
    GameplayWorldCatalog.WorldView world = maybeWorld.orElseThrow();
    java.util.Optional<GameplayWorldCatalog.RealmView> maybeRealm =
        StringUtils.hasText(realmSelector)
            ? worldCatalog.resolveRealm(world, realmSelector)
            : worldCatalog.requiresExplicitRealmSelection(world)
                ? java.util.Optional.empty()
                : worldCatalog.resolveDefaultRealm(world);
    if (StringUtils.hasText(realmSelector) && maybeRealm.isEmpty()) {
      return CharacterBrowseResult.invalidRealm(world.slug());
    }
    if (!StringUtils.hasText(realmSelector) && maybeRealm.isEmpty()) {
      return CharacterBrowseResult.realmSelectionRequired(world.slug());
    }

    GameplayWorldCatalog.RealmView realm = maybeRealm.orElseThrow();
    ListCharactersByAccountResponse response =
        entityManagementClient.listCharactersByAccount(
            Long.toString(realm.tenantId()),
            Long.toString(sessionContext.accountId()),
            Long.toString(realm.gameInstanceId()),
            toPlayableStateScope(realm));
    if (response.hasError()) {
      return CharacterBrowseResult.unavailable();
    }
    java.util.List<CharacterBrowseViewOutput.CharacterEntry> entries =
        new java.util.ArrayList<>(response.getCharactersCount());
    for (int i = 0; i < response.getCharactersCount(); i++) {
      net.firedevops.firemud.entitymanagement.v1.Character character = response.getCharacters(i);
      entries.add(
          new CharacterBrowseViewOutput.CharacterEntry(
              i + 1, character.getId(), character.getName(), character.getLevel()));
    }
    return CharacterBrowseResult.success(
        new CharacterBrowseViewOutput(
            world.slug(),
            realm.slug(),
            realm.stateScope(),
            realm.characterCreationPolicy(),
            entries));
  }

  public sealed interface CharacterBrowseResult
      permits CharacterBrowseResult.Success,
          CharacterBrowseResult.InvalidWorld,
          CharacterBrowseResult.InvalidRealm,
          CharacterBrowseResult.RealmSelectionRequired,
          CharacterBrowseResult.Unavailable {
    static CharacterBrowseResult success(CharacterBrowseViewOutput output) {
      return new Success(output);
    }

    static CharacterBrowseResult invalidWorld() {
      return new InvalidWorld();
    }

    static CharacterBrowseResult invalidRealm(String worldSlug) {
      return new InvalidRealm(worldSlug);
    }

    static CharacterBrowseResult realmSelectionRequired(String worldSlug) {
      return new RealmSelectionRequired(worldSlug);
    }

    static CharacterBrowseResult unavailable() {
      return new Unavailable();
    }

    record Success(CharacterBrowseViewOutput output) implements CharacterBrowseResult {}

    record InvalidWorld() implements CharacterBrowseResult {}

    record InvalidRealm(String worldSlug) implements CharacterBrowseResult {}

    record RealmSelectionRequired(String worldSlug) implements CharacterBrowseResult {}

    record Unavailable() implements CharacterBrowseResult {}
  }

  private PlayableStateScope toPlayableStateScope(GameplayWorldCatalog.RealmView realm) {
    String scope =
        realm.stateScope() == null
            ? ""
            : realm.stateScope().trim().toUpperCase(java.util.Locale.ROOT);
    return switch (scope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }
}
