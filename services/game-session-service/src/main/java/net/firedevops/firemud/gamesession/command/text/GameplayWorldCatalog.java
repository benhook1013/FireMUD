package net.firedevops.firemud.gamesession.command.text;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    return readDiscoverySnapshot().output();
  }

  /** Reads the catalog once and builds the exact public WORLDS projection and ordinal authority. */
  public DiscoverySnapshot readDiscoverySnapshot() {
    List<WorldView> catalogWorlds = normalizeWorlds(worldSupplier.get());
    List<WorldView> visibleWorlds =
        catalogWorlds.stream().filter(this::hasVisibleRealmEntries).toList();
    List<WorldView> discoverableWorlds =
        visibleWorlds.stream()
            .filter(world -> hasPublicDiscoveryRealm(world, catalogWorlds))
            .toList();
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(discoverableWorlds.size());
    ArrayList<WorldOrdinalTarget> targets = new ArrayList<>(discoverableWorlds.size());
    for (WorldView world : discoverableWorlds) {
      RealmView defaultRealm = resolveDefaultRealm(world, catalogWorlds).orElseThrow();
      int ordinal = entries.size() + 1;
      entries.add(
          new WorldsViewOutput.WorldEntry(
              ordinal,
              world.slug(),
              world.displayName(),
              defaultRealm.gameInstanceId(),
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
        visibleWorlds);
  }

  public Optional<WorldView> resolveStableWorld(DiscoverySnapshot snapshot, String selector) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (selector == null || selector.isBlank() || isOrdinalSelector(selector)) {
      return Optional.empty();
    }
    String normalized = selector.trim().toLowerCase(Locale.ROOT);
    return snapshot.visibleWorlds().stream()
        .filter(world -> normalized.equals(world.slug().toLowerCase(Locale.ROOT)))
        .findFirst();
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
    return current.visibleWorlds().stream()
        .filter(world -> world.slug().equalsIgnoreCase(originatingTarget.worldSlug()))
        .filter(
            world ->
                world.realms().stream()
                    .filter(RealmView::visible)
                    .anyMatch(realm -> realm.tenantId() == originatingTarget.tenantId()))
        .findFirst();
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
    return resolveWorld(worldSelector)
        .map(world -> new RealmBrowseViewOutput(world.slug(), realmEntries(world)));
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
    return visibleRealms(world).stream()
        .filter(realm -> realm.slug().equalsIgnoreCase(originatingTarget.realmSlug()))
        .filter(realm -> realm.tenantId() == originatingTarget.tenantId())
        .filter(realm -> realm.catalogRevision() == originatingTarget.catalogRevision())
        .filter(realm -> realm.pointerVersion() == originatingTarget.pointerVersion())
        .filter(
            realm ->
                realmTargetFingerprint(world, realm).equals(originatingTarget.targetFingerprint()))
        .findFirst();
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
    return resolveDefaultRealm(world, normalizeWorlds(worldSupplier.get()));
  }

  private Optional<RealmView> resolveDefaultRealm(WorldView world, List<WorldView> catalogWorlds) {
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
      return hasValidPublicProductionRealm(catalogWorlds, publicRealm.tenantId())
          ? Optional.of(publicRealm)
          : Optional.empty();
    }
    return visibleRealms.stream()
            .allMatch(realm -> hasValidPublicProductionRealm(catalogWorlds, realm.tenantId()))
        ? Optional.of(visibleRealms.getFirst())
        : Optional.empty();
  }

  private boolean hasValidPublicProductionRealm(List<WorldView> catalogWorlds, long tenantId) {
    long count =
        catalogWorlds.stream()
            .flatMap(world -> world.realms().stream())
            .filter(realm -> realm.tenantId() == tenantId)
            .filter(this::isPlayerAddressable)
            .filter(RealmView::publicProductionRealm)
            .count();
    return count == 1L;
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
      List<WorldView> visibleWorlds) {
    public DiscoverySnapshot {
      Objects.requireNonNull(output, "output must not be null");
      if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
        throw new IllegalArgumentException("catalogFingerprint must not be blank");
      }
      ordinalTargets =
          List.copyOf(Objects.requireNonNull(ordinalTargets, "ordinalTargets must not be null"));
      visibleWorlds =
          List.copyOf(Objects.requireNonNull(visibleWorlds, "visibleWorlds must not be null"));
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

  /** Returns whether every tenant represented by this selected world has unambiguous authority. */
  public boolean hasValidPublicProductionRealm(WorldView world) {
    List<RealmView> visibleRealms = visibleRealms(world);
    return !visibleRealms.isEmpty()
        && visibleRealms.stream()
            .map(RealmView::tenantId)
            .distinct()
            .allMatch(this::hasValidPublicProductionRealm);
  }

  public boolean hasValidPublicProductionRealm(DiscoverySnapshot snapshot, WorldView world) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    List<RealmView> visibleRealms = visibleRealms(world);
    return !visibleRealms.isEmpty()
        && visibleRealms.stream()
            .map(RealmView::tenantId)
            .distinct()
            .allMatch(
                tenantId -> hasValidPublicProductionRealm(snapshot.visibleWorlds(), tenantId));
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

  private List<WorldsViewOutput.WorldEntry> worldEntries() {
    List<WorldView> worlds = discoverableWorlds();
    ArrayList<WorldsViewOutput.WorldEntry> entries = new ArrayList<>(worlds.size());
    for (WorldView world : worlds) {
      RealmView defaultRealm = resolveDefaultRealm(world).orElseThrow();
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

  private List<WorldView> discoverableWorlds() {
    List<WorldView> catalogWorlds = normalizeWorlds(worldSupplier.get());
    return catalogWorlds.stream()
        .filter(this::hasVisibleRealmEntries)
        .filter(world -> hasPublicDiscoveryRealm(world, catalogWorlds))
        .toList();
  }

  private boolean hasPublicDiscoveryRealm(WorldView world, List<WorldView> catalogWorlds) {
    List<RealmView> publicProductionRealms =
        visibleRealms(world).stream().filter(RealmView::publicProductionRealm).toList();
    return publicProductionRealms.size() == 1
        && hasValidPublicProductionRealm(
            catalogWorlds, publicProductionRealms.getFirst().tenantId());
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

  private boolean isPlayerAddressable(RealmView realm) {
    return realm != null
        && realm.visible()
        && realm.slug() != null
        && !realm.slug().isBlank()
        && realm.tenantId() > 0L;
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
              pointer.worldSlug().toLowerCase(Locale.ROOT),
              ignored ->
                  new MutableWorldAccumulator(pointer.worldSlug(), pointer.worldDisplayName()));
      world.tenantIds.add(pointer.tenantId());
      world
          .realmsBySlug
          .computeIfAbsent(pointer.realmSlug(), ignored -> new ArrayList<>())
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
}
