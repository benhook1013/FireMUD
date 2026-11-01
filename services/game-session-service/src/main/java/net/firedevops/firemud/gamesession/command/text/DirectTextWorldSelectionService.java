package net.firedevops.firemud.gamesession.command.text;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.InvalidAuthorityException;

/**
 * Manually composed, unregistered public world selection preparation. This observational read port
 * neither authenticates a caller nor supplies an atomic Account commit checkpoint. A future caller
 * must retain each returned menu on its own transport; menu expiry is only UI selection validity.
 */
public final class DirectTextWorldSelectionService {
  private static final Duration MENU_LIFETIME = Duration.ofMinutes(5);

  private final GameplayWorldCatalog catalog;
  private final CanonicalPlayerRouteReadService routeReader;
  private final Clock clock;

  public DirectTextWorldSelectionService(
      GameplayWorldCatalog catalog, CanonicalPlayerRouteReadService routeReader, Clock clock) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.routeReader = Objects.requireNonNull(routeReader, "routeReader");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Joins the existing guarded public vector, without widening its omissions. Reachable invalid or
   * changing evidence requires fresh discovery; unavailable source exceptions deliberately escape.
   */
  public Optional<SelectionSnapshot> browse() {
    Instant createdAt = clock.instant();
    Instant expiresAt = createdAt.plus(MENU_LIFETIME);
    return compose(createdAt, expiresAt).map(snapshot -> new SelectionSnapshot(this, snapshot));
  }

  /** Encodes only this instance's genuine handle as bounded, nonsecret storage evidence. */
  public Optional<byte[]> encodeSnapshot(SelectionSnapshot retained) {
    if (retained == null || retained.owner != this) {
      return Optional.empty();
    }
    try {
      return Optional.of(DirectTextWorldSelectionSnapshotCodec.encode(retained.snapshot));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  /** Owner-clock liveness for the menu store's immediate browse result, without source I/O. */
  Optional<byte[]> encodeLiveSnapshot(SelectionSnapshot justDiscovered) {
    if (justDiscovered == null
        || justDiscovered.owner != this
        || !isLive(justDiscovered.snapshot)) {
      return Optional.empty();
    }
    return encodeSnapshot(justDiscovered);
  }

  /**
   * Restores the same original observational menu, never authority or a renewed deadline. Parsing
   * refusal is separate from guarded source reads so unavailable sources still escape unchanged.
   */
  public Optional<SelectionSnapshot> restoreSnapshot(byte[] stored) {
    Optional<Snapshot> decoded = DirectTextWorldSelectionSnapshotCodec.decode(stored);
    if (decoded.isEmpty()) {
      return Optional.empty();
    }
    Snapshot retained = decoded.orElseThrow();
    if (!MENU_LIFETIME.equals(Duration.between(retained.createdAt(), retained.expiresAt()))
        || !isLive(retained)) {
      return Optional.empty();
    }
    Optional<Snapshot> fresh = compose(retained.createdAt(), retained.expiresAt());
    if (fresh.isEmpty() || !retained.equals(fresh.orElseThrow()) || !isLive(retained)) {
      return Optional.empty();
    }
    return Optional.of(new SelectionSnapshot(this, retained));
  }

  private Optional<Snapshot> compose(Instant createdAt, Instant expiresAt) {
    var original = catalog.discoverySnapshot();
    List<CanonicalPlayableTarget> targets;
    try {
      targets = routeReader.readVisiblePublicProductionTargets();
    } catch (InvalidAuthorityException exception) {
      return Optional.empty();
    }
    if (!hasUniqueSourceIdentities(targets)) {
      return Optional.empty();
    }

    ArrayList<Choice> choices = new ArrayList<>(original.worlds().size());
    for (WorldView world : original.worlds()) {
      List<CanonicalPlayableTarget> matches =
          targets.stream().filter(target -> correlates(world, target)).toList();
      if (matches.size() != 1) {
        return Optional.empty();
      }
      choices.add(new Choice(world, matches.getFirst()));
    }

    // Each call is one validated catalog read. This bracket detects observed catalog drift during
    // composition; it is not a distributed lock or an Account admission fence.
    if (!original.fingerprint().equals(catalog.discoverySnapshot().fingerprint())
        || !clock.instant().isBefore(expiresAt)) {
      return Optional.empty();
    }
    return Optional.of(new Snapshot(choices, original.fingerprint(), createdAt, expiresAt));
  }

  /** Resolves only the retained vector, then revalidates the complete selected owner record. */
  public Optional<CanonicalPlayableTarget> resolve(SelectionSnapshot retained, String selector) {
    if (retained == null
        || retained.owner != this
        || selector == null
        || selector.isBlank()
        || !isLive(retained.snapshot)) {
      return Optional.empty();
    }
    Snapshot snapshot = retained.snapshot;
    Optional<Choice> selected = select(snapshot.choices(), selector);
    if (selected.isEmpty()) {
      return Optional.empty();
    }
    CanonicalPlayableTarget expected = selected.orElseThrow().target();
    CanonicalPlayableTarget current;
    try {
      current = routeReader.readCurrentTarget(expected);
    } catch (InvalidAuthorityException exception) {
      return Optional.empty();
    }
    if (!expected.equals(current)
        || !snapshot.fingerprint().equals(catalog.discoverySnapshot().fingerprint())
        || !isLive(snapshot)) {
      return Optional.empty();
    }
    return Optional.of(expected);
  }

  private boolean isLive(Snapshot snapshot) {
    Instant now = clock.instant();
    return !now.isBefore(snapshot.createdAt()) && now.isBefore(snapshot.expiresAt());
  }

  private static Optional<Choice> select(List<Choice> choices, String selector) {
    String folded = selector.toLowerCase(Locale.ROOT);
    // Numeric input is exclusively a response-local ordinal, never an owner/runtime identifier.
    if (folded.chars().allMatch(character -> character >= '0' && character <= '9')) {
      try {
        int ordinal = Integer.parseInt(folded);
        return ordinal > 0 && ordinal <= choices.size()
            ? Optional.of(choices.get(ordinal - 1))
            : Optional.empty();
      } catch (NumberFormatException exception) {
        return Optional.empty();
      }
    }
    List<Choice> matches =
        choices.stream()
            .filter(
                choice ->
                    folded.equals(choice.selector()) || folded.equals(choice.target().tenantSlug()))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private static boolean hasUniqueSourceIdentities(List<CanonicalPlayableTarget> targets) {
    if (targets == null) {
      return false;
    }
    Set<String> selectors = new HashSet<>();
    Set<RuntimeWorldIdentity> runtimeWorlds = new HashSet<>();
    Set<RuntimeTargetIdentity> runtimeTargets = new HashSet<>();
    for (CanonicalPlayableTarget target : targets) {
      if (target == null) {
        return false;
      }
      try {
        // Reuse the authored-source owner's exact canonical slug validation, without normalizing
        // inconsistent source evidence. The target constructor already validates its full record.
        AuthoredWorldSourceDigest.validateReadSelector(
            target.targetNamespace(), target.canonicalTenantId(), target.tenantSlug());
        AuthoredWorldSourceDigest.validateReadSelector(
            target.targetNamespace(), target.canonicalTenantId(), target.worldSlug());
      } catch (IllegalArgumentException exception) {
        return false;
      }
      if (!selectors.add(target.tenantSlug() + "/" + target.worldSlug())
          || !runtimeWorlds.add(
              new RuntimeWorldIdentity(target.gameSessionTenantId(), target.worldSlug()))
          || !runtimeTargets.add(
              new RuntimeTargetIdentity(target.gameSessionTenantId(), target.gameInstanceId()))) {
        return false;
      }
    }
    return true;
  }

  private static boolean correlates(WorldView world, CanonicalPlayableTarget target) {
    if (world.realms().size() != 1) {
      return false;
    }
    RealmView realm = world.realms().getFirst();
    // Display data is compared only alongside actual routing identity, never used as identity.
    // requiresCharacterSelection has no canonical-target counterpart: retain it in the original
    // world evidence and protect it with the complete catalog fingerprint instead of inferring it.
    return world.slug().equals(target.worldSlug())
        && Objects.equals(world.displayName(), target.worldDisplayName())
        && realm.visible()
        && realm.publicProductionRealm()
        && realm.slug().equals(target.realmSlug())
        && Objects.equals(realm.displayName(), target.realmDisplayName())
        && realm.tenantId() > 0L
        && realm.tenantId() == target.gameSessionTenantId()
        && realm.gameInstanceId() > 0L
        && realm.gameInstanceId() == target.gameInstanceId()
        && Objects.equals(realm.realmId(), target.realmId())
        && Objects.equals(realm.playableStateNamespaceId(), target.playableStateNamespaceId())
        && realm.catalogRevision() == target.catalogRevision()
        && realm.pointerVersion() == target.pointerVersion()
        && Objects.equals(realm.stateScope(), target.playableStateScope())
        && Objects.equals(realm.characterCreationPolicy(), target.characterCreationPolicy());
  }

  /** An opaque response-local handle, not a PlayerOutput or public wire schema. */
  public static final class SelectionSnapshot {
    private final DirectTextWorldSelectionService owner;
    private final Snapshot snapshot;

    private SelectionSnapshot(DirectTextWorldSelectionService owner, Snapshot snapshot) {
      this.owner = owner;
      this.snapshot = snapshot;
    }

    /** Ordered friendly selectors parallel to the original validated public world vector. */
    public List<String> selectors() {
      return snapshot.choices().stream().map(Choice::selector).toList();
    }

    /** Immutable original display/selection evidence; callers must not expose its internal IDs. */
    public List<WorldView> worlds() {
      return snapshot.choices().stream().map(Choice::world).toList();
    }

    public Instant expiresAt() {
      return snapshot.expiresAt();
    }
  }

  record Snapshot(List<Choice> choices, String fingerprint, Instant createdAt, Instant expiresAt) {
    Snapshot {
      choices = List.copyOf(choices);
      Objects.requireNonNull(fingerprint, "fingerprint");
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  record Choice(WorldView world, CanonicalPlayableTarget target) {
    Choice {
      Objects.requireNonNull(world, "world");
      Objects.requireNonNull(target, "target");
    }

    private String selector() {
      return target.tenantSlug() + "/" + target.worldSlug();
    }
  }

  private record RuntimeWorldIdentity(long tenantId, String worldSlug) {}

  private record RuntimeTargetIdentity(long tenantId, long gameInstanceId) {}
}
