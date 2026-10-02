package net.firedevops.firemud.gamesession.command.text;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.RealmOrdinalTarget;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.WorldOrdinalTarget;
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
    return new GameplayWorldCatalog(() -> worlds);
  }

  public static GameplayWorldCatalog forWorldSupplier(Supplier<List<WorldView>> worldSupplier) {
    return new GameplayWorldCatalog(worldSupplier);
  }

  public WorldsViewOutput browseView() {
    return readDiscoverySnapshot().output();
  }

  /** Reads the catalog once and builds the exact public WORLDS projection and ordinal authority. */
  public DiscoverySnapshot readDiscoverySnapshot() {
    CatalogState catalogState = readCatalogState();
    List<WorldView> catalogWorlds = catalogState.worlds();
    List<WorldView> visibleWorlds =
        catalogWorlds.stream().filter(this::hasVisibleRealmEntries).toList();
    List<WorldView> discoverableWorlds =
        visibleWorlds.stream()
            .filter(world -> hasPublicDiscoveryRealm(world, catalogState))
            .toList();
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(discoverableWorlds.size());
    ArrayList<WorldOrdinalTarget> targets = new ArrayList<>(discoverableWorlds.size());
    for (WorldView world : discoverableWorlds) {
      RealmView defaultRealm = resolveDefaultRealm(world, catalogState).orElseThrow();
      int ordinal = entries.size() + 1;
      entries.add(
          new WorldsViewOutput.WorldEntry(
              ordinal,
              world.slug(),
              world.displayName(),
              defaultRealm.requiresCharacterSelection()));
      targets.add(
          new WorldOrdinalTarget(
              ordinal,
              world.slug(),
              defaultRealm.tenantId(),
              defaultRealm.catalogRevision(),
              worldTargetFingerprint(world, defaultRealm)));
    }
    List<WorldOrdinalTarget> exactTargets = List.copyOf(targets);
    return new DiscoverySnapshot(
        new WorldsViewOutput(entries),
        fingerprint(
            exactTargets.stream().map(GameplayWorldCatalog::targetFingerprintInput).toList()),
        exactTargets,
        visibleWorlds,
        catalogState.publicProductionCounts());
  }

  public Optional<WorldView> resolveStableWorld(DiscoverySnapshot snapshot, String selector) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (selector == null || selector.isBlank() || isOrdinalSelector(selector)) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<WorldView> matches =
        snapshot.visibleWorlds().stream()
            .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<WorldView> resolveSnapshotOrdinal(
      DiscoverySnapshot current, WorldOrdinalTarget originatingTarget) {
    Objects.requireNonNull(current, "current must not be null");
    Objects.requireNonNull(originatingTarget, "originatingTarget must not be null");
    List<WorldOrdinalTarget> matches =
        current.ordinalTargets().stream()
            .filter(target -> target.ordinal() == originatingTarget.ordinal())
            .filter(originatingTarget::equals)
            .toList();
    if (matches.size() != 1) {
      return Optional.empty();
    }
    List<WorldView> worldMatches =
        current.visibleWorlds().stream()
            .filter(world -> world.slug().equalsIgnoreCase(originatingTarget.worldSlug()))
            .filter(world -> worldTenantId(world) == originatingTarget.tenantId())
            .toList();
    return worldMatches.size() == 1 ? Optional.of(worldMatches.getFirst()) : Optional.empty();
  }

  public static boolean isOrdinalSelector(String selector) {
    if (selector == null || selector.isBlank()) {
      return false;
    }
    String trimmed = selector.trim();
    if (!trimmed.chars().allMatch(Character::isDigit)) {
      return false;
    }
    return true;
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
    if (isOrdinalSelector(selector)) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<WorldView> matches =
        worlds.stream()
            .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  /** Reads the current realm catalog and records the exact response-local ordinal targets. */
  public RealmDiscoverySnapshot readRealmDiscoverySnapshot(WorldView world) {
    Objects.requireNonNull(world, "world must not be null");
    return realmDiscoverySnapshot(world, visibleRealms(world));
  }

  /** Builds a REALMS snapshot whose ordinals match the filtered response entries. */
  public RealmDiscoverySnapshot realmDiscoverySnapshot(
      WorldView world, List<RealmView> responseRealms) {
    Objects.requireNonNull(world, "world must not be null");
    List<RealmView> visibleCatalogRealms = visibleRealms(world);
    List<RealmView> safeResponseRealms =
        List.copyOf(Objects.requireNonNull(responseRealms, "responseRealms must not be null"));
    if (safeResponseRealms.stream().anyMatch(realm -> !visibleCatalogRealms.contains(realm))) {
      throw new IllegalArgumentException("REALMS response contains a non-visible realm");
    }
    List<RealmOrdinalTarget> targets = new ArrayList<>(safeResponseRealms.size());
    for (int index = 0; index < safeResponseRealms.size(); index++) {
      RealmView realm = safeResponseRealms.get(index);
      targets.add(
          new RealmOrdinalTarget(
              index + 1,
              realm.slug(),
              realm.tenantId(),
              realm.catalogRevision(),
              realm.pointerVersion(),
              realmTargetFingerprint(world, realm)));
    }
    List<RealmOrdinalTarget> catalogTargets = new ArrayList<>(visibleCatalogRealms.size());
    for (int index = 0; index < visibleCatalogRealms.size(); index++) {
      RealmView realm = visibleCatalogRealms.get(index);
      catalogTargets.add(
          new RealmOrdinalTarget(
              index + 1,
              realm.slug(),
              realm.tenantId(),
              realm.catalogRevision(),
              realm.pointerVersion(),
              realmTargetFingerprint(world, realm)));
    }
    return new RealmDiscoverySnapshot(
        world.slug(),
        fingerprint(
            catalogTargets.stream()
                .map(GameplayWorldCatalog::realmTargetFingerprintInput)
                .toList()),
        List.copyOf(targets));
  }

  /** Resolves a numeric REALMS target by identity, never by its current ordinal. */
  public Optional<RealmView> resolveRealmSnapshotOrdinal(
      WorldView world, RealmDiscoverySnapshot current, RealmOrdinalTarget originatingTarget) {
    Objects.requireNonNull(world, "world must not be null");
    Objects.requireNonNull(current, "current must not be null");
    Objects.requireNonNull(originatingTarget, "originatingTarget must not be null");
    if (!world.slug().equalsIgnoreCase(current.worldSlug())) {
      return Optional.empty();
    }
    List<RealmView> matches =
        visibleRealms(world).stream()
            .filter(realm -> realm.slug().equalsIgnoreCase(originatingTarget.realmSlug()))
            .filter(realm -> realm.tenantId() == originatingTarget.tenantId())
            .filter(realm -> realm.catalogRevision() == originatingTarget.catalogRevision())
            .filter(realm -> realm.pointerVersion() == originatingTarget.pointerVersion())
            .filter(
                realm ->
                    realmTargetFingerprint(world, realm)
                        .equals(originatingTarget.targetFingerprint()))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<WorldView> resolveWorld(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    CatalogState catalogState = readCatalogState();
    List<WorldView> catalogWorlds = catalogState.worlds();
    List<WorldView> visibleWorlds =
        catalogWorlds.stream().filter(this::hasVisibleRealmEntries).toList();
    return resolveWorld(selector, visibleWorlds, discoverableWorlds(visibleWorlds, catalogState));
  }

  public Optional<WorldView> resolveWorldFromAuthoritySnapshot(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    if (authorityPointerSupplier == null) {
      List<WorldView> visibleWorlds = visibleWorldsFromAuthoritySnapshot();
      CatalogState catalogState =
          new CatalogState(visibleWorlds, publicProductionCounts(visibleWorlds));
      return resolveStableWorld(selector, visibleWorlds)
          .filter(world -> hasValidPublicProductionRealm(world, catalogState));
    }
    List<GameplayAdmissionPointerSnapshot> pointers = readAuthorityPointers();
    if (pointers == null) {
      throw new AuthorityPointerReadUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (!hasCompleteAuthorityPointer(pointer)) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative gameplay pointer is incomplete");
      }
    }
    requireExactlyOneVisiblePublicProductionRealmPerTenant(pointers);
    CatalogState catalogState = catalogStateFromPointers(pointers);
    List<WorldView> visibleWorlds =
        catalogState.worlds().stream().filter(this::hasVisibleRealmEntries).toList();
    // This RPC receives a stable slug, not a response-local text-menu ordinal. In particular,
    // digit-only authored slugs must resolve by exact normalized slug rather than by list position.
    return resolveStableWorld(selector, visibleWorlds)
        .filter(world -> hasValidPublicProductionRealm(world, catalogState));
  }

  private Optional<WorldView> resolveStableWorld(String selector, List<WorldView> worlds) {
    String normalized = normalizeSlug(selector);
    List<WorldView> matches =
        worlds.stream().filter(world -> normalized.equals(normalizeSlug(world.slug()))).toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private Optional<WorldView> resolveWorld(
      String selector, List<WorldView> visibleWorlds, List<WorldView> indexedWorlds) {
    try {
      int index = Integer.parseInt(selector);
      if (index >= 1 && index <= indexedWorlds.size()) {
        return Optional.of(indexedWorlds.get(index - 1));
      }
    } catch (NumberFormatException ignored) {
      // Fall back to slug matching.
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<WorldView> matches =
        visibleWorlds.stream()
            .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RealmView> resolveRealm(WorldView world, String selector) {
    if (world == null || selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<RealmView> matches =
        visibleRealms(world).stream()
            .filter(realm -> normalized.equals(realm.slug().toLowerCase(Locale.ROOT)))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  // Admission may resolve hidden realms, but callers must still perform membership and grant
  // checks.
  Optional<RealmView> resolveRealmForAdmission(WorldView world, String selector) {
    if (world == null || selector == null || selector.isBlank() || world.realms() == null) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    List<RealmView> matches =
        world.realms().stream()
            .filter(realm -> realm != null && realm.slug() != null && !realm.slug().isBlank())
            .filter(realm -> normalized.equals(normalizeSlug(realm.slug())))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
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
    CatalogState catalogState = readCatalogState();
    return resolveDefaultRealm(world, catalogState);
  }

  private Optional<RealmView> resolveDefaultRealm(WorldView world, CatalogState catalogState) {
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
      return hasValidPublicProductionRealm(catalogState, publicRealm.tenantId())
          ? Optional.of(publicRealm)
          : Optional.empty();
    }
    return visibleRealms.stream()
            .allMatch(realm -> hasValidPublicProductionRealm(catalogState, realm.tenantId()))
        ? Optional.of(visibleRealms.getFirst())
        : Optional.empty();
  }

  private static String worldTargetFingerprint(WorldView world, RealmView defaultRealm) {
    return fingerprint(
        List.of(
            world.slug(),
            world.displayName(),
            Long.toString(defaultRealm.tenantId()),
            defaultRealm.slug(),
            Long.toString(defaultRealm.catalogRevision()),
            Long.toString(defaultRealm.pointerVersion()),
            defaultRealm.realmId() == null ? "" : defaultRealm.realmId().toString(),
            defaultRealm.playableStateNamespaceId() == null
                ? ""
                : defaultRealm.playableStateNamespaceId().toString(),
            Long.toString(defaultRealm.gameInstanceId()),
            Boolean.toString(defaultRealm.requiresCharacterSelection()),
            defaultRealm.stateScope() == null ? "" : defaultRealm.stateScope(),
            defaultRealm.characterCreationPolicy() == null
                ? ""
                : defaultRealm.characterCreationPolicy()));
  }

  private static String realmTargetFingerprint(WorldView world, RealmView realm) {
    return fingerprint(
        List.of(
            world.slug(),
            world.displayName(),
            realm.slug(),
            realm.displayName(),
            Long.toString(realm.tenantId()),
            Long.toString(realm.gameInstanceId()),
            Long.toString(realm.pointerVersion()),
            Long.toString(realm.catalogRevision()),
            realm.realmId() == null ? "" : realm.realmId().toString(),
            realm.playableStateNamespaceId() == null
                ? ""
                : realm.playableStateNamespaceId().toString(),
            Boolean.toString(realm.visible()),
            Boolean.toString(realm.publicProductionRealm()),
            Boolean.toString(realm.requiresCharacterSelection()),
            realm.stateScope() == null ? "" : realm.stateScope(),
            realm.characterCreationPolicy() == null ? "" : realm.characterCreationPolicy()));
  }

  private static String realmTargetFingerprintInput(RealmOrdinalTarget target) {
    return target.ordinal()
        + "|"
        + target.realmSlug()
        + "|"
        + target.tenantId()
        + "|"
        + target.catalogRevision()
        + "|"
        + target.pointerVersion()
        + "|"
        + target.targetFingerprint();
  }

  private static String targetFingerprintInput(WorldOrdinalTarget target) {
    return target.ordinal()
        + "|"
        + target.worldSlug()
        + "|"
        + target.tenantId()
        + "|"
        + target.catalogRevision()
        + "|"
        + target.targetFingerprint();
  }

  private static String fingerprint(List<String> values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  public record DiscoverySnapshot(
      WorldsViewOutput output,
      String catalogFingerprint,
      List<WorldOrdinalTarget> ordinalTargets,
      List<WorldView> visibleWorlds,
      Map<Long, Long> publicProductionCounts) {
    public DiscoverySnapshot {
      Objects.requireNonNull(output, "output must not be null");
      if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
        throw new IllegalArgumentException("catalogFingerprint must not be blank");
      }
      ordinalTargets =
          List.copyOf(Objects.requireNonNull(ordinalTargets, "ordinalTargets must not be null"));
      visibleWorlds =
          List.copyOf(Objects.requireNonNull(visibleWorlds, "visibleWorlds must not be null"));
      publicProductionCounts =
          Map.copyOf(
              Objects.requireNonNull(
                  publicProductionCounts, "publicProductionCounts must not be null"));
    }
  }

  public record RealmDiscoverySnapshot(
      String worldSlug, String catalogFingerprint, List<RealmOrdinalTarget> ordinalTargets) {
    public RealmDiscoverySnapshot {
      if (worldSlug == null || worldSlug.isBlank()) {
        throw new IllegalArgumentException("worldSlug must not be blank");
      }
      if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
        throw new IllegalArgumentException("catalogFingerprint must not be blank");
      }
      ordinalTargets =
          List.copyOf(Objects.requireNonNull(ordinalTargets, "ordinalTargets must not be null"));
    }
  }

  /**
   * Returns the tenant-wide cardinality of visible, player-addressable public-production realms.
   * The scan intentionally spans every authored world because a tenant may have candidates in more
   * than one world selector.
   */
  public PublicProductionRealmCardinality publicProductionRealmCardinality(long tenantId) {
    if (tenantId <= 0L) {
      return PublicProductionRealmCardinality.ZERO;
    }
    return cardinality(readCatalogState().publicProductionCounts().getOrDefault(tenantId, 0L));
  }

  private static PublicProductionRealmCardinality cardinality(long count) {
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

  /** Returns whether every tenant represented by this selected world has unambiguous authority. */
  public boolean hasValidPublicProductionRealm(WorldView world) {
    return hasValidPublicProductionRealm(world, readCatalogState());
  }

  private boolean hasValidPublicProductionRealm(WorldView world, CatalogState catalogState) {
    List<RealmView> visibleRealms = visibleRealms(world);
    return !visibleRealms.isEmpty()
        && visibleRealms.stream()
            .map(RealmView::tenantId)
            .distinct()
            .allMatch(tenantId -> hasValidPublicProductionRealm(catalogState, tenantId));
  }

  public boolean hasValidPublicProductionRealm(DiscoverySnapshot snapshot, WorldView world) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    List<RealmView> visibleRealms = visibleRealms(world);
    return !visibleRealms.isEmpty()
        && visibleRealms.stream()
            .map(RealmView::tenantId)
            .distinct()
            .allMatch(
                tenantId -> snapshot.publicProductionCounts().getOrDefault(tenantId, 0L) == 1L);
  }

  /**
   * Returns whether a world is represented by the public WORLDS discovery projection.
   *
   * <p>Private-only worlds remain in the catalog so an account with a matching grant can resolve
   * them for admission. Callers handling a selector must not use that broader admission catalog as
   * proof that an ungranted private world is discoverable.
   */
  public boolean isPubliclyDiscoverable(DiscoverySnapshot snapshot, WorldView world) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (world == null) {
      return false;
    }
    return hasPublicDiscoveryRealm(
        world, new CatalogState(snapshot.visibleWorlds(), snapshot.publicProductionCounts()));
  }

  public boolean requiresExplicitRealmSelection(WorldView world) {
    List<RealmView> publicRealms = publicVisibleRealms(world);
    return publicRealms.size() > 1 || (publicRealms.isEmpty() && visibleRealms(world).size() > 1);
  }

  public Optional<RealmView> resolveRealmByRuntimeTarget(long tenantId, long gameInstanceId) {
    List<RealmView> matches =
        readCatalogState().worlds().stream()
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.tenantId() == tenantId)
            .filter(realm -> realm.gameInstanceId() == gameInstanceId)
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RuntimeRealmTarget> resolveRuntimeTarget(long tenantId, long gameInstanceId) {
    List<RuntimeRealmTarget> matches =
        readCatalogState().worlds().stream()
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
    String normalizedWorld = normalizeSlug(worldSlug);
    String normalizedRealm = normalizeSlug(realmSlug);
    List<RuntimeRealmTarget> matches =
        visibleWorlds().stream()
            .filter(world -> normalizedWorld.equals(normalizeSlug(world.slug())))
            .flatMap(
                world ->
                    visibleRealms(world).stream()
                        .filter(realm -> normalizedRealm.equals(normalizeSlug(realm.slug())))
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
    return normalizedRealmViews(world.realms()).stream().filter(RealmView::visible).toList();
  }

  public List<RealmView> publicVisibleRealms(WorldView world) {
    return visibleRealms(world).stream().filter(RealmView::publicProductionRealm).toList();
  }

  public List<WorldView> visibleWorlds() {
    return readCatalogState().worlds().stream().filter(this::hasVisibleRealmEntries).toList();
  }

  /** Returns only worlds whose public browse projection has one unambiguous realm. */
  public List<WorldView> publicVisibleWorlds() {
    return discoverableWorlds(readCatalogState());
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
    List<GameplayAdmissionPointerSnapshot> pointers = readAuthorityPointers();
    if (pointers == null) {
      throw new AuthorityPointerReadUnavailableException(
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
    List<GameplayAdmissionPointerSnapshot> pointers = readAuthorityPointers();
    if (pointers == null) {
      throw new AuthorityPointerReadUnavailableException(
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

  /**
   * Re-reads the current routing authority and requires the selected realm to remain the exact
   * target that was resolved from the earlier catalog snapshot.
   *
   * <p>This is intentionally a read-before-admission fence, not an atomic read/bind operation. The
   * caller must invoke it immediately before any Account or Entity admission read.
   */
  public boolean matchesCurrentAdmissionPointer(WorldView expectedWorld, RealmView expectedRealm) {
    if (expectedWorld == null || expectedRealm == null) {
      return false;
    }
    if (authorityPointerSupplier != null) {
      List<GameplayAdmissionPointerSnapshot> pointers = readAuthorityPointers();
      if (pointers == null
          || pointers.stream().anyMatch(pointer -> !hasCompleteAuthorityPointer(pointer))) {
        return false;
      }
      if (pointers.stream()
              .filter(pointer -> pointer.tenantId() == expectedRealm.tenantId())
              .filter(GameplayAdmissionPointerSnapshot::visible)
              .filter(GameplayAdmissionPointerSnapshot::publicProductionRealm)
              .count()
          != 1L) {
        return false;
      }
      return pointers.stream()
              .filter(pointer -> matchesExpectedTarget(expectedWorld, expectedRealm, pointer))
              .count()
          == 1L;
    }

    CatalogState currentState = readCatalogState();
    if (currentState.publicProductionCounts().getOrDefault(expectedRealm.tenantId(), 0L) != 1L) {
      return false;
    }
    List<WorldView> currentWorlds = currentState.worlds();
    return currentWorlds.stream()
            .filter(world -> sameWorld(expectedWorld, world))
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.equals(expectedRealm))
            .count()
        == 1L;
  }

  private static boolean matchesExpectedTarget(
      WorldView expectedWorld,
      RealmView expectedRealm,
      GameplayAdmissionPointerSnapshot currentPointer) {
    return Objects.equals(expectedWorld.slug(), currentPointer.worldSlug())
        && Objects.equals(expectedWorld.displayName(), currentPointer.worldDisplayName())
        && toRealmView(currentPointer).equals(expectedRealm);
  }

  private static boolean sameWorld(WorldView expectedWorld, WorldView currentWorld) {
    return Objects.equals(expectedWorld.slug(), currentWorld.slug())
        && Objects.equals(expectedWorld.displayName(), currentWorld.displayName());
  }

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    CatalogState catalogState = readCatalogState();
    List<WorldView> worlds = discoverableWorlds(catalogState);
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(worlds.size());
    for (WorldView world : worlds) {
      RealmView defaultRealm = resolveDefaultRealm(world, catalogState).orElseThrow();
      entries.add(
          new WorldsViewOutput.WorldEntry(
              entries.size() + 1,
              world.slug(),
              world.displayName(),
              defaultRealm.requiresCharacterSelection()));
    }
    return List.copyOf(entries);
  }

  private List<WorldView> discoverableWorlds(CatalogState catalogState) {
    List<WorldView> catalogWorlds = catalogState.worlds();
    List<WorldView> visibleWorlds =
        catalogWorlds.stream().filter(this::hasVisibleRealmEntries).toList();
    return discoverableWorlds(visibleWorlds, catalogState);
  }

  private List<WorldView> discoverableWorlds(
      List<WorldView> visibleWorlds, CatalogState catalogState) {
    return visibleWorlds.stream()
        .filter(world -> hasPublicDiscoveryRealm(world, catalogState))
        .toList();
  }

  private boolean hasPublicDiscoveryRealm(WorldView world, CatalogState catalogState) {
    List<RealmView> publicProductionRealms =
        visibleRealms(world).stream().filter(RealmView::publicProductionRealm).toList();
    return publicProductionRealms.size() == 1
        && hasValidPublicProductionRealm(
            catalogState, publicProductionRealms.getFirst().tenantId());
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
              realm.requiresCharacterSelection(),
              realm.stateScope(),
              realm.characterCreationPolicy()));
    }
    return List.copyOf(entries);
  }

  private boolean hasVisibleRealmEntries(WorldView world) {
    return world != null && !visibleRealms(world).isEmpty();
  }

  private boolean hasValidPublicProductionRealm(CatalogState catalogState, long tenantId) {
    return tenantId > 0L && catalogState.publicProductionCounts().getOrDefault(tenantId, 0L) == 1L;
  }

  private CatalogState readCatalogState() {
    if (authorityPointerSupplier == null) {
      List<WorldView> suppliedWorlds = worldSupplier.get();
      List<WorldView> worlds = normalizeWorlds(suppliedWorlds);
      // Count authoritative rows before normalization removes case-colliding realm selectors.
      return new CatalogState(worlds, publicProductionCounts(suppliedWorlds));
    }
    List<GameplayAdmissionPointerSnapshot> pointers = readAuthorityPointers();
    if (pointers == null) {
      throw new AuthorityPointerReadUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    return catalogStateFromPointers(pointers);
  }

  private static CatalogState catalogStateFromPointers(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    return new CatalogState(toWorlds(pointers), publicProductionCountsFromPointers(pointers));
  }

  private List<GameplayAdmissionPointerSnapshot> readAuthorityPointers() {
    if (authorityPointerSupplier == null) {
      throw new AuthorityPointerReadUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    try {
      List<GameplayAdmissionPointerSnapshot> pointers = authorityPointerSupplier.get();
      if (pointers == null) {
        throw new AuthorityPointerReadUnavailableException(
            "Authoritative gameplay pointer list is unavailable");
      }
      return pointers;
    } catch (AuthorityPointerReadUnavailableException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new AuthorityPointerReadUnavailableException(
          "Authoritative gameplay pointer list is unavailable", ex);
    }
  }

  private Map<Long, Long> publicProductionCounts(List<WorldView> worlds) {
    Map<Long, Long> counts = new LinkedHashMap<>();
    if (worlds == null) {
      return Map.of();
    }
    for (WorldView world : worlds) {
      if (world == null || world.realms() == null) {
        continue;
      }
      for (RealmView realm : world.realms()) {
        if (realm != null
            && realm.visible()
            && realm.tenantId() > 0L
            && realm.publicProductionRealm()) {
          counts.merge(realm.tenantId(), 1L, Long::sum);
        }
      }
    }
    return Map.copyOf(counts);
  }

  private static Map<Long, Long> publicProductionCountsFromPointers(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Map<Long, Long> counts = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer != null
          && pointer.visible()
          && pointer.tenantId() > 0L
          && pointer.publicProductionRealm()) {
        counts.merge(pointer.tenantId(), 1L, Long::sum);
      }
    }
    return Map.copyOf(counts);
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

  private record CatalogState(List<WorldView> worlds, Map<Long, Long> publicProductionCounts) {
    private CatalogState {
      worlds = List.copyOf(Objects.requireNonNull(worlds, "worlds must not be null"));
      publicProductionCounts =
          Map.copyOf(
              Objects.requireNonNull(
                  publicProductionCounts, "publicProductionCounts must not be null"));
    }
  }

  private static List<WorldView> toWorlds(List<GameplayAdmissionPointerSnapshot> pointers) {
    List<GameplayAdmissionPointerSnapshot> sourcePointers =
        pointers == null ? List.of() : pointers.stream().filter(Objects::nonNull).toList();
    Map<Long, List<GameplayAdmissionPointerSnapshot>> visiblePublicPointersByTenant =
        sourcePointers.stream()
            .filter(GameplayAdmissionPointerSnapshot::visible)
            .filter(GameplayAdmissionPointerSnapshot::publicProductionRealm)
            .collect(Collectors.groupingBy(GameplayAdmissionPointerSnapshot::tenantId));
    Set<Long> tenantsWithOneCompleteVisiblePublicPointer =
        visiblePublicPointersByTenant.entrySet().stream()
            .filter(entry -> entry.getValue().size() == 1)
            .filter(entry -> hasCompleteAuthorityPointer(entry.getValue().getFirst()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    List<GameplayAdmissionPointerSnapshot> completePointers =
        sourcePointers.stream()
            .filter(GameplayWorldCatalog::hasCompleteAuthorityPointer)
            .filter(
                pointer -> tenantsWithOneCompleteVisiblePublicPointer.contains(pointer.tenantId()))
            .toList();
    Map<String, Set<Long>> tenantsByWorldSlug = new LinkedHashMap<>();
    Map<WorldIdentity, Set<String>> rawWorldSlugsByTenant = new LinkedHashMap<>();
    // Collisions must include rows later suppressed for tenant cardinality or completeness.
    List<GameplayAdmissionPointerSnapshot> selectorPointers =
        sourcePointers.stream()
            .filter(pointer -> pointer.tenantId() > 0L)
            .filter(pointer -> pointer.worldSlug() != null && !pointer.worldSlug().isBlank())
            .toList();
    for (GameplayAdmissionPointerSnapshot pointer : selectorPointers) {
      String normalizedWorldSlug = normalizeSlug(pointer.worldSlug());
      tenantsByWorldSlug
          .computeIfAbsent(normalizedWorldSlug, ignored -> new HashSet<>())
          .add(pointer.tenantId());
      rawWorldSlugsByTenant
          .computeIfAbsent(
              new WorldIdentity(pointer.tenantId(), normalizedWorldSlug),
              ignored -> new HashSet<>())
          .add(pointer.worldSlug());
    }

    Map<WorldIdentity, MutableWorldAccumulator> worlds = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : completePointers) {
      String normalizedWorldSlug = normalizeSlug(pointer.worldSlug());
      if (tenantsByWorldSlug.get(normalizedWorldSlug).size() > 1) {
        continue;
      }
      WorldIdentity key = new WorldIdentity(pointer.tenantId(), normalizedWorldSlug);
      if (rawWorldSlugsByTenant.get(key).size() > 1) {
        continue;
      }
      MutableWorldAccumulator world =
          worlds.computeIfAbsent(
              key,
              ignored ->
                  new MutableWorldAccumulator(pointer.worldSlug(), pointer.worldDisplayName()));
      world.tenantIds.add(pointer.tenantId());
      world
          .realmsBySlug
          .computeIfAbsent(normalizeSlug(pointer.realmSlug()), ignored -> new ArrayList<>())
          .add(pointer);
    }
    return normalizeWorlds(
        worlds.values().stream()
            .filter(world -> world.tenantIds.size() == 1)
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
    return slug.trim().toLowerCase(Locale.ROOT);
  }

  private static long worldTenantId(WorldView world) {
    List<Long> tenantIds = world.realms().stream().map(RealmView::tenantId).distinct().toList();
    return tenantIds.size() == 1 ? tenantIds.getFirst() : -1L;
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

  public static class AuthorityPointerUnavailableException extends RuntimeException {
    public AuthorityPointerUnavailableException(String message) {
      super(message);
    }
  }

  /** Indicates the authority read itself failed, distinct from malformed or ambiguous data. */
  public static final class AuthorityPointerReadUnavailableException
      extends AuthorityPointerUnavailableException {
    public AuthorityPointerReadUnavailableException(String message) {
      super(message);
    }

    public AuthorityPointerReadUnavailableException(String message, RuntimeException cause) {
      super(message);
      initCause(cause);
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
    return new WorldView(input.slug(), input.displayName(), normalizedRealmViews(input.realms()));
  }

  private static List<RealmView> normalizedRealmViews(List<RealmView> realms) {
    if (realms == null) {
      return List.of();
    }
    List<RealmView> copiedRealms =
        realms.stream()
            .filter(Objects::nonNull)
            .filter(realm -> realm.slug() != null && !realm.slug().isBlank())
            .map(GameplayWorldCatalog::copyRealmView)
            .toList();
    Set<String> seenSlugs = new HashSet<>();
    Set<String> ambiguousSlugs = new HashSet<>();
    for (RealmView realm : copiedRealms) {
      String normalizedSlug = normalizeSlug(realm.slug());
      if (!seenSlugs.add(normalizedSlug)) {
        ambiguousSlugs.add(normalizedSlug);
      }
    }
    return copiedRealms.stream()
        .filter(realm -> !ambiguousSlugs.contains(normalizeSlug(realm.slug())))
        .toList();
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
    private final Set<Long> tenantIds = new HashSet<>();
    private final Map<String, List<GameplayAdmissionPointerSnapshot>> realmsBySlug =
        new LinkedHashMap<>();

    private MutableWorldAccumulator(String slug, String displayName) {
      this.slug = slug;
      this.displayName = displayName;
    }
  }

  private record WorldIdentity(long tenantId, String normalizedWorldSlug) {}
}
