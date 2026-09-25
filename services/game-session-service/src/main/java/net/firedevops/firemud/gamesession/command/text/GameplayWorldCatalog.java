package net.firedevops.firemud.gamesession.command.text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves the canonical public world list and selector forms used by WORLDS/PLAY. */
@Component
public final class GameplayWorldCatalog {
  private final Supplier<List<WorldView>> worldSupplier;

  private GameplayWorldCatalog(Supplier<List<WorldView>> worldSupplier) {
    this.worldSupplier = Objects.requireNonNull(worldSupplier, "worldSupplier must not be null");
  }

  @Autowired
  public GameplayWorldCatalog(GameplayAdmissionPointerAuthorityService authorityService) {
    this(
        () -> {
          Objects.requireNonNull(authorityService, "authorityService must not be null");
          return toWorlds(authorityService.listPointers());
        });
  }

  public static GameplayWorldCatalog forWorldViews(List<WorldView> worlds) {
    return new GameplayWorldCatalog(() -> normalizeWorlds(worlds));
  }

  public static GameplayWorldCatalog forWorldSupplier(Supplier<List<WorldView>> worldSupplier) {
    return new GameplayWorldCatalog(() -> normalizeWorlds(worldSupplier.get()));
  }

  public WorldsViewOutput browseView() {
    return new WorldsViewOutput(worldEntries());
  }

  public Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    return resolvePublicWorld(worldSelector)
        .map(world -> new RealmBrowseViewOutput(world.slug(), realmEntries(world)));
  }

  /** Resolves only realms currently safe to expose through the public browse projections. */
  public Optional<WorldView> resolvePublicWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    List<WorldView> worlds = publicVisibleWorlds();
    try {
      int index = Integer.parseInt(selector);
      if (index >= 1 && index <= worlds.size()) {
        return Optional.of(worlds.get(index - 1));
      }
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<WorldView> matches =
        worlds.stream()
            .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<WorldView> resolveWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    List<WorldView> worlds = visibleWorlds();
    try {
      int index = Integer.parseInt(selector);
      List<WorldView> publicWorlds = publicVisibleWorlds();
      if (index >= 1 && index <= publicWorlds.size()) {
        return Optional.of(publicWorlds.get(index - 1));
      }
      // Numeric selectors are the public menu's aliases. Non-public admission remains
      // available by its explicit world slug until Account supplies caller-bound authority.
      return Optional.empty();
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<WorldView> matches =
        worlds.stream()
            .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RealmView> resolveRealm(WorldView world, String selector) {
    if (world == null || selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return visibleRealms(world).stream()
        .filter(realm -> normalized.equals(realm.slug().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  // Admission may resolve hidden realms, but callers must still perform membership and grant
  // checks.
  Optional<RealmView> resolveRealmForAdmission(WorldView world, String selector) {
    if (world == null || selector == null || selector.isBlank() || world.realms() == null) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return world.realms().stream()
        .filter(realm -> realm != null && realm.slug() != null && !realm.slug().isBlank())
        .filter(realm -> normalized.equals(realm.slug().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  boolean hasRealmForAdmission(WorldView world, String selector) {
    return resolveRealmForAdmission(world, selector).isPresent();
  }

  public Optional<RealmView> resolveDefaultRealm(WorldView world) {
    if (world == null) {
      return Optional.empty();
    }
    List<RealmView> visibleRealms = visibleRealms(world);
    if (visibleRealms.isEmpty()) {
      return Optional.empty();
    }
    return visibleRealms.stream()
        .filter(RealmView::publicProductionRealm)
        .findFirst()
        .or(() -> Optional.of(visibleRealms.get(0)));
  }

  public boolean requiresExplicitRealmSelection(WorldView world) {
    return visibleRealms(world).size() > 1;
  }

  public Optional<RealmView> resolveRealmByRuntimeTarget(long tenantId, long gameInstanceId) {
    List<RealmView> matches =
        normalizeWorlds(worldSupplier.get()).stream()
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.tenantId() == tenantId)
            .filter(realm -> realm.gameInstanceId() == gameInstanceId)
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RuntimeRealmTarget> resolveRuntimeTarget(long tenantId, long gameInstanceId) {
    List<RuntimeRealmTarget> matches =
        normalizeWorlds(worldSupplier.get()).stream()
            .flatMap(
                world ->
                    world.realms().stream()
                        .filter(realm -> realm.tenantId() == tenantId)
                        .filter(realm -> realm.gameInstanceId() == gameInstanceId)
                        .map(
                            realm ->
                                new RuntimeRealmTarget(
                                    world.slug(),
                                    world.displayName(),
                                    realm.slug(),
                                    realm.displayName())))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RuntimeRealmTarget> resolveRealmTarget(String worldSlug, String realmSlug) {
    if (worldSlug == null || worldSlug.isBlank() || realmSlug == null || realmSlug.isBlank()) {
      return Optional.empty();
    }
    String normalizedWorld = worldSlug.trim().toLowerCase(Locale.ROOT);
    String normalizedRealm = realmSlug.trim().toLowerCase(Locale.ROOT);
    return visibleWorlds().stream()
        .filter(world -> normalizedWorld.equals(world.slug().toLowerCase(Locale.ROOT)))
        .flatMap(
            world ->
                visibleRealms(world).stream()
                    .filter(realm -> normalizedRealm.equals(realm.slug().toLowerCase(Locale.ROOT)))
                    .map(
                        realm ->
                            new RuntimeRealmTarget(
                                world.slug(),
                                world.displayName(),
                                realm.slug(),
                                realm.displayName())))
        .findFirst();
  }

  public List<RealmView> visibleRealms(WorldView world) {
    if (world == null || world.realms() == null) {
      return List.of();
    }
    return world.realms().stream().filter(RealmView::visible).toList();
  }

  public List<RealmView> publicVisibleRealms(WorldView world) {
    return visibleRealms(world).stream().filter(RealmView::publicProductionRealm).toList();
  }

  public List<WorldView> visibleWorlds() {
    return normalizeWorlds(worldSupplier.get()).stream()
        .filter(this::hasVisibleRealmEntries)
        .toList();
  }

  public List<WorldView> publicVisibleWorlds() {
    return normalizeWorlds(worldSupplier.get()).stream()
        .filter(world -> !publicVisibleRealms(world).isEmpty())
        .toList();
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    List<WorldView> worlds = publicVisibleWorlds();
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(worlds.size());
    for (int i = 0; i < worlds.size(); i++) {
      WorldView world = worlds.get(i);
      RealmView defaultRealm = publicVisibleRealms(world).getFirst();
      entries.add(
          new WorldsViewOutput.WorldEntry(
              i + 1,
              world.slug(),
              world.displayName(),
              defaultRealm.gameInstanceId(),
              defaultRealm.requiresCharacterSelection()));
    }
    return List.copyOf(entries);
  }

  private List<RealmBrowseViewOutput.RealmEntry> realmEntries(WorldView world) {
    List<RealmView> realms = publicVisibleRealms(world);
    ArrayList<RealmBrowseViewOutput.RealmEntry> entries = new ArrayList<>(realms.size());
    for (int i = 0; i < realms.size(); i++) {
      RealmView realm = realms.get(i);
      entries.add(
          new RealmBrowseViewOutput.RealmEntry(
              i + 1,
              realm.slug(),
              realm.displayName(),
              realm.gameInstanceId(),
              realm.requiresCharacterSelection(),
              realm.stateScope(),
              realm.characterCreationPolicy()));
    }
    return List.copyOf(entries);
  }

  private boolean hasVisibleRealmEntries(WorldView world) {
    return world != null && !visibleRealms(world).isEmpty();
  }

  private RealmView defaultRealm(WorldView world) {
    return resolveDefaultRealm(world)
        .orElseGet(
            () -> {
              return new RealmView(
                  "production",
                  "Live Realm",
                  0L,
                  0L,
                  1L,
                  true,
                  true,
                  false,
                  "SHARED",
                  "ALLOW_NEW",
                  0L);
            });
  }

  private static List<WorldView> normalizeWorlds(List<WorldView> worlds) {
    if (worlds == null) {
      return List.of();
    }
    return worlds.stream()
        .filter(Objects::nonNull)
        .filter(world -> world.slug() != null && !world.slug().isBlank())
        .map(GameplayWorldCatalog::copyWorldView)
        .toList();
  }

  private static List<WorldView> toWorlds(List<GameplayAdmissionPointerSnapshot> pointers) {
    List<GameplayAdmissionPointerSnapshot> completePointers =
        pointers.stream().filter(GameplayWorldCatalog::hasCompleteAuthorityPointer).toList();
    Map<String, Set<Long>> tenantsByWorldSlug = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : completePointers) {
      tenantsByWorldSlug
          .computeIfAbsent(normalizeSlug(pointer.worldSlug()), ignored -> new HashSet<>())
          .add(pointer.tenantId());
    }

    Map<TenantWorldKey, MutableWorldAccumulator> worlds = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : completePointers) {
      String normalizedWorldSlug = normalizeSlug(pointer.worldSlug());
      if (tenantsByWorldSlug.get(normalizedWorldSlug).size() > 1) {
        continue;
      }
      TenantWorldKey key = new TenantWorldKey(pointer.tenantId(), normalizedWorldSlug);
      MutableWorldAccumulator world =
          worlds.computeIfAbsent(
              key,
              ignored ->
                  new MutableWorldAccumulator(pointer.worldSlug(), pointer.worldDisplayName()));
      world
          .realmsBySlug
          .computeIfAbsent(pointer.realmSlug(), ignored -> new ArrayList<>())
          .add(pointer);
    }
    return normalizeWorlds(
        worlds.values().stream()
            .map(
                world ->
                    new WorldView(
                        world.slug,
                        world.displayName,
                        world.realmsBySlug.entrySet().stream()
                            .filter(entry -> entry.getValue().size() == 1)
                            .map(entry -> toRealmView(entry.getValue().getFirst()))
                            .toList()))
            .toList());
  }

  private static String normalizeSlug(String slug) {
    return slug.toLowerCase(Locale.ROOT);
  }

  private record TenantWorldKey(long tenantId, String normalizedSlug) {}

  private static boolean hasCompleteAuthorityPointer(GameplayAdmissionPointerSnapshot pointer) {
    return GameplayAdmissionPointerSnapshots.hasCompleteRoutingBundle(pointer)
        && pointer.characterCreationPolicy() != null
        && !pointer.characterCreationPolicy().isBlank();
  }

  private static RealmView toRealmView(GameplayAdmissionPointerSnapshot pointer) {
    return new RealmView(
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

  private static WorldView copyWorldView(WorldView input) {
    return new WorldView(
        input.slug(),
        input.displayName(),
        input.realms() == null
            ? List.of()
            : input.realms().stream()
                .filter(Objects::nonNull)
                .filter(realm -> realm.slug() != null && !realm.slug().isBlank())
                .map(GameplayWorldCatalog::copyRealmView)
                .toList());
  }

  private static RealmView copyRealmView(RealmView input) {
    return new RealmView(
        input.slug(),
        input.displayName(),
        input.tenantId(),
        input.gameInstanceId(),
        input.pointerVersion(),
        input.visible(),
        input.publicProductionRealm(),
        input.requiresCharacterSelection(),
        input.stateScope(),
        input.characterCreationPolicy(),
        input.catalogRevision(),
        input.realmId(),
        input.playableStateNamespaceId());
  }

  public record WorldView(String slug, String displayName, List<RealmView> realms) {
    public WorldView {
      realms = realms == null ? List.of() : List.copyOf(realms);
    }
  }

  public record RealmView(
      String slug,
      String displayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy,
      long catalogRevision,
      UUID realmId,
      UUID playableStateNamespaceId) {
    /** Creates a synthetic realm view without authoritative identity evidence. */
    public RealmView(
        String slug,
        String displayName,
        long tenantId,
        long gameInstanceId,
        long pointerVersion,
        boolean visible,
        boolean publicProductionRealm,
        boolean requiresCharacterSelection,
        String stateScope,
        String characterCreationPolicy,
        long catalogRevision) {
      this(
          slug,
          displayName,
          tenantId,
          gameInstanceId,
          pointerVersion,
          visible,
          publicProductionRealm,
          requiresCharacterSelection,
          stateScope,
          characterCreationPolicy,
          catalogRevision,
          null,
          null);
    }

    /** Creates a synthetic realm view without authoritative catalog revision evidence. */
    public RealmView(
        String slug,
        String displayName,
        long tenantId,
        long gameInstanceId,
        long pointerVersion,
        boolean visible,
        boolean publicProductionRealm,
        boolean requiresCharacterSelection,
        String stateScope,
        String characterCreationPolicy) {
      this(
          slug,
          displayName,
          tenantId,
          gameInstanceId,
          pointerVersion,
          visible,
          publicProductionRealm,
          requiresCharacterSelection,
          stateScope,
          characterCreationPolicy,
          0L);
    }
  }

  public record RuntimeRealmTarget(
      String worldSlug, String worldDisplayName, String realmSlug, String realmDisplayName) {}

  private static final class MutableWorldAccumulator {
    private final String slug;
    private final String displayName;
    private final Map<String, List<GameplayAdmissionPointerSnapshot>> realmsBySlug =
        new LinkedHashMap<>();

    private MutableWorldAccumulator(String slug, String displayName) {
      this.slug = slug;
      this.displayName = displayName;
    }
  }
}
