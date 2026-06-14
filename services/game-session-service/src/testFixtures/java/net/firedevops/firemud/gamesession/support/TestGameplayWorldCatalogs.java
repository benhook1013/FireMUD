package net.firedevops.firemud.gamesession.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;

public final class TestGameplayWorldCatalogs {
  private TestGameplayWorldCatalogs() {}

  public static GameplayWorldCatalog fromProperties(GameplayCatalogProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    return GameplayWorldCatalog.forWorldSupplier(() -> toWorldViews(properties.getWorlds()));
  }

  private static List<GameplayWorldCatalog.WorldView> toWorldViews(
      List<GameplayCatalogProperties.World> worlds) {
    if (worlds == null) {
      return List.of();
    }
    return worlds.stream()
        .filter(Objects::nonNull)
        .filter(world -> world.getSlug() != null && !world.getSlug().isBlank())
        .map(TestGameplayWorldCatalogs::toWorldView)
        .toList();
  }

  private static GameplayWorldCatalog.WorldView toWorldView(GameplayCatalogProperties.World input) {
    ArrayList<GameplayWorldCatalog.RealmView> realms = new ArrayList<>();
    if (input.getRealms() != null) {
      for (GameplayCatalogProperties.Realm realm : input.getRealms()) {
        if (realm != null) {
          realms.add(toRealmView(realm));
        }
      }
    }
    return new GameplayWorldCatalog.WorldView(input.getSlug(), input.getDisplayName(), realms);
  }

  private static GameplayWorldCatalog.RealmView toRealmView(GameplayCatalogProperties.Realm input) {
    String stateScope =
        input.getStateScope() == null ? "UNSPECIFIED" : input.getStateScope().name();
    String characterCreationPolicy =
        input.getCharacterCreationPolicy() == null
            ? "UNSPECIFIED"
            : input.getCharacterCreationPolicy().name();
    return new GameplayWorldCatalog.RealmView(
        input.getSlug(),
        input.getDisplayName(),
        input.getTenantId(),
        input.getGameInstanceId(),
        input.getPointerVersion(),
        input.isVisible(),
        input.isPublicProductionRealm(),
        input.isRequiresCharacterSelection(),
        stateScope,
        characterCreationPolicy);
  }
}
