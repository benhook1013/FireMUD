package net.firedevops.firemud.gamesession.command.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

  private GameplayWorldCatalog(Supplier<List<WorldView>> worldSupplier) {
    this(worldSupplier, null);
  }

  private GameplayWorldCatalog(
      Supplier<List<WorldView>> worldSupplier,
      Supplier<List<GameplayAdmissionPointerSnapshot>> authorityPointerSupplier) {
    this.worldSupplier = Objects.requireNonNull(worldSupplier, "worldSupplier must not be null");
    this.authorityPointerSupplier = authorityPointerSupplier;
  }

  @Autowired
  public GameplayWorldCatalog(GameplayAdmissionPointerAuthorityService authorityService) {
    this(
        () -> {
          Objects.requireNonNull(authorityService, "authorityService must not be null");
          return toWorlds(authorityService.listPointers());
        },
        () -> {
          Objects.requireNonNull(authorityService, "authorityService must not be null");
          return authorityService.listPointers();
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
    return resolveWorld(worldSelector)
        .map(world -> new RealmBrowseViewOutput(world.slug(), realmEntries(world)));
  }

  public Optional<WorldView> resolveWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    List<WorldView> visibleWorlds = visibleWorlds();
    List<WorldView> indexedWorlds = discoverableWorlds();
    try {
      int index = Integer.parseInt(selector);
      if (index >= 1 && index <= indexedWorlds.size()) {
        return Optional.of(indexedWorlds.get(index - 1));
      }
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return visibleWorlds.stream()
        .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  public Optional<WorldView> resolveWorldFromAuthoritySnapshot(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    return resolveWorld(selector, visibleWorldsFromAuthoritySnapshot());
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
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return worlds.stream()
        .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
        .findFirst();
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
    List<RealmView> publicProductionRealms =
        visibleRealms.stream().filter(RealmView::publicProductionRealm).toList();
    if (publicProductionRealms.size() > 1) {
      return Optional.empty();
    }
    if (publicProductionRealms.size() == 1) {
      RealmView publicRealm = publicProductionRealms.getFirst();
      return hasValidPublicProductionRealm(publicRealm.tenantId())
          ? Optional.of(publicRealm)
          : Optional.empty();
    }
    // Non-public presentation remains available only when its tenant has an unambiguous public
    // production realm elsewhere in the catalog; zero or multiple public targets never default.
    return visibleRealms.stream().allMatch(realm -> hasValidPublicProductionRealm(realm.tenantId()))
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
    long count =
        normalizeWorlds(worldSupplier.get()).stream()
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.tenantId() == tenantId)
            .filter(this::isPlayerAddressable)
            .filter(RealmView::publicProductionRealm)
            .count();
    return switch (Long.compare(count, 1L)) {
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
    return !visibleRealms.isEmpty()
        && visibleRealms.stream()
            .map(RealmView::tenantId)
            .distinct()
            .allMatch(this::hasValidPublicProductionRealm);
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

  public List<WorldView> visibleWorlds() {
    return normalizeWorlds(worldSupplier.get()).stream()
        .filter(this::hasVisibleRealmEntries)
        .toList();
  }

  /**
   * Builds the visible world projection from one authoritative pointer-list snapshot.
   *
   * <p>Text discovery intentionally continues to use {@link #visibleWorlds()}, which filters
   * malformed or ambiguous rows for its existing negative-admission behavior. The gRPC discovery
   * boundary validates completeness and tenant-global public-realm uniqueness on one exact snapshot
   * before projecting it.
   */
  public List<WorldView> visibleWorldsFromAuthoritySnapshot() {
    if (authorityPointerSupplier == null) {
      return visibleWorlds();
    }
    List<GameplayAdmissionPointerSnapshot> pointers = authorityPointerSupplier.get();
    if (pointers == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (!hasCompleteAuthorityPointer(pointer)) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative gameplay pointer is incomplete");
      }
    }
    requireExactlyOneVisiblePublicProductionRealmPerTenant(pointers);
    return toWorlds(pointers).stream().filter(this::hasVisibleRealmEntries).toList();
  }

  /**
   * Requires a direct public-production admission target to match the sole visible public realm in
   * its tenant's current authoritative pointer snapshot.
   */
  public void requireUniqueVisiblePublicProductionRealm(
      GameplayAdmissionPointerSnapshot expectedPointer) {
    if (authorityPointerSupplier == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    if (expectedPointer == null || expectedPointer.tenantId() <= 0L) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm identity is unavailable");
    }
    List<GameplayAdmissionPointerSnapshot> pointers = authorityPointerSupplier.get();
    if (pointers == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer == null || pointer.tenantId() == expectedPointer.tenantId()) {
        if (!hasCompleteAuthorityPointer(pointer)) {
          throw new AuthorityPointerUnavailableException(
              "Authoritative gameplay pointer is incomplete");
        }
      }
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
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    List<WorldView> worlds = discoverableWorlds();
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(worlds.size());
    for (WorldView world : worlds) {
      RealmView defaultRealm = defaultRealm(world);
      entries.add(
          new WorldsViewOutput.WorldEntry(
              entries.size() + 1,
              world.slug(),
              world.displayName(),
              defaultRealm.gameInstanceId(),
              defaultRealm.requiresCharacterSelection()));
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

  private List<WorldView> discoverableWorlds() {
    return visibleWorlds().stream()
        .filter(world -> resolveDefaultRealm(world).isPresent())
        .toList();
  }

  private boolean isPlayerAddressable(RealmView realm) {
    return realm != null
        && realm.visible()
        && realm.slug() != null
        && !realm.slug().isBlank()
        && realm.tenantId() > 0L;
  }

  private RealmView defaultRealm(WorldView world) {
    return resolveDefaultRealm(world).orElseThrow();
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
    Map<String, MutableWorldAccumulator> worlds = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (!hasCompleteAuthorityPointer(pointer)) {
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

  private static void requireExactlyOneVisiblePublicProductionRealmPerTenant(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Set<Long> tenantIds =
        pointers.stream()
            .map(GameplayAdmissionPointerSnapshot::tenantId)
            .collect(Collectors.toSet());
    Map<Long, Long> publicRealmCounts = new HashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer.visible() && pointer.publicProductionRealm()) {
        publicRealmCounts.merge(pointer.tenantId(), 1L, Long::sum);
      }
    }
    for (long tenantId : tenantIds) {
      if (publicRealmCounts.getOrDefault(tenantId, 0L) != 1L) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative public-production realm count is invalid for tenant " + tenantId);
      }
    }
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
