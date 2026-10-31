package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles public world-browse commands before login and after login. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
public class WorldsCommandHandler {
  private final GameplayWorldCatalog worldCatalog;

  public WorldsCommandHandler(GameplayWorldCatalog worldCatalog) {
    this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
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
    if (!sessionContext.hasAccountIdentity()) {
      return CharacterBrowseResult.unavailable();
    }
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

    return CharacterBrowseResult.unavailable();
  }

  public sealed interface CharacterBrowseResult
      permits CharacterBrowseResult.InvalidWorld,
          CharacterBrowseResult.InvalidRealm,
          CharacterBrowseResult.RealmSelectionRequired,
          CharacterBrowseResult.Unavailable {
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

    record InvalidWorld() implements CharacterBrowseResult {}

    record InvalidRealm(String worldSlug) implements CharacterBrowseResult {}

    record RealmSelectionRequired(String worldSlug) implements CharacterBrowseResult {}

    record Unavailable() implements CharacterBrowseResult {}
  }
}
