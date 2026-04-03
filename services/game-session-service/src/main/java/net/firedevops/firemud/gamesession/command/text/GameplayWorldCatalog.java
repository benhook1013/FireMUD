package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import org.springframework.stereotype.Component;

/** Resolves the current public world list and selector forms used by WORLDS/PLAY. */
@Component
public final class GameplayWorldCatalog {
  private final GameSessionProperties properties;

  public GameplayWorldCatalog(GameSessionProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public String describeWorlds() {
    StringBuilder builder = new StringBuilder();
    for (WorldsViewOutput.WorldEntry world : worldEntries()) {
      builder
          .append(world.ordinal())
          .append(") ")
          .append(world.displayName())
          .append(" (")
          .append(world.slug())
          .append(")\n");
    }
    builder.append('\n');
    return builder.toString();
  }

  public WorldsViewOutput browseView() {
    return new WorldsViewOutput(worldEntries());
  }

  public Optional<GameSessionProperties.WorldOption> resolve(String selector) {
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
    return entries;
  }
}
