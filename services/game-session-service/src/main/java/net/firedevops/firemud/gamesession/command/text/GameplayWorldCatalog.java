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
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.RealmOrdinalTarget;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.WorldOrdinalTarget;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import org.jooq.exception.DataAccessException;
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
    CatalogState catalogState = readCatalogState();
    return buildDiscoverySnapshot(catalogState, visibleWorlds(catalogState)).output();
  }

  /** Reads a tenant-qualified snapshot for retained, response-local world ordinals. */
  public DiscoverySnapshot readDiscoverySnapshot() {
    CatalogState catalogState = readCatalogState();
    return buildDiscoverySnapshot(catalogState, qualifiedVisibleWorlds(catalogState));
  }

  private DiscoverySnapshot buildDiscoverySnapshot(
      CatalogState catalogState, List<WorldView> visibleWorlds) {
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

  /** Builds the public REALMS snapshot from the entries currently exposed in that response. */
  public RealmDiscoverySnapshot readRealmDiscoverySnapshot(WorldView world) {
    Objects.requireNonNull(world, "world must not be null");
    return realmDiscoverySnapshot(world, publicProductionRealms(world));
  }

  /** Builds a REALMS snapshot and fingerprint from the exact caller-visible response entries. */
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
    return new RealmDiscoverySnapshot(
        world.slug(), realmDiscoveryFingerprint(targets), List.copyOf(targets));
  }

  /**
   * Revalidates pointer identity for only the targets included in a caller's REALMS response.
   * Callers must still perform fresh membership, entitlement, and grant checks before admission.
   */
  public Optional<RealmDiscoverySnapshot> revalidateRealmDiscoverySnapshot(
      WorldView world, List<RealmOrdinalTarget> responseTargets) {
    Objects.requireNonNull(world, "world must not be null");
    List<RealmOrdinalTarget> safeResponseTargets =
        List.copyOf(Objects.requireNonNull(responseTargets, "responseTargets must not be null"));
    if (safeResponseTargets.isEmpty()) {
      return Optional.empty();
    }

    List<RealmView> currentRealms = visibleRealms(world);
    List<RealmOrdinalTarget> currentTargets = new ArrayList<>(safeResponseTargets.size());
    for (int index = 0; index < safeResponseTargets.size(); index++) {
      RealmOrdinalTarget responseTarget = safeResponseTargets.get(index);
      if (responseTarget.ordinal() != index + 1) {
        return Optional.empty();
      }
      List<RealmView> matches =
          currentRealms.stream()
              .filter(realm -> realm.slug().equalsIgnoreCase(responseTarget.realmSlug()))
              .filter(realm -> realm.tenantId() == responseTarget.tenantId())
              .toList();
      if (matches.size() != 1) {
        return Optional.empty();
      }
      RealmView realm = matches.getFirst();
      currentTargets.add(
          new RealmOrdinalTarget(
              responseTarget.ordinal(),
              realm.slug(),
              realm.tenantId(),
              realm.catalogRevision(),
              realm.pointerVersion(),
              realmTargetFingerprint(world, realm)));
    }
    return Optional.of(
        new RealmDiscoverySnapshot(
            world.slug(), realmDiscoveryFingerprint(currentTargets), List.copyOf(currentTargets)));
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
    List<WorldView> visibleWorlds = visibleWorlds(catalogState);
    return resolveWorld(selector, visibleWorlds, discoverableWorlds(visibleWorlds, catalogState));
  }

  public Optional<WorldView> resolveWorldFromAuthoritySnapshot(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    if (authorityPointerSupplier == null) {
      return resolveStableWorld(selector, visibleWorlds(readCatalogState()));
    }
    List<GameplayAdmissionPointerSnapshot> pointers = loadAuthorityPointers();
    Set<Long> invalidTenantIds = tenantsWithoutExactlyOneVisiblePublicProductionRealm(pointers);
    String selectedWorld = selectorKey(selector);
    boolean selectorExists =
        pointers.stream()
            .filter(pointer -> pointer.worldSlug() != null && !pointer.worldSlug().isBlank())
            .anyMatch(pointer -> selectedWorld.equals(selectorKey(pointer.worldSlug())));
    boolean selectedInvalidTenant =
        pointers.stream()
            .filter(pointer -> pointer.worldSlug() != null && !pointer.worldSlug().isBlank())
            .filter(pointer -> selectedWorld.equals(selectorKey(pointer.worldSlug())))
            .anyMatch(pointer -> invalidTenantIds.contains(pointer.tenantId()));
    if (selectedInvalidTenant) {
      return Optional.empty();
    }
    if (!invalidTenantIds.isEmpty() && !selectorExists) {
      requireExactlyOneVisiblePublicProductionRealmPerTenant(pointers);
    }
    CatalogState snapshot = catalogStateFromPointers(pointers);
    return resolveStableWorld(selector, visibleWorlds(snapshot));
  }

  /** Builds the no-caller discovery projection from one validated authority snapshot. */
  public List<WorldView> publicWorldsFromAuthoritySnapshot() {
    if (authorityPointerSupplier == null) {
      return unambiguousTenantWorlds(publicDiscoveryWorlds(loadAuthorityWorldSnapshot()));
    }
    return publicWorldsFromAuthoritySnapshot(loadAuthorityPointers());
  }

  /** Builds the no-caller discovery projection from the supplied authoritative snapshot. */
  public List<WorldView> publicWorldsFromAuthoritySnapshot(
      List<GameplayAdmissionPointerSnapshot> pointerSnapshot) {
    List<GameplayAdmissionPointerSnapshot> validated = validateAuthoritySnapshot(pointerSnapshot);
    return unambiguousTenantWorlds(publicDiscoveryWorlds(catalogStateFromPointers(validated)));
  }

  /** Resolves only worlds exposed by the no-caller public discovery surface. */
  public Optional<WorldView> resolvePublicWorldFromAuthoritySnapshot(String selector) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    if (authorityPointerSupplier == null) {
      return resolveStableWorld(selector, publicWorldsFromAuthoritySnapshot());
    }
    return resolvePublicWorldFromAuthoritySnapshot(selector, loadAuthorityPointers());
  }

  /** Resolves a stable world slug against the supplied authoritative snapshot. */
  public Optional<WorldView> resolvePublicWorldFromAuthoritySnapshot(
      String selector, List<GameplayAdmissionPointerSnapshot> pointerSnapshot) {
    if (selector == null || selector.isBlank()) {
      return Optional.empty();
    }
    List<GameplayAdmissionPointerSnapshot> pointers = validateAuthoritySnapshot(pointerSnapshot);
    Set<Long> invalidTenantIds = tenantsWithoutExactlyOneVisiblePublicProductionRealm(pointers);
    String selectedWorld = selectorKey(selector);
    if (ambiguousWorldSelectors(pointers).contains(selectedWorld)) {
      return Optional.empty();
    }
    boolean selectedInvalidTenant =
        pointers.stream()
            .filter(Objects::nonNull)
            .filter(pointer -> pointer.worldSlug() != null && !pointer.worldSlug().isBlank())
            .filter(pointer -> selectedWorld.equals(selectorKey(pointer.worldSlug())))
            .anyMatch(pointer -> invalidTenantIds.contains(pointer.tenantId()));
    if (selectedInvalidTenant) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm count is invalid for selected world");
    }
    if (!invalidTenantIds.isEmpty()) {
      boolean selectorExists =
          pointers.stream()
              .filter(Objects::nonNull)
              .filter(pointer -> pointer.worldSlug() != null && !pointer.worldSlug().isBlank())
              .anyMatch(pointer -> selectedWorld.equals(selectorKey(pointer.worldSlug())));
      if (!selectorExists) {
        requireExactlyOneVisiblePublicProductionRealmPerTenant(pointers);
      }
    }
    CatalogState snapshot = catalogStateFromPointers(pointers);
    // This RPC accepts a stable slug, not a response-local menu ordinal. Digit-only authored
    // slugs resolve by exact normalized slug rather than by list position.
    return resolveStableWorld(selector, unambiguousTenantWorlds(publicDiscoveryWorlds(snapshot)));
  }

  private Optional<WorldView> resolveStableWorld(String selector, List<WorldView> worlds) {
    String normalized = selectorKey(selector);
    List<WorldView> matches =
        worlds.stream().filter(world -> normalized.equals(selectorKey(world.slug()))).toList();
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
    return resolveWorldBySlug(selector, visibleWorlds);
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

  private static String realmDiscoveryFingerprint(List<RealmOrdinalTarget> targets) {
    return fingerprint(
        targets.stream().map(GameplayWorldCatalog::realmTargetFingerprintInput).toList());
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
    if (authorityPointerSupplier != null) {
      return cardinality(
          publicProductionCountsFromPointers(validateAuthoritySnapshot(readAuthorityPointers()))
              .getOrDefault(tenantId, 0L));
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
    CatalogState snapshot = readCatalogState();
    List<RealmView> matches =
        snapshot.worlds().stream()
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.tenantId() == tenantId)
            .filter(realm -> realm.gameInstanceId() == gameInstanceId)
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public Optional<RuntimeRealmTarget> resolveRuntimeTarget(long tenantId, long gameInstanceId) {
    CatalogState snapshot = readCatalogState();
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
        visibleWorlds(readCatalogState()).stream()
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
    return normalizedRealmViews(world.realms()).stream().filter(RealmView::visible).toList();
  }

  public List<RealmView> publicVisibleRealms(WorldView world) {
    return visibleRealms(world).stream().filter(RealmView::publicProductionRealm).toList();
  }

  private List<RealmView> publicProductionRealms(WorldView world) {
    return visibleRealms(world).stream().filter(RealmView::publicProductionRealm).toList();
  }

  public List<WorldView> visibleWorlds() {
    return visibleWorlds(readCatalogState());
  }

  /** Returns only worlds whose public browse projection has one unambiguous realm. */
  public List<WorldView> publicVisibleWorlds() {
    return unambiguousTenantWorlds(publicDiscoveryWorlds(readCatalogState()));
  }

  /** Builds the visible world projection from one validated authoritative pointer snapshot. */
  public List<WorldView> visibleWorldsFromAuthoritySnapshot() {
    if (authorityPointerSupplier == null) {
      return visibleWorlds();
    }
    return unambiguousTenantWorlds(visibleWorlds(loadAuthorityWorldSnapshot()));
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
    requireUniqueVisiblePublicProductionRealm(expectedPointer, pointers);
  }

  /** Validates one tenant's complete catalog from an already-read authority snapshot. */
  public void requireHealthyPointerCatalog(
      List<GameplayAdmissionPointerSnapshot> pointerSnapshot, long tenantId) {
    List<GameplayAdmissionPointerSnapshot> pointers =
        tenantPointersFromSnapshot(pointerSnapshot, tenantId);
    if (!tenantHasValidPublicProductionAuthority(pointers, tenantId)) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm is incomplete or ambiguous for tenant "
              + tenantId);
    }
  }

  /** Validates a public target against one previously-read authority snapshot. */
  public void requireUniqueVisiblePublicProductionRealm(
      GameplayAdmissionPointerSnapshot expectedPointer,
      List<GameplayAdmissionPointerSnapshot> pointerSnapshot) {
    if (expectedPointer == null || expectedPointer.tenantId() <= 0L) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm identity is unavailable");
    }
    List<GameplayAdmissionPointerSnapshot> pointers =
        tenantPointersFromSnapshot(pointerSnapshot, expectedPointer.tenantId());
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
    if (tenantAuthorityPointerSupplier != null) {
      try {
        List<GameplayAdmissionPointerSnapshot> pointers =
            loadAuthorityPointersForTenant(expectedRealm.tenantId());
        if (!tenantHasValidPublicProductionAuthority(pointers, expectedRealm.tenantId())) {
          return false;
        }
        List<GameplayAdmissionPointerSnapshot> selected =
            pointers.stream()
                .filter(pointer -> matchesExpectedTarget(expectedWorld, expectedRealm, pointer))
                .toList();
        if (selected.size() != 1) {
          return false;
        }
        requireCollisionSafeVisiblePointer(pointers, selected.getFirst());
        return true;
      } catch (AuthorityPointerReadUnavailableException ex) {
        throw ex;
      } catch (AuthorityPointerUnavailableException ex) {
        return false;
      }
    }
    if (authorityPointerSupplier != null) {
      List<GameplayAdmissionPointerSnapshot> pointers = readAuthorityPointers();
      if (pointers.stream().anyMatch(pointer -> !hasCompleteAuthorityPointer(pointer))) {
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
    return currentState.worlds().stream()
            .filter(world -> sameWorld(expectedWorld, world))
            .flatMap(world -> world.realms().stream())
            .filter(expectedRealm::equals)
            .count()
        == 1L;
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
    requireHealthyVisiblePrivateRealm(expectedPointer, pointers);
  }

  /** Validates a visible private target against one previously-read authority snapshot. */
  public void requireHealthyVisiblePrivateRealm(
      GameplayAdmissionPointerSnapshot expectedPointer,
      List<GameplayAdmissionPointerSnapshot> pointerSnapshot) {
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
        tenantPointersFromSnapshot(pointerSnapshot, expectedPointer.tenantId());
    if (!tenantHasValidPublicProductionAuthority(pointers, expectedPointer.tenantId())) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm is incomplete or ambiguous for tenant "
              + expectedPointer.tenantId());
    }
    List<GameplayAdmissionPointerSnapshot> selectedRealms =
        pointers.stream()
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

  private static List<GameplayAdmissionPointerSnapshot> tenantPointersFromSnapshot(
      List<GameplayAdmissionPointerSnapshot> pointerSnapshot, long tenantId) {
    if (tenantId <= 0L || pointerSnapshot == null) {
      throw new AuthorityPointerUnavailableException(
          "Authoritative gameplay pointer snapshot is unavailable");
    }
    List<GameplayAdmissionPointerSnapshot> tenantPointers = new ArrayList<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointerSnapshot) {
      if (pointer == null || pointer.tenantId() <= 0L) {
        throw new AuthorityPointerUnavailableException(
            "Authoritative gameplay pointer identity is unavailable");
      }
      if (pointer.tenantId() == tenantId) {
        tenantPointers.add(pointer);
      }
    }
    return List.copyOf(tenantPointers);
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
    List<GameplayAdmissionPointerSnapshot> pointers;
    try {
      pointers = tenantAuthorityPointerSupplier.apply(tenantId);
    } catch (AuthorityPointerReadUnavailableException ex) {
      throw ex;
    } catch (AuthorityPointerUnavailableException ex) {
      throw ex;
    } catch (DataAccessException ex) {
      throw new AuthorityPointerReadUnavailableException(
          "Authoritative tenant gameplay pointer list is unavailable", ex);
    }
    if (pointers == null) {
      throw new AuthorityPointerReadUnavailableException(
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

  private List<WorldView> publicDiscoveryWorlds(CatalogState snapshot) {
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
                                cardinality(
                                        snapshot
                                            .publicProductionCounts()
                                            .getOrDefault(realm.tenantId(), 0L))
                                    == PublicProductionRealmCardinality.EXACTLY_ONE)
                        .toList()))
        .filter(world -> world.realms().size() == 1)
        .toList();
  }

  private static List<WorldView> unambiguousTenantWorlds(List<WorldView> worlds) {
    Map<String, Set<Long>> tenantsBySelector = new HashMap<>();
    for (WorldView world : worlds) {
      tenantsBySelector
          .computeIfAbsent(selectorKey(world.slug()), ignored -> new HashSet<>())
          .add(worldTenantId(world));
    }
    Set<String> ambiguousSelectors =
        tenantsBySelector.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    return worlds.stream()
        .filter(world -> !ambiguousSelectors.contains(selectorKey(world.slug())))
        .toList();
  }

  private boolean hasValidPublicProductionRealm(CatalogState catalogState, long tenantId) {
    return tenantId > 0L && catalogState.publicProductionCounts().getOrDefault(tenantId, 0L) == 1L;
  }

  private CatalogState readCatalogState() {
    if (authorityPointerSupplier == null) {
      List<WorldView> suppliedWorlds = worldSupplier.get();
      List<WorldView> worlds = normalizeWorlds(suppliedWorlds);
      // Count authoritative rows before normalization removes case-colliding realm selectors.
      Map<Long, Long> publicCounts = publicProductionCounts(suppliedWorlds);
      return new CatalogState(
          worlds,
          publicCounts,
          ambiguousWorldSelectorsFromWorlds(suppliedWorlds),
          ambiguousWorldSelectorsWithUnhealthyTenantsFromWorlds(suppliedWorlds, publicCounts));
    }
    return catalogStateFromPointers(loadAuthorityPointers());
  }

  private static CatalogState catalogStateFromPointers(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    List<GameplayAdmissionPointerSnapshot> validated = validateAuthoritySnapshot(pointers);
    Set<String> ambiguousSelectors = ambiguousWorldSelectors(validated);
    Set<Long> unhealthyTenantIds = tenantsWithoutExactlyOneVisiblePublicProductionRealm(validated);
    List<GameplayAdmissionPointerSnapshot> healthyPointers =
        validated.stream()
            .filter(pointer -> !unhealthyTenantIds.contains(pointer.tenantId()))
            .toList();
    return new CatalogState(
        toWorlds(healthyPointers),
        publicProductionCountsFromPointers(healthyPointers),
        ambiguousSelectors,
        ambiguousWorldSelectorsWithUnhealthyTenants(validated, unhealthyTenantIds));
  }

  private List<GameplayAdmissionPointerSnapshot> readAuthorityPointers() {
    if (authorityPointerSupplier == null) {
      throw new AuthorityPointerReadUnavailableException(
          "Authoritative gameplay pointer list is unavailable");
    }
    return readAuthorityPointers(authorityPointerSupplier);
  }

  private static List<GameplayAdmissionPointerSnapshot> readAuthorityPointers(
      Supplier<List<GameplayAdmissionPointerSnapshot>> pointerSupplier) {
    try {
      List<GameplayAdmissionPointerSnapshot> pointers = pointerSupplier.get();
      if (pointers == null) {
        throw new AuthorityPointerReadUnavailableException(
            "Authoritative gameplay pointer list is unavailable");
      }
      return pointers;
    } catch (AuthorityPointerReadUnavailableException ex) {
      throw ex;
    } catch (AuthorityPointerUnavailableException ex) {
      throw ex;
    } catch (DataAccessException ex) {
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

  private static boolean isPlayerAddressable(GameplayAdmissionPointerSnapshot pointer) {
    return pointer != null
        && pointer.visible()
        && pointer.worldSlug() != null
        && !pointer.worldSlug().isBlank()
        && pointer.realmSlug() != null
        && !pointer.realmSlug().isBlank()
        && pointer.tenantId() > 0L;
  }

  private CatalogState loadAuthorityWorldSnapshot() {
    if (authorityPointerSupplier == null) {
      return readCatalogState();
    }
    return catalogStateFromPointers(loadAuthorityPointers());
  }

  private List<WorldView> visibleWorlds(CatalogState snapshot) {
    return snapshot.worlds().stream()
        .filter(world -> !snapshot.ambiguousWorldSelectors().contains(selectorKey(world.slug())))
        .filter(this::hasVisibleRealmEntries)
        .toList();
  }

  private List<WorldView> qualifiedVisibleWorlds(CatalogState snapshot) {
    return snapshot.worlds().stream()
        .filter(
            world ->
                !snapshot
                    .ambiguousWorldSelectorsWithUnhealthyTenants()
                    .contains(selectorKey(world.slug())))
        .filter(this::hasVisibleRealmEntries)
        .toList();
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
    List<WorldView> normalizedWorlds =
        worlds.stream()
            .filter(Objects::nonNull)
            .filter(world -> world.slug() != null && !world.slug().isBlank())
            .map(GameplayWorldCatalog::copyWorldView)
            .toList();
    Map<WorldIdentity, Integer> worldCounts = new HashMap<>();
    Map<WorldIdentity, Set<String>> worldSpellings = new HashMap<>();
    for (WorldView world : normalizedWorlds) {
      WorldIdentity key = worldIdentity(world);
      worldCounts.merge(key, 1, Integer::sum);
      worldSpellings.computeIfAbsent(key, ignored -> new HashSet<>()).add(world.slug());
    }
    Set<WorldIdentity> ambiguousWorlds = new HashSet<>();
    worldCounts.forEach(
        (key, count) -> {
          if (count > 1 || worldSpellings.get(key).size() > 1 || key.tenantId() <= 0L) {
            ambiguousWorlds.add(key);
          }
        });
    return normalizedWorlds.stream()
        .filter(world -> !ambiguousWorlds.contains(worldIdentity(world)))
        .toList();
  }

  private record CatalogState(
      List<WorldView> worlds,
      Map<Long, Long> publicProductionCounts,
      Set<String> ambiguousWorldSelectors,
      Set<String> ambiguousWorldSelectorsWithUnhealthyTenants) {
    private CatalogState(List<WorldView> worlds, Map<Long, Long> publicProductionCounts) {
      this(
          worlds,
          publicProductionCounts,
          ambiguousWorldSelectorsFromWorlds(worlds),
          ambiguousWorldSelectorsWithUnhealthyTenantsFromWorlds(worlds, publicProductionCounts));
    }

    private CatalogState {
      worlds = List.copyOf(Objects.requireNonNull(worlds, "worlds must not be null"));
      publicProductionCounts =
          Map.copyOf(
              Objects.requireNonNull(
                  publicProductionCounts, "publicProductionCounts must not be null"));
      ambiguousWorldSelectors =
          Set.copyOf(
              Objects.requireNonNull(
                  ambiguousWorldSelectors, "ambiguousWorldSelectors must not be null"));
      ambiguousWorldSelectorsWithUnhealthyTenants =
          Set.copyOf(
              Objects.requireNonNull(
                  ambiguousWorldSelectorsWithUnhealthyTenants,
                  "ambiguousWorldSelectorsWithUnhealthyTenants must not be null"));
    }
  }

  private static List<WorldView> toWorlds(List<GameplayAdmissionPointerSnapshot> pointers) {
    List<GameplayAdmissionPointerSnapshot> sourcePointers =
        pointers == null ? List.of() : pointers.stream().filter(Objects::nonNull).toList();
    Map<WorldIdentity, Set<String>> worldSpellings = new HashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : sourcePointers) {
      if (hasCompleteAuthorityPointer(pointer)) {
        WorldIdentity identity = worldIdentity(pointer);
        worldSpellings
            .computeIfAbsent(identity, ignored -> new HashSet<>())
            .add(pointer.worldSlug());
      }
    }
    Set<WorldIdentity> ambiguousWorldSlugs =
        worldSpellings.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());

    Map<WorldIdentity, MutableWorldAccumulator> worlds = new LinkedHashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : sourcePointers) {
      if (!hasCompleteAuthorityPointer(pointer)
          || ambiguousWorldSlugs.contains(worldIdentity(pointer))) {
        continue;
      }
      MutableWorldAccumulator world =
          worlds.computeIfAbsent(
              worldIdentity(pointer),
              ignored ->
                  new MutableWorldAccumulator(pointer.worldSlug(), pointer.worldDisplayName()));
      world.tenantIds.add(pointer.tenantId());
      world
          .realmsBySlug
          .computeIfAbsent(selectorKey(pointer.realmSlug()), ignored -> new ArrayList<>())
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

  private static WorldIdentity worldIdentity(GameplayAdmissionPointerSnapshot pointer) {
    return new WorldIdentity(pointer.tenantId(), selectorKey(pointer.worldSlug()));
  }

  private static long worldTenantId(WorldView world) {
    List<Long> tenantIds = world.realms().stream().map(RealmView::tenantId).distinct().toList();
    return tenantIds.size() == 1 ? tenantIds.getFirst() : -1L;
  }

  private static WorldIdentity worldIdentity(WorldView world) {
    return new WorldIdentity(worldTenantId(world), selectorKey(world.slug()));
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

  private static void requireExactlyOneVisiblePublicProductionRealmPerTenant(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Set<Long> invalidTenantIds = tenantsWithoutExactlyOneVisiblePublicProductionRealm(pointers);
    if (!invalidTenantIds.isEmpty()) {
      long invalidTenantId = invalidTenantIds.stream().sorted().findFirst().orElseThrow();
      throw new AuthorityPointerUnavailableException(
          "Authoritative public-production realm count is invalid for tenant " + invalidTenantId);
    }
  }

  private static List<GameplayAdmissionPointerSnapshot> healthyTenantPointers(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Set<Long> invalidTenantIds = tenantsWithoutExactlyOneVisiblePublicProductionRealm(pointers);
    return pointers.stream()
        .filter(pointer -> !invalidTenantIds.contains(pointer.tenantId()))
        .toList();
  }

  private static Set<String> ambiguousWorldSelectors(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    Map<String, Set<Long>> tenantsBySelector = new HashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer.worldSlug() == null || pointer.worldSlug().isBlank()) {
        continue;
      }
      tenantsBySelector
          .computeIfAbsent(selectorKey(pointer.worldSlug()), ignored -> new HashSet<>())
          .add(pointer.tenantId());
    }
    return tenantsBySelector.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> ambiguousWorldSelectorsFromWorlds(List<WorldView> worlds) {
    Map<String, Set<Long>> tenantsBySelector = new HashMap<>();
    for (WorldView world : worlds) {
      if (world == null || world.slug() == null || world.slug().isBlank()) {
        continue;
      }
      if (world.realms() == null) {
        continue;
      }
      world.realms().stream()
          .filter(Objects::nonNull)
          .map(RealmView::tenantId)
          .filter(tenantId -> tenantId > 0L)
          .forEach(
              tenantId ->
                  tenantsBySelector
                      .computeIfAbsent(selectorKey(world.slug()), ignored -> new HashSet<>())
                      .add(tenantId));
    }
    return tenantsBySelector.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> ambiguousWorldSelectorsWithUnhealthyTenants(
      List<GameplayAdmissionPointerSnapshot> pointers, Set<Long> unhealthyTenantIds) {
    Map<String, Set<Long>> tenantsBySelector = new HashMap<>();
    for (GameplayAdmissionPointerSnapshot pointer : pointers) {
      if (pointer.worldSlug() == null || pointer.worldSlug().isBlank()) {
        continue;
      }
      tenantsBySelector
          .computeIfAbsent(selectorKey(pointer.worldSlug()), ignored -> new HashSet<>())
          .add(pointer.tenantId());
    }
    return tenantsBySelector.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .filter(entry -> entry.getValue().stream().anyMatch(unhealthyTenantIds::contains))
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> ambiguousWorldSelectorsWithUnhealthyTenantsFromWorlds(
      List<WorldView> worlds, Map<Long, Long> publicProductionCounts) {
    Map<String, Set<Long>> tenantsBySelector = new HashMap<>();
    for (WorldView world : worlds) {
      if (world == null
          || world.slug() == null
          || world.slug().isBlank()
          || world.realms() == null) {
        continue;
      }
      world.realms().stream()
          .filter(Objects::nonNull)
          .map(RealmView::tenantId)
          .filter(tenantId -> tenantId > 0L)
          .forEach(
              tenantId ->
                  tenantsBySelector
                      .computeIfAbsent(selectorKey(world.slug()), ignored -> new HashSet<>())
                      .add(tenantId));
    }
    return tenantsBySelector.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .filter(
            entry ->
                entry.getValue().stream()
                    .anyMatch(tenantId -> publicProductionCounts.getOrDefault(tenantId, 0L) != 1L))
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
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
    return validateAuthoritySnapshot(readAuthorityPointers(authorityService::listPointers));
  }

  private List<GameplayAdmissionPointerSnapshot> loadAuthorityPointers() {
    return validateAuthoritySnapshot(readAuthorityPointers());
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
    List<RealmView> realms = normalizedRealmViews(input.realms());
    return new WorldView(input.slug(), input.displayName(), realms);
  }

  private static List<RealmView> normalizedRealmViews(List<RealmView> input) {
    List<RealmView> realms =
        input == null
            ? List.of()
            : input.stream()
                .filter(Objects::nonNull)
                .filter(realm -> realm.slug() != null && !realm.slug().isBlank())
                .map(GameplayWorldCatalog::copyRealmView)
                .toList();
    Map<RealmSelectorKey, Integer> realmCounts = new HashMap<>();
    for (RealmView realm : realms) {
      realmCounts.merge(
          new RealmSelectorKey(realm.tenantId(), selectorKey(realm.slug())), 1, Integer::sum);
    }
    return realms.stream()
        .filter(
            realm ->
                realmCounts.get(new RealmSelectorKey(realm.tenantId(), selectorKey(realm.slug())))
                    == 1)
        .toList();
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
