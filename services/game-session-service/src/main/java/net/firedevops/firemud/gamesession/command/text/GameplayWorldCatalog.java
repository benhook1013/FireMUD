package net.firedevops.firemud.gamesession.command.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves the canonical public world list and selector forms used by WORLDS/PLAY. */
@Component
public final class GameplayWorldCatalog {
  private final Supplier<List<GameplayCatalogProperties.World>> worldSupplier;

  @Autowired
  public GameplayWorldCatalog(GameplayAdmissionPointerAuthorityService authorityService) {
    Objects.requireNonNull(authorityService, "authorityService must not be null");
    this.worldSupplier = () -> toWorlds(authorityService.listPointers());
  }

  public GameplayWorldCatalog(GameplayCatalogProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    this.worldSupplier = () -> normalizeWorlds(properties.getWorlds());
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
        .filter(GameplayCatalogProperties.Realm::isPublicProductionRealm)
        .findFirst()
        .or(() -> Optional.of(visibleRealms.get(0)));
  }

  public boolean requiresExplicitRealmSelection(GameplayCatalogProperties.World world) {
    return visibleRealms(world).size() > 1;
  }

  public Optional<GameplayCatalogProperties.Realm> resolveRealmByRuntimeTarget(
      long tenantId, long gameInstanceId) {
    return visibleWorlds().stream()
        .flatMap(world -> visibleRealms(world).stream())
        .filter(realm -> realm.getTenantId() == tenantId)
        .filter(realm -> realm.getGameInstanceId() == gameInstanceId)
        .findFirst();
  }

  public Optional<RuntimeRealmTarget> resolveRuntimeTarget(long tenantId, long gameInstanceId) {
    return visibleWorlds().stream()
        .flatMap(
            world ->
                visibleRealms(world).stream()
                    .filter(realm -> realm.getTenantId() == tenantId)
                    .filter(realm -> realm.getGameInstanceId() == gameInstanceId)
                    .map(
                        realm ->
                            new RuntimeRealmTarget(
                                world.getSlug(),
                                world.getDisplayName(),
                                realm.getSlug(),
                                realm.getDisplayName())))
        .findFirst();
  }

  public List<GameplayCatalogProperties.Realm> visibleRealms(
      GameplayCatalogProperties.World world) {
    if (world == null || world.getRealms() == null) {
      return List.of();
    }
    return world.getRealms().stream()
        .filter(Objects::nonNull)
        .filter(GameplayCatalogProperties.Realm::isVisible)
        .toList();
  }

  public List<GameplayCatalogProperties.World> visibleWorlds() {
    return normalizeWorlds(worldSupplier.get());
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    List<GameplayCatalogProperties.World> worlds = visibleWorlds();
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(worlds.size());
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
    ArrayList<RealmBrowseViewOutput.RealmEntry> entries = new ArrayList<>(realms.size());
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

  private static List<GameplayCatalogProperties.World> normalizeWorlds(
      List<GameplayCatalogProperties.World> worlds) {
    if (worlds == null) {
      return List.of();
    }
    return worlds.stream()
        .filter(Objects::nonNull)
        .filter(world -> world.getSlug() != null && !world.getSlug().isBlank())
        .map(GameplayWorldCatalog::copyWorld)
        .toList();
  }

  private static List<GameplayCatalogProperties.World> toWorlds(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Map<String, GameplayCatalogProperties.World> worlds = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      GameplayCatalogProperties.World world =
          worlds.computeIfAbsent(
              pointer.worldSlug(),
              ignored -> {
                GameplayCatalogProperties.World entry = new GameplayCatalogProperties.World();
                entry.setSlug(pointer.worldSlug());
                entry.setDisplayName(pointer.worldDisplayName());
                entry.setRealms(new ArrayList<>());
                return entry;
              });
      GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
      realm.setSlug(pointer.realmSlug());
      realm.setDisplayName(pointer.realmDisplayName());
      realm.setTenantId(pointer.tenantId());
      realm.setGameInstanceId(pointer.gameInstanceId());
      realm.setPointerVersion(pointer.pointerVersion());
      realm.setVisible(pointer.visible());
      realm.setPublicProductionRealm(pointer.publicProductionRealm());
      realm.setRequiresCharacterSelection(pointer.requiresCharacterSelection());
      realm.setStateScope(GameplayCatalogProperties.RealmStateScope.valueOf(pointer.stateScope()));
      realm.setCharacterCreationPolicy(
          GameplayCatalogProperties.CharacterCreationPolicy.valueOf(
              pointer.characterCreationPolicy()));
      world.getRealms().add(realm);
    }
    return normalizeWorlds(new ArrayList<>(worlds.values()));
  }

  private static GameplayCatalogProperties.World copyWorld(GameplayCatalogProperties.World input) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(input.getSlug());
    world.setDisplayName(input.getDisplayName());
    ArrayList<GameplayCatalogProperties.Realm> realms = new ArrayList<>();
    if (input.getRealms() != null) {
      for (GameplayCatalogProperties.Realm realm : input.getRealms()) {
        if (realm != null) {
          realms.add(copyRealm(realm));
        }
      }
    }
    world.setRealms(realms);
    return world;
  }

  private static GameplayCatalogProperties.Realm copyRealm(GameplayCatalogProperties.Realm input) {
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug(input.getSlug());
    realm.setDisplayName(input.getDisplayName());
    realm.setTenantId(input.getTenantId());
    realm.setGameInstanceId(input.getGameInstanceId());
    realm.setPointerVersion(input.getPointerVersion());
    realm.setVisible(input.isVisible());
    realm.setPublicProductionRealm(input.isPublicProductionRealm());
    realm.setRequiresCharacterSelection(input.isRequiresCharacterSelection());
    realm.setStateScope(input.getStateScope());
    realm.setCharacterCreationPolicy(input.getCharacterCreationPolicy());
    return realm;
  }

  public record RuntimeRealmTarget(
      String worldSlug, String worldDisplayName, String realmSlug, String realmDisplayName) {}
}
