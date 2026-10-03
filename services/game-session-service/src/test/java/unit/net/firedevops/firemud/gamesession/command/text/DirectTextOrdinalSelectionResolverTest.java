package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.DiscoverySnapshot;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmDiscoverySnapshot;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore.WorldsSnapshot;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class DirectTextOrdinalSelectionResolverTest {
  private static final Instant NOW = Instant.parse("2030-05-06T07:08:09Z");

  private final SessionContext caller =
      new SessionContext(7L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");
  private final RealmView realm =
      new RealmView(
          "production",
          "Production",
          22L,
          1L,
          1L,
          true,
          true,
          false,
          "SHARED",
          "ALLOW_NEW",
          1L,
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          UUID.fromString("00000000-0000-0000-0000-000000000002"));
  private final WorldView world = new WorldView("demo", "Demo", List.of(realm));
  private final GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(world));
  private final DiscoverySnapshot worlds = catalog.readDiscoverySnapshot();
  private final RealmDiscoverySnapshot realms = catalog.readRealmDiscoverySnapshot(world);

  @Test
  void resolvesWorldAndRealmOrdinalsAgainstCurrentBoundSnapshots() {
    DirectTextConnectScopeSessionStore store = DirectTextConnectScopeSessionStore.inMemoryForTest();
    store.replaceWorldSnapshot(
        caller.sessionId(),
        caller.accountId(),
        worlds.catalogFingerprint(),
        worlds.ordinalTargets(),
        NOW);
    store.replaceRealmSnapshot(
        caller,
        "demo",
        22L,
        "demo",
        realms.catalogFingerprint(),
        realms.ordinalTargets(),
        List.of(),
        NOW);

    var worldResult =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1", store, caller, NOW, worlds, catalog);
    var realmResult =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1", store, caller, 22L, world, NOW, () -> realms, catalog);

    assertThat(worldResult.selectedValue()).contains(world);
    assertThat(realmResult.selectedValue()).contains(realm);
    assertThat(DirectTextOrdinalSelectionResolver.resolveWorldTenantId(world))
        .isEqualTo(new DirectTextOrdinalSelectionResolver.UniqueWorldTenantId(22L));
  }

  @Test
  void concreteRealmResolverKeepsNumericTargetFromExactResponseSubsetAfterReordering() {
    RealmView preview = previewRealm(2L, 1L);
    WorldView responseWorld = new WorldView("demo", "Demo", List.of(preview, realm));
    DirectTextConnectScopeSessionStore store =
        storeWithRealmResponse(responseWorld, List.of(preview));
    WorldView currentWorld = new WorldView("demo", "Demo", List.of(realm, preview));
    GameplayWorldCatalog currentCatalog = GameplayWorldCatalog.forWorldViews(List.of(currentWorld));
    RealmDiscoverySnapshot publicSnapshot = currentCatalog.readRealmDiscoverySnapshot(currentWorld);

    assertThat(publicSnapshot.catalogFingerprint())
        .isNotEqualTo(
            GameplayWorldCatalog.forWorldViews(List.of(responseWorld))
                .realmDiscoverySnapshot(responseWorld, List.of(preview))
                .catalogFingerprint());

    var result =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1", store, caller, 22L, currentWorld, NOW, () -> publicSnapshot, currentCatalog);

    assertThat(result.selectedValue()).contains(preview);
  }

  @Test
  void concreteRealmResolverRejectsChangedTargetInExactResponseSubset() {
    RealmView preview = previewRealm(2L, 1L);
    WorldView responseWorld = new WorldView("demo", "Demo", List.of(preview, realm));
    DirectTextConnectScopeSessionStore store =
        storeWithRealmResponse(responseWorld, List.of(preview));
    RealmView reroutedPreview = previewRealm(3L, 2L);
    WorldView currentWorld = new WorldView("demo", "Demo", List.of(realm, reroutedPreview));
    GameplayWorldCatalog currentCatalog = GameplayWorldCatalog.forWorldViews(List.of(currentWorld));

    var result =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            store,
            caller,
            22L,
            currentWorld,
            NOW,
            () -> currentCatalog.readRealmDiscoverySnapshot(currentWorld),
            currentCatalog);

    assertThat(result).isInstanceOf(DirectTextOrdinalSelectionResolver.SnapshotMismatch.class);
  }

  @Test
  void absentAndExpiredWorldSnapshotsRemainUnbound() {
    var absent =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1", () -> Optional.empty(), NOW, worlds, ignored -> Optional.of(world));
    var expired =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1",
            () ->
                Optional.of(
                    new WorldsSnapshot(worlds.catalogFingerprint(), NOW, worlds.ordinalTargets())),
            NOW,
            worlds,
            ignored -> Optional.of(world));

    assertThat(absent).isInstanceOf(DirectTextOrdinalSelectionResolver.UnboundSelector.class);
    assertThat(expired).isInstanceOf(DirectTextOrdinalSelectionResolver.UnboundSelector.class);
  }

  @Test
  void absentAndExpiredRealmSnapshotsRemainUnbound() {
    var absent =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> Optional.empty(),
            22L,
            world,
            NOW,
            () -> realms,
            (current, target) -> Optional.of(realm));
    var expiredSnapshot =
        new DirectTextConnectScopeSessionStore.RealmsSnapshot(
            "demo", 22L, "demo", realms.catalogFingerprint(), NOW, realms.ordinalTargets());
    var expired =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> Optional.of(expiredSnapshot),
            22L,
            world,
            NOW,
            () -> realms,
            (current, target) -> Optional.of(realm));

    assertThat(absent).isInstanceOf(DirectTextOrdinalSelectionResolver.UnboundSelector.class);
    assertThat(expired).isInstanceOf(DirectTextOrdinalSelectionResolver.UnboundSelector.class);
  }

  @Test
  void malformedAndOutOfRangeOrdinalsRemainUnbound() {
    var snapshot =
        new WorldsSnapshot(
            worlds.catalogFingerprint(), NOW.plusSeconds(30), worlds.ordinalTargets());

    for (String selector : List.of("2147483648", "0", "99")) {
      var result =
          DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
              selector, () -> Optional.of(snapshot), NOW, worlds, ignored -> Optional.of(world));
      assertThat(result)
          .as("selector %s", selector)
          .isInstanceOf(DirectTextOrdinalSelectionResolver.UnboundSelector.class);
    }

    for (String selector : List.of("2147483648", "0", "99")) {
      var result =
          DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
              selector,
              () -> Optional.of(currentRealmsSnapshot()),
              22L,
              world,
              NOW,
              () -> realms,
              (current, target) -> Optional.of(realm));
      assertThat(result)
          .as("realm selector %s", selector)
          .isInstanceOf(DirectTextOrdinalSelectionResolver.UnboundSelector.class);
    }
  }

  @Test
  void changedFingerprintOrCurrentTargetIsSnapshotMismatch() {
    var changedFingerprint =
        new WorldsSnapshot("changed", NOW.plusSeconds(30), worlds.ordinalTargets());
    var fingerprintResult =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1", () -> Optional.of(changedFingerprint), NOW, worlds, ignored -> Optional.of(world));
    var targetResult =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1",
            () -> Optional.of(currentWorldsSnapshot()),
            NOW,
            worlds,
            ignored -> Optional.empty());

    assertThat(fingerprintResult)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.SnapshotMismatch.class);
    assertThat(targetResult)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.SnapshotMismatch.class);

    var staleRealmSnapshot =
        new DirectTextConnectScopeSessionStore.RealmsSnapshot(
            "demo", 22L, "demo", "changed", NOW.plusSeconds(30), realms.ordinalTargets());
    var realmFingerprintResult =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> Optional.of(staleRealmSnapshot),
            22L,
            world,
            NOW,
            () -> realms,
            (current, target) -> Optional.of(realm));
    var realmTargetResult =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> Optional.of(currentRealmsSnapshot()),
            22L,
            world,
            NOW,
            () -> realms,
            (current, target) -> Optional.empty());

    assertThat(realmFingerprintResult)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.SnapshotMismatch.class);
    assertThat(realmTargetResult)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.SnapshotMismatch.class);
  }

  @Test
  void unavailableOrConflictingStoreAndPointerFailuresRemainDistinct() {
    var missingWorldStore =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1", null, caller, NOW, worlds, catalog);
    var missingRealmStore =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1", null, caller, 22L, world, NOW, () -> realms, catalog);
    var unavailableStore =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            "1",
            () -> {
              throw new DirectTextConnectScopeSessionStore.StoreUnavailableException("read failed");
            },
            NOW,
            worlds,
            ignored -> Optional.of(world));
    var conflictingStore =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> {
              throw new DirectTextConnectScopeSessionStore.ConflictingIdentityException(
                  "identity mismatch");
            },
            22L,
            world,
            NOW,
            () -> realms,
            (current, target) -> Optional.of(realm));
    var unavailablePointer =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> Optional.of(currentRealmsSnapshot()),
            22L,
            world,
            NOW,
            () -> {
              throw new GameplayWorldCatalog.AuthorityPointerReadUnavailableException(
                  "read failed");
            },
            (current, target) -> Optional.of(realm));
    var invalidPointer =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            "1",
            () -> Optional.of(currentRealmsSnapshot()),
            22L,
            world,
            NOW,
            () -> {
              throw new GameplayWorldCatalog.AuthorityPointerUnavailableException("invalid");
            },
            (current, target) -> Optional.of(realm));

    assertThat(unavailableStore).isInstanceOf(DirectTextOrdinalSelectionResolver.Unavailable.class);
    assertThat(missingWorldStore)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.Unavailable.class);
    assertThat(missingRealmStore)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.Unavailable.class);
    assertThat(conflictingStore).isInstanceOf(DirectTextOrdinalSelectionResolver.Unavailable.class);
    assertThat(unavailablePointer)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.Unavailable.class);
    assertThat(invalidPointer)
        .isInstanceOf(DirectTextOrdinalSelectionResolver.PointerUnavailable.class);
  }

  @Test
  void ambiguousWorldTenantDoesNotProduceAnAuthorityIdentity() {
    WorldView ambiguousWorld =
        new WorldView(
            "demo",
            "Demo",
            List.of(
                realm,
                new RealmView(
                    "preview",
                    "Preview",
                    23L,
                    2L,
                    1L,
                    true,
                    false,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    1L)));

    assertThat(DirectTextOrdinalSelectionResolver.resolveWorldTenantId(ambiguousWorld))
        .isInstanceOf(DirectTextOrdinalSelectionResolver.AmbiguousWorldTenantId.class);
    assertThat(DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(ambiguousWorld))
        .isEqualTo(-1L);
  }

  private WorldsSnapshot currentWorldsSnapshot() {
    return new WorldsSnapshot(
        worlds.catalogFingerprint(), NOW.plusSeconds(30), worlds.ordinalTargets());
  }

  private DirectTextConnectScopeSessionStore.RealmsSnapshot currentRealmsSnapshot() {
    return new DirectTextConnectScopeSessionStore.RealmsSnapshot(
        "demo",
        22L,
        "demo",
        realms.catalogFingerprint(),
        NOW.plusSeconds(30),
        realms.ordinalTargets());
  }

  private DirectTextConnectScopeSessionStore storeWithRealmResponse(
      WorldView responseWorld, List<RealmView> responseRealms) {
    GameplayWorldCatalog responseCatalog =
        GameplayWorldCatalog.forWorldViews(List.of(responseWorld));
    RealmDiscoverySnapshot responseSnapshot =
        responseCatalog.realmDiscoverySnapshot(responseWorld, responseRealms);
    DirectTextConnectScopeSessionStore store = DirectTextConnectScopeSessionStore.inMemoryForTest();
    store.replaceRealmSnapshot(
        caller,
        "demo",
        22L,
        "demo",
        responseSnapshot.catalogFingerprint(),
        responseSnapshot.ordinalTargets(),
        List.of(),
        NOW);
    return store;
  }

  private RealmView previewRealm(long gameInstanceId, long pointerVersion) {
    return new RealmView(
        "preview",
        "Preview",
        22L,
        gameInstanceId,
        pointerVersion,
        true,
        false,
        false,
        "ISOLATED",
        "ALLOW_NEW",
        1L,
        UUID.fromString("00000000-0000-0000-0000-000000000003"),
        UUID.fromString("00000000-0000-0000-0000-000000000004"));
  }
}
