package net.firedevops.firemud.gamesession.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;

public final class TestGameplayWorldCatalogs {
  private TestGameplayWorldCatalogs() {}

  public static GameplayWorldCatalog fromProperties(GameplayCatalogProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    return GameplayWorldCatalog.forWorldSupplier(() -> toWorldViews(properties.getWorlds()));
  }

  /**
   * Test-only catalog seam for cross-service transport fixtures that need a healthy demo route
   * without claiming that a World-owned initial admission bind occurred.
   */
  public static final class MutableDefaultDemoCatalog {
    private final GameplayAdmissionPointerAuthorityService pointerAuthority;
    private final AtomicReference<GameplayAdmissionPointerSnapshot> defaultDemo =
        new AtomicReference<>();

    public MutableDefaultDemoCatalog(GameplayAdmissionPointerAuthorityService pointerAuthority) {
      this.pointerAuthority =
          Objects.requireNonNull(pointerAuthority, "pointerAuthority must not be null");
    }

    public GameplayWorldCatalog catalog() {
      return GameplayWorldCatalog.forWorldSupplier(this::worlds);
    }

    public void useDefaultDemo(long tenantId, long gameInstanceId) {
      if (tenantId <= 0 || gameInstanceId <= 0) {
        throw new IllegalArgumentException(
            "test demo tenant and game instance IDs must be positive");
      }
      defaultDemo.set(
          new GameplayAdmissionPointerSnapshot(
              "demo",
              "Demo World",
              "production",
              "Live Realm",
              tenantId,
              gameInstanceId,
              1L,
              true,
              true,
              false,
              "SHARED",
              "ALLOW_NEW",
              1L,
              stableId("realm", tenantId, "demo", "production"),
              stableId("namespace", tenantId, "demo", "production")));
    }

    public void clearDefaultDemo() {
      defaultDemo.set(null);
    }

    private List<GameplayWorldCatalog.WorldView> worlds() {
      List<GameplayAdmissionPointerSnapshot> pointers =
          new ArrayList<>(pointerAuthority.listPointers());
      GameplayAdmissionPointerSnapshot fallback = defaultDemo.get();
      if (fallback != null
          && pointers.stream()
              .noneMatch(
                  pointer ->
                      pointer.worldSlug().equals(fallback.worldSlug())
                          && pointer.realmSlug().equals(fallback.realmSlug()))) {
        pointers.add(fallback);
      }
      return fromPointers(pointers);
    }
  }

  private static List<GameplayWorldCatalog.WorldView> fromPointers(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Map<String, Map<String, List<GameplayAdmissionPointerSnapshot>>> grouped =
        new LinkedHashMap<>();
    Map<String, String> displayNames = new HashMap<>();
    Map<Long, Long> publicRealmCounts = new HashMap<>();
    Set<UUID> realmIds = new HashSet<>();
    pointers.forEach(TestGameplayWorldCatalogs::requireComplete);
    List<GameplayAdmissionPointerSnapshot> orderedPointers =
        pointers.stream()
            .sorted(
                Comparator.comparing(GameplayAdmissionPointerSnapshot::worldSlug)
                    .thenComparing(GameplayAdmissionPointerSnapshot::realmSlug)
                    .thenComparingLong(GameplayAdmissionPointerSnapshot::tenantId))
            .toList();
    for (GameplayAdmissionPointerSnapshot pointer : orderedPointers) {
      if (!realmIds.add(pointer.realmId())) {
        throw new IllegalArgumentException("test catalog contains a duplicate realm ID");
      }
      if (pointer.visible() && pointer.publicProductionRealm()) {
        publicRealmCounts.merge(pointer.tenantId(), 1L, Long::sum);
      }
      grouped
          .computeIfAbsent(pointer.worldSlug(), ignored -> new LinkedHashMap<>())
          .computeIfAbsent(pointer.realmSlug(), ignored -> new ArrayList<>())
          .add(pointer);
      displayNames.putIfAbsent(pointer.worldSlug(), pointer.worldDisplayName());
    }
    Set<Long> tenants =
        pointers.stream()
            .map(GameplayAdmissionPointerSnapshot::tenantId)
            .collect(java.util.stream.Collectors.toSet());
    for (long tenantId : tenants) {
      if (publicRealmCounts.getOrDefault(tenantId, 0L) != 1L) {
        throw new IllegalArgumentException(
            "test catalog requires exactly one visible public production realm per tenant");
      }
    }
    return grouped.entrySet().stream()
        .map(
            world ->
                new GameplayWorldCatalog.WorldView(
                    world.getKey(),
                    displayNames.get(world.getKey()),
                    world.getValue().entrySet().stream()
                        .map(
                            realm -> {
                              if (realm.getValue().size() != 1) {
                                throw new IllegalArgumentException(
                                    "test catalog contains a duplicate world/realm selector");
                              }
                              return toRealmView(realm.getValue().getFirst());
                            })
                        .toList()))
        .toList();
  }

  private static void requireComplete(GameplayAdmissionPointerSnapshot pointer) {
    if (pointer == null
        || pointer.worldSlug() == null
        || pointer.worldSlug().isBlank()
        || pointer.worldDisplayName() == null
        || pointer.worldDisplayName().isBlank()
        || pointer.realmSlug() == null
        || pointer.realmSlug().isBlank()
        || pointer.realmDisplayName() == null
        || pointer.realmDisplayName().isBlank()
        || pointer.tenantId() <= 0
        || pointer.gameInstanceId() <= 0
        || pointer.pointerVersion() <= 0
        || pointer.catalogRevision() <= 0
        || pointer.realmId() == null
        || pointer.playableStateNamespaceId() == null
        || !("SHARED".equals(pointer.stateScope()) || "ISOLATED".equals(pointer.stateScope()))
        || pointer.characterCreationPolicy() == null
        || pointer.characterCreationPolicy().isBlank()) {
      throw new IllegalArgumentException("test catalog pointer is incomplete");
    }
  }

  private static GameplayWorldCatalog.RealmView toRealmView(
      GameplayAdmissionPointerSnapshot pointer) {
    return new GameplayWorldCatalog.RealmView(
        pointer.realmSlug(),
        pointer.realmDisplayName(),
        pointer.tenantId(),
        pointer.gameInstanceId(),
        pointer.pointerVersion(),
        pointer.visible(),
        pointer.publicProductionRealm(),
        pointer.requiresCharacterSelection(),
        pointer.stateScope(),
        pointer.characterCreationPolicy(),
        pointer.catalogRevision(),
        pointer.realmId(),
        pointer.playableStateNamespaceId());
  }

  private static UUID stableId(String kind, long tenantId, String worldSlug, String realmSlug) {
    return UUID.nameUUIDFromBytes(
        ("firemud-test-catalog:" + kind + ":" + tenantId + ":" + worldSlug + ":" + realmSlug)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
