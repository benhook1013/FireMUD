package net.firedevops.firemud.gamesession.command.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
  private final Supplier<List<GameplayAdmissionPointerSnapshot>> authorityPointerSupplier;
  private final LongFunction<List<GameplayAdmissionPointerSnapshot>> tenantAuthorityPointerSupplier;

  private GameplayWorldCatalog(Supplier<List<WorldView>> worldSupplier) {
    this(worldSupplier, null, null);
  }

  private GameplayWorldCatalog(
      Supplier<List<WorldView>> worldSupplier,
      Supplier<List<GameplayAdmissionPointerSnapshot>> authorityPointerSupplier,
      LongFunction<List<GameplayAdmissionPointerSnapshot>> tenantAuthorityPointerSupplier) {
    this.worldSupplier = Objects.requireNonNull(worldSupplier, "worldSupplier must not be null");
    this.authorityPointerSupplier = authorityPointerSupplier;
    this.tenantAuthorityPointerSupplier = tenantAuthorityPointerSupplier;
  }

  @Autowired
  public GameplayWorldCatalog(GameplayAdmissionPointerAuthorityService authorityService) {
    this(
        () -> toWorlds(healthyTenantPointers(loadAuthorityPointers(authorityService))),
        () -> loadAuthorityPointers(authorityService),
        authorityService::listPointersByTenant);
  }

  public static GameplayWorldCatalog forWorldViews(List<WorldView> worlds) {
    return new GameplayWorldCatalog(() -> worlds);
  }

  public static GameplayWorldCatalog forWorldSupplier(Supplier<List<WorldView>> worldSupplier) {
    return new GameplayWorldCatalog(worldSupplier);
  }

  public WorldsViewOutput browseView() {
    CatalogSnapshot snapshot = loadWorldSnapshot();
    return new WorldsViewOutput(worldEntries(publicDiscoveryWorlds(snapshot)));
  }

  public Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    if (worldSelector == null || worldSelector.isBlank()) {
      return Optional.empty();
    }
    CatalogSnapshot snapshot = loadWorldSnapshot();
    return resolveWorld(worldSelector, publicDiscoveryWorlds(snapshot))
        .map(world -> new RealmBrowseViewOutput(world.slug(), realmEntries(world)));
  }

  public Optional<WorldView> resolveWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    return resolveWorld(selector, loadWorldSnapshot());
  }

  private Optional<WorldView> resolveWorld(String selector, CatalogSnapshot snapshot) {
    List<WorldView> visibleWorlds = visibleWorlds(snapshot);
    List<WorldView> indexedWorlds = publicDiscoveryWorlds(snapshot);
    try {
      int index = Integer.parseInt(selector);
      if (index >= 1 && index <= indexedWorlds.size()) {
        return Optional.of(indexedWorlds.get(index - 1));
      }
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    return resolveWorldBySlug(selector, visibleWorlds);
  }

  public Optional<WorldView> resolveWorldFromAuthoritySnapshot(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    return resolveWorld(selector, visibleWorldsFromAuthoritySnapshot());
  }

  /** Builds the no-caller discovery projection from one validated authority snapshot. */
  public List<WorldView> publicWorldsFromAuthoritySnapshot() {
    return publicDiscoveryWorlds(loadAuthorityWorldSnapshot());
  }

  /** Resolves only worlds exposed by the no-caller public discovery surface. */
  public Optional<WorldView> resolvePublicWorldFromAuthoritySnapshot(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    return resolveWorld(selector, publicWorldsFromAuthoritySnapshot());
  }

  private Optional<WorldView> resolveWorld(String selector, List<WorldView> worlds) {
    try {
      int index = Integer.parseInt(selector);
      if (index >= 1 && index <= worlds.size()) {
        return Optional.of(worlds.get(index - 1));
      }
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    List<WorldView> matches =
        worlds.stream()
            .filter(world -> selectorKey(selector).equals(selectorKey(world.slug())))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private Optional<WorldView> resolveWorldBySlug(String selector, List<WorldView> worlds) {
    String normalized = selectorKey(selector);
    List<WorldView> matches =
        worlds.stream().filter(world -> normalized.equals(selectorKey(world.slug()))).toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RealmView> resolveRealm(WorldView world, String selector) {
    if (world == null || selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    String normalized = selectorKey(selector);
    List<RealmView> matches =
        visibleRealms(world).stream()
            .filter(realm -> normalized.equals(selectorKey(realm.slug())))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  // Admission may resolve hidden realms, but callers must still perform membership and grant
  // checks.
  Optional<RealmView> resolveRealmForAdmission(WorldView world, String selector) {
    if (world == null || selector == null || selector.isBlank() || world.realms() == null) {
      return Optional.empty();
    }
    String normalized = selectorKey(selector);
    List<RealmView> matches =
        world.realms().stream()
            .filter(realm -> realm != null && realm.slug() != null && !realm.slug().isBlank())
            .filter(realm -> normalized.equals(selectorKey(realm.slug())))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  boolean hasRealmForAdmission(WorldView world, String selector) {
    return resolveRealmForAdmission(world, selector).isPresent();
  }

  public Optional<RealmView> resolveDefaultRealm(WorldView world) {
    // Avoid loading authoritative routing data when the supplied world cannot be selected.
    if (world == null) {
      return Optional.empty();
    }
    List<RealmView> visibleRealms = visibleRealms(world);
    if (visibleRealms.isEmpty()) {
      return Optional.empty();
    }
    return resolveDefaultRealm(world, loadWorldSnapshot());
  }

  private Optional<RealmView> resolveDefaultRealm(WorldView world, CatalogSnapshot snapshot) {
    if (world == null) {
      return Optional.empty();
    }
    List<RealmView> visibleRealms = visibleRealms(world);
    if (visibleRealms.isEmpty()) {
      return Optional.empty();
    }
    List<RealmView> publicProductionRealms =
        visibleRealms.stream().filter(RealmView::publicProductionRealm).toList();
    if (publicProductionRealms.size() > 1) {
      return Optional.empty();
    }
    if (publicProductionRealms.size() == 1) {
      RealmView publicRealm = publicProductionRealms.getFirst();
      return publicProductionRealmCardinality(snapshot, publicRealm.tenantId())
              == PublicProductionRealmCardinality.EXACTLY_ONE
          ? Optional.of(publicRealm)
          : Optional.empty();
    }
    // Non-public presentation remains available only when its tenant has an unambiguous public
    // production realm elsewhere in the catalog; zero or multiple public targets never default.
    return visibleRealms.stream()
            .map(RealmView::tenantId)
            .distinct()
            .allMatch(
                tenantId ->
                    publicProductionRealmCardinality(snapshot, tenantId)
                        == PublicProductionRealmCardinality.EXACTLY_ONE)
        ? Optional.of(visibleRealms.getFirst())
        : Optional.empty();
  }

  /**
   * Returns the tenant-wide cardinality of visible, player-addressable public-production realms.
   */
  public PublicProductionRealmCardinality publicProductionRealmCardinality(long tenantId) {
    if (tenantId <= 0L) {
      return PublicProductionRealmCardinality.ZERO;
    }
    return publicProductionRealmCardinality(loadWorldSnapshot(), tenantId);
  }

  private PublicProductionRealmCardinality publicProductionRealmCardinality(
      CatalogSnapshot snapshot, long tenantId) {
    return switch (Long.compare(
        snapshot.publicProductionRealmCounts().getOrDefault(tenantId, 0L), 1L)) {
      case -1 -> PublicProductionRealmCardinality.ZERO;
      case 0 -> PublicProductionRealmCardinality.EXACTLY_ONE;
      default -> PublicProductionRealmCardinality.MULTIPLE;
    };
  }

  public boolean hasValidPublicProductionRealm(long tenantId) {
    return publicProductionRealmCardinality(tenantId)
        == PublicProductionRealmCardinality.EXACTLY_ONE;
  }

  /** Returns whether each tenant represented by this world has unambiguous public routing. */
  public boolean hasValidPublicProductionRealm(WorldView world) {
    List<RealmView> visibleRealms = visibleRealms(world);
    if (visibleRealms.isEmpty()) {
      return false;
    }
    CatalogSnapshot snapshot = loadWorldSnapshot();
    return visibleRealms.stream()
        .map(RealmView::tenantId)
        .distinct()
        .allMatch(
            tenantId ->
                publicProductionRealmCardinality(snapshot, tenantId)
                    == PublicProductionRealmCardinality.EXACTLY_ONE);
  }

  public boolean requiresExplicitRealmSelection(WorldView world) {
    return visibleRealms(world).size() > 1;
  }

  public Optional<RealmView> resolveRealmByRuntimeTarget(long tenantId, long gameInstanceId) {
    CatalogSnapshot snapshot = loadWorldSnapshot();
    List<RealmView> matches =
        snapshot.worlds().stream()
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.tenantId() == tenantId)
            .filter(realm -> realm.gameInstanceId() == gameInstanceId)
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RuntimeRealmTarget> resolveRuntimeTarget(long tenantId, long gameInstanceId) {
    CatalogSnapshot snapshot = loadWorldSnapshot();
    List<RuntimeRealmTarget> matches =
        snapshot.worlds().stream()
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
    String normalizedWorld = selectorKey(worldSlug);
    String normalizedRealm = selectorKey(realmSlug);
    List<RuntimeRealmTarget> matches =
        visibleWorlds(loadWorldSnapshot()).stream()
            .filter(world -> normalizedWorld.equals(selectorKey(world.slug())))
            .flatMap(
                world ->
                    visibleRealms(world).stream()
                        .filter(realm -> normalizedRealm.equals(selectorKey(realm.slug())))
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

  public List<RealmView> visibleRealms(WorldView world) {
    if (world == null || world.realms() == null) {
      return List.of();
    }
    return world.realms().stream().filter(RealmView::visible).toList();
  }

  public List<WorldView> visibleWorlds() {
    return visibleWorlds(loadWorldSnapshot());
  }

  private List<WorldView> visibleWorlds(CatalogSnapshot snapshot) {
    return snapshot.worlds().stream().filter(this::hasVisibleRealmEntries).toList();
  }

  /** Builds the visible world projection from one validated authoritative pointer snapshot. */
  public List<WorldView> visibleWorldsFromAuthoritySnapshot() {
    if (authorityPointerSupplier == null) {
      return visibleWorlds();
    }
    return visibleWorlds(loadAuthorityWorldSnapshot());
  }

  /**
   * Requires a direct public-production admission target to match the sole visible public realm in
   * its tenant's current authoritative pointer snapshot.
   */
  public void requireUniqueVisiblePublicProductionRealm(
      GameplayAdmissionPointerSnapshot expectedPointer) {
    if (tenantAuthorityPointerSupplier == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative tenant gameplay pointer list is unavailable");
    }
    if (expectedPointer == null || expectedPointer.tenantId() <= 0L) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm identity is unavailable");
    }
    List<GameplayAdmissionPointerSnapshot> pointers =
        loadAuthorityPointersForTenant(expectedPointer.tenantId());
    if (!tenantHasValidPublicProductionAuthority(pointers, expectedPointer.tenantId())) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm is incomplete or ambiguous for tenant "
              + expectedPointer.tenantId());
    }

    List<GameplayAdmissionPointerSnapshot> publicRealms =
        pointers.stream()
            .filter(pointer -> pointer.tenantId() == expectedPointer.tenantId())
            .filter(GameplayAdmissionPointerSnapshot::visible)
            .filter(GameplayAdmissionPointerSnapshot::publicProductionRealm)
            .toList();
    if (publicRealms.size() != 1 || !publicRealms.getFirst().equals(expectedPointer)) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm is not unique for tenant "
              + expectedPointer.tenantId());
    }
    requireCollisionSafeVisiblePointer(pointers, expectedPointer);
  }

  /**
   * Requires a visible non-public realm to belong to a complete tenant catalog with one visible
   * public-production realm before it is returned through the player-addressable read surface.
   */
  public void requireHealthyVisiblePrivateRealm(GameplayAdmissionPointerSnapshot expectedPointer) {
    if (tenantAuthorityPointerSupplier == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative tenant gameplay pointer list is unavailable");
    }
    if (expectedPointer == null
        || expectedPointer.tenantId() <= 0L
        || !expectedPointer.visible()
        || expectedPointer.publicProductionRealm()
        || !isPlayerAddressable(expectedPointer)
        || !hasCompleteAuthorityPointer(expectedPointer)) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative visible private realm identity is unavailable");
    }
    List<GameplayAdmissionPointerSnapshot> pointers =
        loadAuthorityPointersForTenant(expectedPointer.tenantId());
    if (!tenantHasValidPublicProductionAuthority(pointers, expectedPointer.tenantId())) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm is incomplete or ambiguous for tenant "
              + expectedPointer.tenantId());
    }

    List<GameplayAdmissionPointerSnapshot> selectedRealms =
        pointers.stream()
            .filter(pointer -> pointer.tenantId() == expectedPointer.tenantId())
            .filter(pointer -> pointer.worldSlug().equals(expectedPointer.worldSlug()))
            .filter(pointer -> pointer.realmSlug().equals(expectedPointer.realmSlug()))
            .toList();
    if (selectedRealms.size() != 1 || !selectedRealms.getFirst().equals(expectedPointer)) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative visible private realm is not unique for tenant "
              + expectedPointer.tenantId());
    }
    requireCollisionSafeVisiblePointer(pointers, expectedPointer);
  }

  private static void requireCollisionSafeVisiblePointer(
      List<GameplayAdmissionPointerSnapshot> pointers,
      GameplayAdmissionPointerSnapshot expectedPointer) {
    RealmView expectedRealm = toRealmView(expectedPointer);
    boolean resolvesUniquely =
        toWorlds(pointers).stream()
            .filter(
                world -> selectorKey(expectedPointer.worldSlug()).equals(selectorKey(world.slug())))
            .flatMap(world -> world.realms().stream())
            .anyMatch(expectedRealm::equals);
    if (!resolvesUniquely) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative visible gameplay realm has ambiguous folded selectors for tenant "
              + expectedPointer.tenantId());
    }
  }

  private List<GameplayAdmissionPointerSnapshot> loadAuthorityPointersForTenant(long tenantId) {
    List<GameplayAdmissionPointerSnapshot> pointers =
        tenantAuthorityPointerSupplier.apply(tenantId);
    if (pointers == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative tenant gameplay pointer list is unavailable");
    }
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer == null) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative tenant gameplay pointer identity is unavailable");
      }
      if (pointer.tenantId() != tenantId) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative tenant gameplay pointer escaped the requested tenant scope");
      }
      if (!hasCompleteAuthorityPointer(pointer)) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative tenant gameplay pointer is incomplete");
      }
    }
    return pointers;
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries(List<WorldView> worlds) {
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(worlds.size());
    for (WorldView world : worlds) {
      RealmView publicRealm = world.realms().getFirst();
      entries.add(
          new WorldsViewOutput.WorldEntry(
              entries.size() + 1,
              world.slug(),
              world.displayName(),
              publicRealm.gameInstanceId(),
              publicRealm.requiresCharacterSelection()));
    }
    return List.copyOf(entries);
  }

  private List<RealmBrowseViewOutput.RealmEntry> realmEntries(WorldView world) {
    List<RealmView> realms = visibleRealms(world);
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

  private List<WorldView> publicDiscoveryWorlds(CatalogSnapshot snapshot) {
    return visibleWorlds(snapshot).stream()
        .map(
            world ->
                new WorldView(
                    world.slug(),
                    world.displayName(),
                    visibleRealms(world).stream()
                        .filter(RealmView::publicProductionRealm)
                        .filter(
                            realm ->
                                publicProductionRealmCardinality(snapshot, realm.tenantId())
                                    == PublicProductionRealmCardinality.EXACTLY_ONE)
                        .toList()))
        .filter(world -> world.realms().size() == 1)
        .toList();
  }

  private static boolean isPlayerAddressable(RealmView realm) {
    return realm != null
        && realm.visible()
        && realm.slug() != null
        && !realm.slug().isBlank()
        && realm.tenantId() > 0L;
  }

  private static boolean isPlayerAddressable(GameplayAdmissionPointerSnapshot pointer) {
    return pointer != null
        && pointer.visible()
        && pointer.worldSlug() != null
        && !pointer.worldSlug().isBlank()
        && pointer.realmSlug() != null
        && !pointer.realmSlug().isBlank()
        && pointer.tenantId() > 0L;
  }

  private CatalogSnapshot loadWorldSnapshot() {
    return catalogSnapshot(worldSupplier.get());
  }

  private CatalogSnapshot loadAuthorityWorldSnapshot() {
    if (authorityPointerSupplier == null) {
      return loadWorldSnapshot();
    }
    return catalogSnapshot(toWorlds(healthyTenantPointers(loadAuthorityPointers())));
  }

  private static CatalogSnapshot catalogSnapshot(List<WorldView> sourceWorlds) {
    List<WorldView> worlds = normalizeWorlds(sourceWorlds);
    Map<Long, Long> publicProductionRealmCounts = new HashMap<>();
    for (WorldView world : worlds) {
      for (RealmView realm : world.realms()) {
        if (isPlayerAddressable(realm) && realm.publicProductionRealm()) {
          publicProductionRealmCounts.merge(realm.tenantId(), 1L, Long::sum);
        }
      }
    }
    return new CatalogSnapshot(worlds, Map.copyOf(publicProductionRealmCounts));
  }

  private static List<WorldView> normalizeWorlds(List<WorldView> worlds) {
    if (worlds == null) {
      return List.of();
    }
    List<WorldView> normalizedWorlds =
        worlds.stream()
            .filter(Objects::nonNull)
            .filter(world -> world.slug() != null && !world.slug().isBlank())
            .map(GameplayWorldCatalog::copyWorldView)
            .toList();
    Map<String, Integer> worldCounts = new HashMap<>();
    Map<String, Set<Long>> tenantsByWorld = new HashMap<>();
    for (WorldView world : normalizedWorlds) {
      String key = selectorKey(world.slug());
      worldCounts.merge(key, 1, Integer::sum);
      Set<Long> tenantIds = tenantsByWorld.computeIfAbsent(key, ignored -> new HashSet<>());
      world.realms().stream().map(RealmView::tenantId).forEach(tenantIds::add);
    }
    Set<String> ambiguousWorlds = new HashSet<>();
    for (String key : worldCounts.keySet()) {
      if (worldCounts.get(key) > 1 || tenantsByWorld.get(key).size() > 1) {
        ambiguousWorlds.add(key);
      }
    }
    return normalizedWorlds.stream()
        .filter(world -> !ambiguousWorlds.contains(selectorKey(world.slug())))
        .toList();
  }

  private static List<WorldView> toWorlds(List<GameplayAdmissionPointerSnapshot> pointers) {
    Map<String, Set<Long>> tenantsByWorldSlug = new HashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (hasCompleteAuthorityPointer(pointer)) {
        tenantsByWorldSlug
            .computeIfAbsent(
                pointer.worldSlug().toLowerCase(Locale.ROOT), ignored -> new HashSet<>())
            .add(pointer.tenantId());
      }
    }
    Set<String> ambiguousWorldSlugs =
        tenantsByWorldSlug.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    Map<String, MutableWorldAccumulator> worlds = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (!hasCompleteAuthorityPointer(pointer)
          || ambiguousWorldSlugs.contains(pointer.worldSlug().toLowerCase(Locale.ROOT))) {
        continue;
      }
      MutableWorldAccumulator world =
          worlds.computeIfAbsent(
              pointer.worldSlug(),
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

  private static Set<Long> tenantsWithoutExactlyOneVisiblePublicProductionRealm(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Set<Long> tenantIds = new java.util.HashSet<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      tenantIds.add(pointer.tenantId());
    }
    Set<Long> invalidTenantIds = new java.util.HashSet<>();
    for (long tenantId : tenantIds) {
      if (!tenantHasValidPublicProductionAuthority(pointers, tenantId)) {
        invalidTenantIds.add(tenantId);
      }
    }
    return Set.copyOf(invalidTenantIds);
  }

  private static List<GameplayAdmissionPointerSnapshot> healthyTenantPointers(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Set<Long> invalidTenantIds = tenantsWithoutExactlyOneVisiblePublicProductionRealm(pointers);
    return pointers.stream()
        .filter(pointer -> !invalidTenantIds.contains(pointer.tenantId()))
        .toList();
  }

  private static boolean tenantHasValidPublicProductionAuthority(
      List<GameplayAdmissionPointerSnapshot> pointers, long tenantId) {
    long visiblePublicRealmCount = 0L;
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer.tenantId() != tenantId) {
        continue;
      }
      if (!hasCompleteAuthorityPointer(pointer)) {
        return false;
      }
      if (isPlayerAddressable(pointer) && pointer.visible() && pointer.publicProductionRealm()) {
        visiblePublicRealmCount++;
      }
    }
    return visiblePublicRealmCount == 1L;
  }

  private static List<GameplayAdmissionPointerSnapshot> loadAuthorityPointers(
      GameplayAdmissionPointerAuthorityService authorityService) {
    Objects.requireNonNull(authorityService, "authorityService must not be null");
    List<GameplayAdmissionPointerSnapshot> pointers = authorityService.listPointers();
    return validateAuthoritySnapshot(pointers);
  }

  private List<GameplayAdmissionPointerSnapshot> loadAuthorityPointers() {
    if (authorityPointerSupplier == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    return validateAuthoritySnapshot(authorityPointerSupplier.get());
  }

  private static List<GameplayAdmissionPointerSnapshot> validateAuthoritySnapshot(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    if (pointers == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer == null) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative gameplay pointer identity is unavailable");
      }
      if (pointer.tenantId() <= 0L) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative gameplay pointer tenant identity is unavailable");
      }
    }
    return pointers;
  }

  private static boolean hasCompleteAuthorityPointer(GameplayAdmissionPointerSnapshot pointer) {
    return GameplayAdmissionPointerSnapshots.hasCompleteRoutingBundle(pointer)
        && pointer.catalogRevision() > 0L
        && pointer.realmId() != null
        && pointer.playableStateNamespaceId() != null
        && ("SHARED".equals(pointer.stateScope()) || "ISOLATED".equals(pointer.stateScope()))
        && pointer.characterCreationPolicy() != null
        && !pointer.characterCreationPolicy().isBlank();
  }

  public static final class AuthorityPointerUnavailableException extends RuntimeException {
    public AuthorityPointerUnavailableException(String message) {
      super(message);
    }
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
    List<RealmView> realms =
        input.realms() == null
            ? List.of()
            : input.realms().stream()
                .filter(Objects::nonNull)
                .filter(realm -> realm.slug() != null && !realm.slug().isBlank())
                .map(GameplayWorldCatalog::copyRealmView)
                .toList();
    Map<RealmSelectorKey, Integer> realmCounts = new HashMap<>();
    for (RealmView realm : realms) {
      realmCounts.merge(
          new RealmSelectorKey(realm.tenantId(), selectorKey(realm.slug())), 1, Integer::sum);
    }
    return new WorldView(
        input.slug(),
        input.displayName(),
        realms.stream()
            .filter(
                realm ->
                    realmCounts.get(
                            new RealmSelectorKey(realm.tenantId(), selectorKey(realm.slug())))
                        == 1)
            .toList());
  }

  private static String selectorKey(String slug) {
    return slug.trim().toLowerCase(Locale.ROOT);
  }

  private record RealmSelectorKey(long tenantId, String selector) {}

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

  private record CatalogSnapshot(
      List<WorldView> worlds, Map<Long, Long> publicProductionRealmCounts) {}

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

  public enum PublicProductionRealmCardinality {
    ZERO,
    EXACTLY_ONE,
    MULTIPLE
  }

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
