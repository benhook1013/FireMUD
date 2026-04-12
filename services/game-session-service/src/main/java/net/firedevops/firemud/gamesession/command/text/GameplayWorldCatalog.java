package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import org.springframework.stereotype.Component;

/** Resolves the current public world list and selector forms used by WORLDS/PLAY. */
@Component
public final class GameplayWorldCatalog {
  private final GameSessionProperties properties;

  public GameplayWorldCatalog(GameSessionProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public WorldsViewOutput browseView() {
    return new WorldsViewOutput(worldEntries());
  }

  public Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    return resolveWorld(worldSelector)
        .map(world -> new RealmBrowseViewOutput(world.getSlug(), realmEntries(world)));
  }

  public Optional<GameSessionProperties.WorldOption> resolveWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    List<GameSessionProperties.WorldOption> worlds = properties.getWorlds();
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

  public Optional<GameSessionProperties.RealmOption> resolveRealm(
      GameSessionProperties.WorldOption world, String selector) {
    if (world == null || selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return visibleRealms(world).stream()
        .filter(realm -> normalized.equals(realm.getSlug().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  public boolean hasVisibleRealm(GameSessionProperties.WorldOption world, String selector) {
    return resolveRealm(world, selector).isPresent();
  }

  public Optional<GameSessionProperties.RealmOption> resolveDefaultRealm(
      GameSessionProperties.WorldOption world) {
    if (world == null) {
      return Optional.empty();
    }
    List<GameSessionProperties.RealmOption> visibleRealms = visibleRealms(world);
    if (visibleRealms.isEmpty()) {
      return Optional.empty();
    }
    return visibleRealms.stream()
        .filter(realm -> "production".equalsIgnoreCase(realm.getSlug()))
        .findFirst()
        .or(() -> Optional.of(visibleRealms.get(0)));
  }

  public boolean requiresExplicitRealmSelection(GameSessionProperties.WorldOption world) {
    return visibleRealms(world).size() > 1;
  }

  private List<GameSessionProperties.RealmOption> visibleRealms(
      GameSessionProperties.WorldOption world) {
    if (world == null || world.getRealms() == null) {
      return List.of();
    }
    return world.getRealms().stream()
        .filter(Objects::nonNull)
        .filter(GameSessionProperties.RealmOption::isVisible)
        .toList();
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    List<GameSessionProperties.WorldOption> worlds = properties.getWorlds();
    java.util.ArrayList<WorldsViewOutput.WorldEntry> entries =
        new java.util.ArrayList<>(worlds.size());
    for (int i = 0; i < worlds.size(); i++) {
      GameSessionProperties.WorldOption world = worlds.get(i);
      entries.add(
          new WorldsViewOutput.WorldEntry(
              i + 1,
              world.getSlug(),
              world.getDisplayName(),
              world.getGameInstanceId(),
              world.isRequiresCharacterSelection()));
    }
    return List.copyOf(entries);
  }

  private List<RealmBrowseViewOutput.RealmEntry> realmEntries(
      GameSessionProperties.WorldOption world) {
    List<GameSessionProperties.RealmOption> realms = visibleRealms(world);
    java.util.ArrayList<RealmBrowseViewOutput.RealmEntry> entries =
        new java.util.ArrayList<>(realms.size());
    for (int i = 0; i < realms.size(); i++) {
      GameSessionProperties.RealmOption realm = realms.get(i);
      entries.add(
          new RealmBrowseViewOutput.RealmEntry(
              i + 1,
              realm.getSlug(),
              realm.getDisplayName(),
              realm.getGameInstanceId(),
              realm.isRequiresCharacterSelection()));
    }
    return List.copyOf(entries);
  }
}
