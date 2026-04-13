package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import org.springframework.stereotype.Component;

/** Resolves the canonical public world list and selector forms used by WORLDS/PLAY. */
@Component
public final class GameplayWorldCatalog {
  private final GameplayCatalogProperties properties;

  public GameplayWorldCatalog(GameplayCatalogProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public WorldsViewOutput browseView() {
    return new WorldsViewOutput(worldEntries());
  }

  public Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    return resolveWorld(worldSelector)
        .map(world -> new RealmBrowseViewOutput(world.getSlug(), realmEntries(world)));
  }

  public Optional<GameplayCatalogProperties.World> resolveWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    List<GameplayCatalogProperties.World> worlds = visibleWorlds();
    try {
      int index = Integer.parseInt(selector);
      if (index >= 1 && index <= worlds.size()) {
        return Optional.of(worlds.get(index - 1));
      }
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return worlds.stream()
        .filter(world -> normalized.equals(world.getSlug().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  public Optional<GameplayCatalogProperties.Realm> resolveRealm(
      GameplayCatalogProperties.World world, String selector) {
    if (world == null || selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return visibleRealms(world).stream()
        .filter(realm -> normalized.equals(realm.getSlug().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  public boolean hasVisibleRealm(GameplayCatalogProperties.World world, String selector) {
    return resolveRealm(world, selector).isPresent();
  }

  public Optional<GameplayCatalogProperties.Realm> resolveDefaultRealm(
      GameplayCatalogProperties.World world) {
    if (world == null) {
      return Optional.empty();
    }
    List<GameplayCatalogProperties.Realm> visibleRealms = visibleRealms(world);
    if (visibleRealms.isEmpty()) {
      return Optional.empty();
    }
    return visibleRealms.stream()
        .filter(realm -> "production".equalsIgnoreCase(realm.getSlug()))
        .findFirst()
        .or(() -> Optional.of(visibleRealms.get(0)));
  }

  public boolean requiresExplicitRealmSelection(GameplayCatalogProperties.World world) {
    return visibleRealms(world).size() > 1;
  }

  private List<GameplayCatalogProperties.Realm> visibleRealms(
      GameplayCatalogProperties.World world) {
    if (world == null || world.getRealms() == null) {
      return List.of();
    }
    return world.getRealms().stream()
        .filter(Objects::nonNull)
        .filter(GameplayCatalogProperties.Realm::isVisible)
        .toList();
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    List<GameplayCatalogProperties.World> worlds = visibleWorlds();
    java.util.ArrayList<WorldsViewOutput.WorldEntry> entries =
        new java.util.ArrayList<>(worlds.size());
    for (int i = 0; i < worlds.size(); i++) {
      GameplayCatalogProperties.World world = worlds.get(i);
      GameplayCatalogProperties.Realm defaultRealm = defaultRealm(world);
      entries.add(
          new WorldsViewOutput.WorldEntry(
              i + 1,
              world.getSlug(),
              world.getDisplayName(),
              defaultRealm.getGameInstanceId(),
              defaultRealm.isRequiresCharacterSelection()));
    }
    return List.copyOf(entries);
  }

  private List<RealmBrowseViewOutput.RealmEntry> realmEntries(
      GameplayCatalogProperties.World world) {
    List<GameplayCatalogProperties.Realm> realms = visibleRealms(world);
    java.util.ArrayList<RealmBrowseViewOutput.RealmEntry> entries =
        new java.util.ArrayList<>(realms.size());
    for (int i = 0; i < realms.size(); i++) {
      GameplayCatalogProperties.Realm realm = realms.get(i);
      entries.add(
          new RealmBrowseViewOutput.RealmEntry(
              i + 1,
              realm.getSlug(),
              realm.getDisplayName(),
              realm.getGameInstanceId(),
              realm.isRequiresCharacterSelection(),
              realm.getStateScope().name(),
              realm.getCharacterCreationPolicy().name()));
    }
    return List.copyOf(entries);
  }

  private GameplayCatalogProperties.Realm defaultRealm(GameplayCatalogProperties.World world) {
    return resolveDefaultRealm(world)
        .orElseGet(
            () -> {
              GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
              realm.setSlug("production");
              realm.setDisplayName("Live Realm");
              return realm;
            });
  }

  private List<GameplayCatalogProperties.World> visibleWorlds() {
    if (properties.getWorlds() == null) {
      return List.of();
    }
    return properties.getWorlds().stream()
        .filter(Objects::nonNull)
        .filter(world -> world.getSlug() != null && !world.getSlug().isBlank())
        .toList();
  }
}
