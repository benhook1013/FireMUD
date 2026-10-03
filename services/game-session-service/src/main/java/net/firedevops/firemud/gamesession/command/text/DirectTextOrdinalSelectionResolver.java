package net.firedevops.firemud.gamesession.command.text;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.DiscoverySnapshot;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmDiscoverySnapshot;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.RealmOrdinalTarget;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.RealmsSnapshot;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.WorldOrdinalTarget;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.WorldsSnapshot;
import net.firedevops.firemud.gamesession.service.SessionContext;

/** Shared, policy-neutral resolution for direct-text response-local ordinal selectors. */
final class DirectTextOrdinalSelectionResolver {
  private DirectTextOrdinalSelectionResolver() {}

  static WorldTenantResolution resolveWorldTenantId(WorldView world) {
    Objects.requireNonNull(world, "world must not be null");
    List<Long> tenantIds = world.realms().stream().map(RealmView::tenantId).distinct().toList();
    if (tenantIds.size() != 1) {
      return new AmbiguousWorldTenantId();
    }
    return new UniqueWorldTenantId(tenantIds.getFirst());
  }

  static long worldTenantIdOrInvalid(WorldView world) {
    WorldTenantResolution resolution = resolveWorldTenantId(world);
    return resolution instanceof UniqueWorldTenantId unique ? unique.tenantId() : -1L;
  }

  static Resolution<WorldView> resolveWorldOrdinal(
      String selector,
      DirectTextConnectScopeSessionStore store,
      SessionContext caller,
      Instant now,
      DiscoverySnapshot currentCatalog,
      GameplayWorldCatalog worldCatalog) {
    Objects.requireNonNull(caller, "caller must not be null");
    Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
    if (store == null) {
      return new Unavailable<>();
    }
    return resolveWorldOrdinal(
        selector,
        () -> store.worldsSnapshot(caller, now),
        now,
        currentCatalog,
        target -> worldCatalog.resolveSnapshotOrdinal(currentCatalog, target));
  }

  static <T> Resolution<T> resolveWorldOrdinal(
      String selector,
      Supplier<Optional<WorldsSnapshot>> snapshotReader,
      Instant now,
      DiscoverySnapshot currentCatalog,
      Function<WorldOrdinalTarget, Optional<T>> currentTargetResolver) {
    Objects.requireNonNull(snapshotReader, "snapshotReader must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(currentCatalog, "currentCatalog must not be null");
    Objects.requireNonNull(currentTargetResolver, "currentTargetResolver must not be null");

    Optional<WorldsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot = Objects.requireNonNull(snapshotReader.get(), "snapshotReader returned null");
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new Unavailable<>();
    }
    if (maybeSnapshot.isEmpty()) {
      return new UnboundSelector<>();
    }
    WorldsSnapshot snapshot = maybeSnapshot.orElseThrow();
    if (!snapshot.expiresAt().isAfter(now)) {
      return new UnboundSelector<>();
    }
    if (!snapshot.catalogFingerprint().equals(currentCatalog.catalogFingerprint())) {
      return new SnapshotMismatch<>();
    }
    Optional<Integer> maybeOrdinal = parseOrdinal(selector);
    if (maybeOrdinal.isEmpty()) {
      return new UnboundSelector<>();
    }
    Optional<WorldOrdinalTarget> maybeTarget =
        snapshot.ordinalTargets().stream()
            .filter(target -> target.ordinal() == maybeOrdinal.orElseThrow())
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return new UnboundSelector<>();
    }
    Optional<T> selected = currentTargetResolver.apply(maybeTarget.orElseThrow());
    return selected.<Resolution<T>>map(Selected::new).orElseGet(SnapshotMismatch::new);
  }

  static Resolution<RealmView> resolveRealmOrdinal(
      String selector,
      DirectTextConnectScopeSessionStore store,
      SessionContext caller,
      long tenantId,
      WorldView world,
      Instant now,
      Supplier<RealmDiscoverySnapshot> currentCatalogReader,
      GameplayWorldCatalog worldCatalog) {
    Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
    Objects.requireNonNull(currentCatalogReader, "currentCatalogReader must not be null");
    Objects.requireNonNull(world, "world must not be null");
    if (store == null) {
      return new Unavailable<>();
    }
    return resolveRealmOrdinalWithSnapshotValidation(
        selector,
        () -> store.realmsSnapshot(caller, tenantId, world.slug(), now),
        tenantId,
        world,
        now,
        currentCatalogReader,
        (snapshot, currentCatalog) -> {
          if (!currentCatalog.worldSlug().equalsIgnoreCase(world.slug())) {
            return Optional.empty();
          }
          return worldCatalog
              .revalidateRealmDiscoverySnapshot(world, snapshot.ordinalTargets())
              .filter(
                  currentResponse ->
                      currentResponse.worldSlug().equalsIgnoreCase(world.slug())
                          && snapshot
                              .catalogFingerprint()
                              .equals(currentResponse.catalogFingerprint())
                          && snapshot.ordinalTargets().equals(currentResponse.ordinalTargets()));
        },
        (current, target) -> worldCatalog.resolveRealmSnapshotOrdinal(world, current, target));
  }

  static <T> Resolution<T> resolveRealmOrdinal(
      String selector,
      Supplier<Optional<RealmsSnapshot>> snapshotReader,
      long tenantId,
      WorldView world,
      Instant now,
      Supplier<RealmDiscoverySnapshot> currentCatalogReader,
      BiFunction<RealmDiscoverySnapshot, RealmOrdinalTarget, Optional<T>> currentTargetResolver) {
    return resolveRealmOrdinalWithSnapshotValidation(
        selector,
        snapshotReader,
        tenantId,
        world,
        now,
        currentCatalogReader,
        (snapshot, currentCatalog) -> Optional.of(currentCatalog),
        currentTargetResolver);
  }

  private static <T> Resolution<T> resolveRealmOrdinalWithSnapshotValidation(
      String selector,
      Supplier<Optional<RealmsSnapshot>> snapshotReader,
      long tenantId,
      WorldView world,
      Instant now,
      Supplier<RealmDiscoverySnapshot> currentCatalogReader,
      BiFunction<RealmsSnapshot, RealmDiscoverySnapshot, Optional<RealmDiscoverySnapshot>>
          currentSnapshotResolver,
      BiFunction<RealmDiscoverySnapshot, RealmOrdinalTarget, Optional<T>> currentTargetResolver) {
    Objects.requireNonNull(snapshotReader, "snapshotReader must not be null");
    Objects.requireNonNull(world, "world must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(currentCatalogReader, "currentCatalogReader must not be null");
    Objects.requireNonNull(currentSnapshotResolver, "currentSnapshotResolver must not be null");
    Objects.requireNonNull(currentTargetResolver, "currentTargetResolver must not be null");

    Optional<RealmsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot = Objects.requireNonNull(snapshotReader.get(), "snapshotReader returned null");
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new Unavailable<>();
    }
    if (maybeSnapshot.isEmpty()) {
      return new UnboundSelector<>();
    }
    RealmsSnapshot snapshot = maybeSnapshot.orElseThrow();
    if (!snapshot.expiresAt().isAfter(now)) {
      return new UnboundSelector<>();
    }
    if (tenantId <= 0L
        || snapshot.tenantId() != tenantId
        || !snapshot.worldSlug().equalsIgnoreCase(world.slug())) {
      return new SnapshotMismatch<>();
    }

    Optional<RealmDiscoverySnapshot> maybeCurrentResponse;
    try {
      RealmDiscoverySnapshot currentCatalog = currentCatalogReader.get();
      maybeCurrentResponse = currentSnapshotResolver.apply(snapshot, currentCatalog);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return new Unavailable<>();
    } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
      return new PointerUnavailable<>();
    }
    if (maybeCurrentResponse.isEmpty()) {
      return new SnapshotMismatch<>();
    }
    RealmDiscoverySnapshot currentResponse = maybeCurrentResponse.orElseThrow();
    if (!currentResponse.worldSlug().equalsIgnoreCase(world.slug())
        || !snapshot.catalogFingerprint().equals(currentResponse.catalogFingerprint())) {
      return new SnapshotMismatch<>();
    }
    Optional<Integer> maybeOrdinal = parseOrdinal(selector);
    if (maybeOrdinal.isEmpty()) {
      return new UnboundSelector<>();
    }
    Optional<RealmOrdinalTarget> maybeTarget =
        snapshot.ordinalTargets().stream()
            .filter(target -> target.ordinal() == maybeOrdinal.orElseThrow())
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return new UnboundSelector<>();
    }
    Optional<T> selected = currentTargetResolver.apply(currentResponse, maybeTarget.orElseThrow());
    return selected.<Resolution<T>>map(Selected::new).orElseGet(SnapshotMismatch::new);
  }

  private static Optional<Integer> parseOrdinal(String selector) {
    if (!GameplayWorldCatalog.isOrdinalSelector(selector)) {
      return Optional.empty();
    }
    try {
      int ordinal = Integer.parseInt(selector.trim());
      return ordinal > 0 ? Optional.of(ordinal) : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  sealed interface WorldTenantResolution permits UniqueWorldTenantId, AmbiguousWorldTenantId {}

  record UniqueWorldTenantId(long tenantId) implements WorldTenantResolution {}

  record AmbiguousWorldTenantId() implements WorldTenantResolution {}

  sealed interface Resolution<T>
      permits Selected, UnboundSelector, SnapshotMismatch, Unavailable, PointerUnavailable {
    default Optional<T> selectedValue() {
      return Optional.empty();
    }
  }

  record Selected<T>(T value) implements Resolution<T> {
    Selected {
      Objects.requireNonNull(value, "selected value must not be null");
    }

    @Override
    public Optional<T> selectedValue() {
      return Optional.of(value);
    }
  }

  record UnboundSelector<T>() implements Resolution<T> {}

  record SnapshotMismatch<T>() implements Resolution<T> {}

  record Unavailable<T>() implements Resolution<T> {}

  record PointerUnavailable<T>() implements Resolution<T> {}
}
