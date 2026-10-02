package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class GameplayWorldCatalogTest {
  private final GameplayAdmissionPointerAuthorityService authorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);

  @Test
  void visibleWorldsDropsAmbiguousRealmSelectorRows() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 12L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
  }

  @Test
  void visibleWorldsKeepsDistinctWorldSlugsTenantQualifiedInOrdinalTargets() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer(
                    "authority", "Authority World", "production", "Authority Live", 2L, 21L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    GameplayWorldCatalog.DiscoverySnapshot snapshot = catalog.readDiscoverySnapshot();
    assertThat(snapshot.output().worlds())
        .extracting(WorldsViewOutput.WorldEntry::ordinal)
        .containsExactly(1, 2);
    assertThat(snapshot.ordinalTargets())
        .extracting(DirectTextConnectScopeSessionStore.WorldOrdinalTarget::tenantId)
        .containsExactly(1L, 2L);
    assertThat(catalog.resolveSnapshotOrdinal(snapshot, snapshot.ordinalTargets().get(1)))
        .hasValueSatisfying(world -> assertThat(world.slug()).isEqualTo("authority"));
  }

  @Test
  void visibleWorldsGroupsMultipleRealmsForTheSameTenantWorldSlug() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("demo", "Demo World", "event", "Event Realm", 1L, 12L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    GameplayWorldCatalog.WorldView world = catalog.resolveWorld("demo").orElseThrow();
    assertThat(world.realms())
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("production", "event");
  }

  @Test
  void visibleWorldsTreatsRealmSlugCaseCollisionsAsAmbiguous() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("demo", "Demo World", "PRODUCTION", "Other Live Realm", 1L, 12L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.readDiscoverySnapshot().output().worlds()).isEmpty();
    assertThat(catalog.publicProductionRealmCardinality(1L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
  }

  @Test
  void pointerReadFailureIsDistinctFromMalformedOrAmbiguousAuthority() {
    when(authorityService.listPointers())
        .thenThrow(new IllegalStateException("authority down"))
        .thenReturn(null);
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::readDiscoverySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
    assertThatThrownBy(catalog::readDiscoverySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
  }

  @Test
  void reverseRuntimeLookupFailsClosedWhenMultipleVisibleRealmsShareRuntimeTarget() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("demo", "Demo World", "event", "Event Realm", 1L, 11L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.resolveRealmByRuntimeTarget(1L, 11L)).isEmpty();
    assertThat(catalog.resolveRuntimeTarget(1L, 11L)).isEmpty();
  }

  @Test
  void reverseRuntimeLookupCountsHiddenRealmPointersBeforeCollapsingRuntimeTarget() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L, true),
                pointer("demo", "Demo World", "private", "Private Realm", 1L, 11L, 8L, false)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.resolveRealmByRuntimeTarget(1L, 11L)).isEmpty();
    assertThat(catalog.resolveRuntimeTarget(1L, 11L)).isEmpty();
  }

  @Test
  void visibleWorldsDropIncompleteAuthorityPointers() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    11L,
                    0L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.resolveRuntimeTarget(1L, 11L)).isEmpty();
  }

  @Test
  void authoritySnapshotRejectsPublicRealmsAcrossDifferentWorldsForOneTenant() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("alpha", "Alpha World", 1L, 11L),
                publicPointer("beta", "Beta World", 1L, 12L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::visibleWorldsFromAuthoritySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("public-production realm count is invalid");
  }

  @Test
  void authoritySnapshotRejectsTenantWithoutVisiblePublicRealm() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(pointer("demo", "Demo World", "private", "Private Realm", 1L, 11L, 7L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::visibleWorldsFromAuthoritySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("public-production realm count is invalid");
  }

  @ParameterizedTest(name = "authority selector resolution rejects {0} public realms")
  @MethodSource("invalidPublicRealmCardinalities")
  void resolveWorldFromAuthoritySnapshotRejectsInvalidPublicRealmCardinalityBeforeLookup(
      String cardinality, List<GameplayAdmissionPointerSnapshot> pointers) {
    when(authorityService.listPointers()).thenReturn(pointers);
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.resolveWorldFromAuthoritySnapshot("unknown-world"))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("public-production realm count is invalid");
    Mockito.verify(authorityService, Mockito.times(1)).listPointers();
  }

  @Test
  void resolveWorldFromAuthoritySnapshotKeepsHealthyUnknownSelectorAsEmpty() {
    when(authorityService.listPointers())
        .thenReturn(List.of(publicPointer("demo", "Demo World", 1L, 11L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.resolveWorldFromAuthoritySnapshot("unknown-world")).isEmpty();
    Mockito.verify(authorityService, Mockito.times(1)).listPointers();
  }

  @Test
  void authoritySnapshotKeepsPrivateRealmsWhenTenantHasOneVisiblePublicRealm() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("demo", "Demo World", 1L, 11L),
                pointer("private", "Private World", "preview", "Preview", 1L, 12L, 1L),
                publicPointer("hidden", "Hidden", 1L, 13L, false)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorldsFromAuthoritySnapshot())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("demo", "private");
  }

  @ParameterizedTest(name = "authority projection rejects {0}")
  @MethodSource("incompleteAuthorityPointers")
  void visibleWorldsDropPointersWithoutCompleteCatalogIdentity(
      String reason, GameplayAdmissionPointerSnapshot pointer) {
    when(authorityService.listPointers()).thenReturn(List.of(pointer));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.resolveRuntimeTarget(1L, 11L)).isEmpty();
  }

  @Test
  void visibleWorldsDropPointersMissingCharacterCreationPolicy() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    11L,
                    7L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "")));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
  }

  @Test
  void resolveRealmForAdmissionIncludesHiddenRealm() {
    GameplayWorldCatalog.WorldView sourceWorld = worldWithTargetRealm("private", false);
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(sourceWorld));
    GameplayWorldCatalog.WorldView visibleWorld = catalog.resolveWorld("demo").orElseThrow();

    assertThat(catalog.resolveRealmForAdmission(visibleWorld, "private"))
        .contains(visibleWorld.realms().get(1));
  }

  @Test
  void resolveRealmForAdmissionMatchesRealmSelectorCaseInsensitively() {
    GameplayWorldCatalog.WorldView sourceWorld = worldWithTargetRealm("Preview", true);
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(sourceWorld));
    GameplayWorldCatalog.WorldView visibleWorld = catalog.resolveWorld("demo").orElseThrow();

    assertThat(catalog.resolveRealmForAdmission(visibleWorld, " pReViEw "))
        .contains(visibleWorld.realms().get(1));
  }

  @Test
  void resolveRealmForAdmissionRejectsBlankSelector() {
    GameplayWorldCatalog.WorldView sourceWorld = worldWithTargetRealm("preview", false);
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(sourceWorld));
    GameplayWorldCatalog.WorldView visibleWorld = catalog.resolveWorld("demo").orElseThrow();

    assertThat(catalog.resolveRealmForAdmission(visibleWorld, "   ")).isEmpty();
  }

  @Test
  void normalizeWorldsDropsRealmViewsWithoutSlugsBeforeAdmissionLookup() {
    GameplayWorldCatalog.WorldView sourceWorld =
        new GameplayWorldCatalog.WorldView(
            "demo",
            "Demo World",
            List.of(
                new GameplayWorldCatalog.RealmView(
                    "production",
                    "Live Realm",
                    7L,
                    11L,
                    1L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW"),
                new GameplayWorldCatalog.RealmView(
                    null,
                    "Invalid Realm",
                    7L,
                    12L,
                    1L,
                    true,
                    false,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(sourceWorld));
    GameplayWorldCatalog.WorldView normalizedWorld = catalog.resolveWorld("demo").orElseThrow();

    assertThat(normalizedWorld.realms())
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("production");
    assertThat(catalog.resolveRealmForAdmission(normalizedWorld, "invalid")).isEmpty();
  }

  @Test
  void publicProductionCardinalityCountsExactlyOneAcrossTheTenantCatalogue() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("demo", "production", 7L, true),
                worldWithRealm("preview", "preview", 7L, false)));

    assertThat(catalog.publicProductionRealmCardinality(7L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
    assertThat(catalog.resolveDefaultRealm(catalog.resolveWorld("demo").orElseThrow())).isPresent();
  }

  @Test
  void publicProductionCardinalityFailsClosedWhenTenantHasNoPublicRealm() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(worldWithRealm("preview", "preview", 7L, false)));

    assertThat(catalog.publicProductionRealmCardinality(7L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.ZERO);
    assertThat(catalog.resolveDefaultRealm(catalog.resolveWorld("preview").orElseThrow()))
        .isEmpty();
  }

  @Test
  void publicProductionCardinalityCountsSameTenantCandidatesAcrossWorlds() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("demo", "production", 7L, true),
                worldWithRealm("alternate", "production", 7L, true)));

    assertThat(catalog.publicProductionRealmCardinality(7L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.MULTIPLE);
    assertThat(catalog.resolveDefaultRealm(catalog.resolveWorld("demo").orElseThrow())).isEmpty();
  }

  @Test
  void publicWorldsExcludePrivateOnlyWorldsFromSameTenant() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("public-world", "production", 7L, true),
                worldWithRealm("private-world", "private", 7L, false)));

    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("public-world");
    assertThat(catalog.resolveWorld("1"))
        .hasValueSatisfying(world -> assertThat(world.slug()).isEqualTo("public-world"));
    assertThat(catalog.resolveWorld("2")).isEmpty();
    assertThat(catalog.resolveWorld("private-world")).isPresent();
  }

  @Test
  void closedVisiblePublicRealmStillCountsAsTheTenantPublicRealm() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            7L,
                            0L,
                            0L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW")))));

    assertThat(catalog.publicProductionRealmCardinality(7L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
    assertThat(catalog.resolveDefaultRealm(catalog.resolveWorld("demo").orElseThrow())).isPresent();
  }

  @Test
  void numericWorldSelectionUsesTheSameFilteredSetAsWorldBrowsePresentation() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("invalid", "preview", 7L, false),
                worldWithRealm("demo", "production", 8L, true)));

    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo");
    assertThat(catalog.resolveWorld("1").orElseThrow().slug()).isEqualTo("demo");
    assertThat(catalog.resolveWorld("invalid")).isPresent();
  }

  @Test
  void authoritativePointerIdentityAndCatalogRevisionReachRealmView() {
    java.util.UUID realmId = java.util.UUID.fromString("8a1df0f1-1b57-465e-9c4b-bb34f8153d31");
    java.util.UUID playableStateNamespaceId =
        java.util.UUID.fromString("2ea958e0-13a2-41d0-9c39-59a96cf31412");
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    7L,
                    11L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    29L,
                    realmId,
                    playableStateNamespaceId)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    GameplayWorldCatalog.RealmView realm =
        catalog
            .resolveRealm(catalog.resolveWorld("demo").orElseThrow(), "production")
            .orElseThrow();

    assertThat(realm.catalogRevision()).isEqualTo(29L);
    assertThat(realm.realmId()).isEqualTo(realmId);
    assertThat(realm.playableStateNamespaceId()).isEqualTo(playableStateNamespaceId);
  }

  @Test
  void realmSnapshotResolvesByExactTargetIdentityAfterResponseOrdinalChanges() {
    GameplayWorldCatalog.RealmView production =
        new GameplayWorldCatalog.RealmView(
            "production",
            "Live Realm",
            7L,
            11L,
            3L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            29L,
            UUID.fromString("8a1df0f1-1b57-465e-9c4b-bb34f8153d31"),
            UUID.fromString("2ea958e0-13a2-41d0-9c39-59a96cf31412"));
    GameplayWorldCatalog.RealmView preview =
        new GameplayWorldCatalog.RealmView(
            "preview",
            "Preview Realm",
            7L,
            12L,
            4L,
            true,
            false,
            false,
            "SHARED",
            "ALLOW_NEW",
            30L,
            UUID.fromString("a11df0f1-1b57-465e-9c4b-bb34f8153d31"),
            UUID.fromString("b2a958e0-13a2-41d0-9c39-59a96cf31412"));
    GameplayWorldCatalog.WorldView original =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(production, preview));
    GameplayWorldCatalog.WorldView reordered =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(preview, production));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(reordered));
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        GameplayWorldCatalog.forWorldViews(List.of(original))
            .realmDiscoverySnapshot(original, List.of(production, preview));

    assertThat(
            catalog.resolveRealmSnapshotOrdinal(
                reordered, snapshot, snapshot.ordinalTargets().get(0)))
        .contains(production);
    assertThat(catalog.readRealmDiscoverySnapshot(reordered).catalogFingerprint())
        .isNotEqualTo(snapshot.catalogFingerprint());
  }

  @Test
  void revalidatedRealmFingerprintIncludesOnlyTheOriginalResponseTargets() {
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of());
    GameplayWorldCatalog.RealmView production =
        new GameplayWorldCatalog.RealmView(
            "production",
            "Live Realm",
            7L,
            11L,
            3L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            29L,
            UUID.fromString("8a1df0f1-1b57-465e-9c4b-bb34f8153d31"),
            UUID.fromString("2ea958e0-13a2-41d0-9c39-59a96cf31412"));
    GameplayWorldCatalog.RealmView deniedPrivateRealm =
        new GameplayWorldCatalog.RealmView(
            "preview",
            "Preview Realm",
            7L,
            12L,
            4L,
            true,
            false,
            false,
            "SHARED",
            "ALLOW_NEW",
            30L,
            UUID.fromString("a11df0f1-1b57-465e-9c4b-bb34f8153d31"),
            UUID.fromString("b2a958e0-13a2-41d0-9c39-59a96cf31412"));
    GameplayWorldCatalog.WorldView responseWorld =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(production, deniedPrivateRealm));
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        catalog.realmDiscoverySnapshot(responseWorld, List.of(production));
    GameplayWorldCatalog.RealmView changedDeniedPrivateRealm =
        new GameplayWorldCatalog.RealmView(
            "preview",
            "Preview Realm",
            7L,
            98L,
            9L,
            true,
            false,
            false,
            "SHARED",
            "ALLOW_NEW",
            31L,
            deniedPrivateRealm.realmId(),
            deniedPrivateRealm.playableStateNamespaceId());

    GameplayWorldCatalog.RealmDiscoverySnapshot hiddenChange =
        catalog
            .revalidateRealmDiscoverySnapshot(
                new GameplayWorldCatalog.WorldView(
                    "demo", "Demo", List.of(production, changedDeniedPrivateRealm)),
                snapshot.ordinalTargets())
            .orElseThrow();

    assertThat(hiddenChange.catalogFingerprint()).isEqualTo(snapshot.catalogFingerprint());
    assertThat(hiddenChange.ordinalTargets()).containsExactlyElementsOf(snapshot.ordinalTargets());

    GameplayWorldCatalog.RealmView changedProduction =
        new GameplayWorldCatalog.RealmView(
            production.slug(),
            production.displayName(),
            production.tenantId(),
            production.gameInstanceId(),
            production.pointerVersion() + 1,
            production.visible(),
            production.publicProductionRealm(),
            production.requiresCharacterSelection(),
            production.stateScope(),
            production.characterCreationPolicy(),
            production.catalogRevision(),
            production.realmId(),
            production.playableStateNamespaceId());
    GameplayWorldCatalog.RealmDiscoverySnapshot visibleChange =
        catalog
            .revalidateRealmDiscoverySnapshot(
                new GameplayWorldCatalog.WorldView(
                    "demo", "Demo", List.of(changedProduction, deniedPrivateRealm)),
                snapshot.ordinalTargets())
            .orElseThrow();
    assertThat(visibleChange.catalogFingerprint()).isNotEqualTo(snapshot.catalogFingerprint());
  }

  private static GameplayWorldCatalog.WorldView worldWithTargetRealm(
      String realmSlug, boolean visible) {
    return new GameplayWorldCatalog.WorldView(
        "demo",
        "Demo World",
        List.of(
            new GameplayWorldCatalog.RealmView(
                "production", "Live Realm", 7L, 11L, 1L, true, true, false, "SHARED", "ALLOW_NEW"),
            new GameplayWorldCatalog.RealmView(
                realmSlug,
                "Target Realm",
                7L,
                12L,
                1L,
                visible,
                false,
                false,
                "SHARED",
                "ALLOW_NEW")));
  }

  private static GameplayWorldCatalog.WorldView worldWithRealm(
      String worldSlug, String realmSlug, long tenantId, boolean publicProduction) {
    return new GameplayWorldCatalog.WorldView(
        worldSlug,
        worldSlug,
        List.of(
            new GameplayWorldCatalog.RealmView(
                realmSlug,
                realmSlug,
                tenantId,
                11L,
                1L,
                true,
                publicProduction,
                false,
                "SHARED",
                "ALLOW_NEW")));
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion) {
    return pointer(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        true);
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible) {
    UUID realmId = stableId("realm/" + tenantId + "/" + worldSlug + "/" + realmSlug);
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        visible,
        "production".equals(realmSlug),
        false,
        "SHARED",
        "ALLOW_NEW",
        1L,
        realmId,
        stableId("namespace/" + realmId));
  }

  private static GameplayAdmissionPointerSnapshot publicPointer(
      String worldSlug, String worldDisplayName, long tenantId, long gameInstanceId) {
    return publicPointer(worldSlug, worldDisplayName, tenantId, gameInstanceId, true);
  }

  private static GameplayAdmissionPointerSnapshot publicPointer(
      String worldSlug,
      String worldDisplayName,
      long tenantId,
      long gameInstanceId,
      boolean visible) {
    UUID realmId = stableId("realm/" + tenantId + "/" + worldSlug + "/public");
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldDisplayName,
        "live",
        "Live Realm",
        tenantId,
        gameInstanceId,
        1L,
        visible,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        1L,
        realmId,
        stableId("namespace/" + realmId));
  }

  private static Stream<Arguments> incompleteAuthorityPointers() {
    GameplayAdmissionPointerSnapshot complete =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            stableId("realm/demo/production"),
            stableId("namespace/demo/production"));
    return Stream.of(
        Arguments.of(
            "non-positive catalog revision",
            copyAuthorityPointer(
                complete,
                0L,
                complete.realmId(),
                complete.playableStateNamespaceId(),
                complete.stateScope())),
        Arguments.of(
            "negative catalog revision",
            copyAuthorityPointer(
                complete,
                -1L,
                complete.realmId(),
                complete.playableStateNamespaceId(),
                complete.stateScope())),
        Arguments.of(
            "missing realm identity",
            copyAuthorityPointer(
                complete,
                complete.catalogRevision(),
                null,
                complete.playableStateNamespaceId(),
                complete.stateScope())),
        Arguments.of(
            "missing playable-state namespace",
            copyAuthorityPointer(
                complete,
                complete.catalogRevision(),
                complete.realmId(),
                null,
                complete.stateScope())),
        Arguments.of(
            "unknown playable-state scope",
            copyAuthorityPointer(
                complete,
                complete.catalogRevision(),
                complete.realmId(),
                complete.playableStateNamespaceId(),
                "UNKNOWN")),
        Arguments.of(
            "non-canonical playable-state scope",
            copyAuthorityPointer(
                complete,
                complete.catalogRevision(),
                complete.realmId(),
                complete.playableStateNamespaceId(),
                "shared")));
  }

  private static Stream<Arguments> invalidPublicRealmCardinalities() {
    return Stream.of(
        Arguments.of(
            "zero", List.of(pointer("demo", "Demo World", "private", "Private", 1L, 11L, 7L))),
        Arguments.of(
            "multiple",
            List.of(
                publicPointer("alpha", "Alpha World", 1L, 11L),
                publicPointer("beta", "Beta World", 1L, 12L))));
  }

  private static GameplayAdmissionPointerSnapshot copyAuthorityPointer(
      GameplayAdmissionPointerSnapshot pointer,
      long catalogRevision,
      UUID realmId,
      UUID playableStateNamespaceId,
      String stateScope) {
    return new GameplayAdmissionPointerSnapshot(
        pointer.worldSlug(),
        pointer.worldDisplayName(),
        pointer.realmSlug(),
        pointer.realmDisplayName(),
        pointer.tenantId(),
        pointer.gameInstanceId(),
        pointer.pointerVersion(),
        pointer.visible(),
        pointer.publicProductionRealm(),
        pointer.requiresCharacterSelection(),
        stateScope,
        pointer.characterCreationPolicy(),
        catalogRevision,
        realmId,
        playableStateNamespaceId);
  }

  private static UUID stableId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void ambiguousPublicRealmSelectorSuppressesPublicDiscoveryButRetainsInternalCatalog() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(
                        realm("production", true),
                        realm("PRODUCTION", false),
                        realm("event", false)))));
    assertThat(catalog.publicVisibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolvePublicWorld("demo")).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isPresent();
    assertThat(catalog.resolveRealmTarget("demo", "production")).isEmpty();
  }

  @Test
  void duplicateRealmSlugsCannotHideExtraVisiblePublicRealmRows() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(
                        realm("production", true),
                        realm("event", true),
                        realm("EVENT", true),
                        realm("private", false)))));

    assertThat(catalog.publicVisibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolvePublicWorld("demo")).isEmpty();
    assertThat(catalog.publicProductionRealmCardinality(7L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.MULTIPLE);
  }

  @Test
  void eachTenantMayExposeOneVisiblePublicRealm() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo", "Demo World", List.of(realm("production", true))),
                new GameplayWorldCatalog.WorldView(
                    "sandbox",
                    "Builder Sandbox",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            8L,
                            12L,
                            1L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW")))));

    assertThat(catalog.browseView().worlds()).extracting("slug").containsExactly("demo", "sandbox");
    assertThat(catalog.resolveWorld("demo")).isPresent();
    assertThat(catalog.resolveWorld("sandbox")).isPresent();
  }

  @Test
  void hiddenSecondPublicRealmDoesNotInvalidateOrAuthorizePublicSelection() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(
                        realm("production", true),
                        new GameplayWorldCatalog.RealmView(
                            "hidden-production",
                            "Hidden Production",
                            7L,
                            12L,
                            1L,
                            false,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW")))));

    GameplayWorldCatalog.WorldView world = catalog.resolveWorld("demo").orElseThrow();

    assertThat(catalog.browseRealms("demo").orElseThrow().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    assertThat(catalog.resolveRealm(world, "hidden-production")).isEmpty();
  }

  @Test
  void publicBrowseDropsCaseInsensitiveRealmSlugCollisions() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(realm("production", true), realm("PRODUCTION", true)))));

    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.browseRealms("demo")).isEmpty();
  }

  @Test
  void publicBrowseOmitsPrivateOnlyWorldsAndPrivateRealmMetadata() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "private-world",
                    "Private World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            7L,
                            17L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW"))),
                new GameplayWorldCatalog.WorldView(
                    "mixed-world",
                    "Mixed World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            7L,
                            11L,
                            1L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW"),
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            7L,
                            17L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW")))));

    assertThat(catalog.browseView().worlds()).extracting("slug").containsExactly("mixed-world");
    assertThat(catalog.browseRealms("mixed-world").orElseThrow().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    assertThat(catalog.browseRealms("private-world")).isEmpty();
  }

  @Test
  void realmSelectionUsesPublicDefaultWhenVisiblePrivateRealmIsAlsoPresent() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "mixed-world",
                    "Mixed World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            7L,
                            11L,
                            1L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW"),
                        new GameplayWorldCatalog.RealmView(
                            "private",
                            "Private Realm",
                            7L,
                            17L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW")))));
    GameplayWorldCatalog.WorldView world = catalog.resolveWorld("mixed-world").orElseThrow();

    assertThat(catalog.requiresExplicitRealmSelection(world)).isFalse();
    assertThat(catalog.resolveDefaultRealm(world))
        .map(GameplayWorldCatalog.RealmView::slug)
        .contains("production");
  }

  @Test
  void resolveWorldFailsClosedWhenNormalizedSlugMatchesMultipleWorldViews() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(worldWithTargetRealm("preview", true), worldWithTargetRealm("preview", true)));

    assertThat(catalog.resolveWorld(" DeMo ")).isEmpty();
    assertThat(catalog.resolveRealmTarget(" DeMo ", "production")).isEmpty();
  }

  @Test
  void sameTenantCannotExposeTwoVisiblePublicRealmsInOneWorld() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(realm("production", true), realm("seasonal", true)))));

    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isPresent();
    assertThat(catalog.resolvePublicWorld("demo")).isEmpty();
  }

  @Test
  void sameTenantCannotExposeVisiblePublicRealmsAcrossDifferentWorlds() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo", "Demo World", List.of(realm("production", true))),
                new GameplayWorldCatalog.WorldView(
                    "sandbox", "Builder Sandbox", List.of(realm("production", true)))));

    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isPresent();
    assertThat(catalog.resolveWorld("sandbox")).isPresent();
    assertThat(catalog.resolvePublicWorld("demo")).isEmpty();
    assertThat(catalog.resolvePublicWorld("sandbox")).isEmpty();
  }

  @Test
  void privateOnlyTenantIsHiddenFromPublicDiscoveryButRetainedForInternalResolution() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "private-only",
                    "Private Only",
                    List.of(realm("private-a", false), realm("private-b", false)))));

    assertThat(catalog.visibleWorlds()).hasSize(1);
    assertThat(catalog.publicVisibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolvePublicWorld("private-only")).isEmpty();
    assertThat(catalog.resolveWorld("private-only")).isPresent();
  }

  @Test
  void uniqueWorldRemainsVisibleAndResolvable() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(pointer("demo", "Demo World", "production", "Live Realm", 7L, 11L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).hasSize(1);
    assertThat(catalog.browseView().worlds()).extracting("slug").containsExactly("demo");
    assertThat(catalog.resolveWorld("DEMO")).isPresent();
  }

  @Test
  void visibleWorldsDropsCaseInsensitiveRealmSlugCollisions() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("demo", "Demo World", "PRODUCTION", "Live Realm", 1L, 12L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
  }

  @Test
  void visibleWorldsSuppressesCaseInsensitiveWorldSlugCollisionsAcrossTenants() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 7L, 11L, 1L),
                pointer("DEMO", "Other Demo World", "event", "Event Realm", 8L, 12L, 1L),
                pointer(
                    "DEMO", "Other Demo World", "production", "Other Live Realm", 8L, 13L, 2L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.browseRealms("DEMO")).isEmpty();
  }

  @Test
  void visibleWorldsSuppressesCaseInsensitiveWorldSlugCollisionsWithinTenant() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 7L, 11L, 1L),
                pointer("DEMO", "Other Demo World", "event", "Event Realm", 7L, 12L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
  }

  @Test
  void authoritySnapshotResolvesDigitOnlyWorldSlugWithoutTreatingItAsAnOrdinal() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("123", "Numeric World", "production", "Numeric Live", 2L, 21L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.resolveWorldFromAuthoritySnapshot("123"))
        .hasValueSatisfying(world -> assertThat(world.slug()).isEqualTo("123"));
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("2")).isEmpty();
  }

  @Test
  void numericWorldAliasUsesRetainedPublicMenuWhileExplicitSlugRetainsAdmissionResolution() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("private-world", "private", 7L, false),
                worldWithRealm("public-world", "production", 7L, true)));
    GameplayWorldCatalog.DiscoverySnapshot snapshot = catalog.readDiscoverySnapshot();

    assertThat(snapshot.output().worlds())
        .extracting(WorldsViewOutput.WorldEntry::slug)
        .containsExactly("public-world");
    assertThat(catalog.resolveSnapshotOrdinal(snapshot, snapshot.ordinalTargets().getFirst()))
        .hasValueSatisfying(world -> assertThat(world.slug()).isEqualTo("public-world"));
    assertThat(catalog.resolveStableWorld(snapshot, "private-world"))
        .hasValueSatisfying(world -> assertThat(world.slug()).isEqualTo("private-world"));
  }

  @Test
  void resolvesWorldFromOneListSnapshotWithoutReinterpretingItsOrdinal() {
    AtomicInteger supplierCalls = new AtomicInteger();
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              supplierCalls.incrementAndGet();
              return List.of(worldWithTargetRealm("demo", true));
            });

    GameplayWorldCatalog.DiscoverySnapshot snapshot = catalog.readDiscoverySnapshot();
    assertThat(supplierCalls).hasValue(1);
    assertThat(catalog.resolveSnapshotOrdinal(snapshot, snapshot.ordinalTargets().getFirst()))
        .hasValueSatisfying(world -> assertThat(world.slug()).isEqualTo("demo"));
    assertThat(supplierCalls).hasValue(1);
  }

  private static GameplayWorldCatalog.RealmView realm(String slug, boolean publicProduction) {
    return new GameplayWorldCatalog.RealmView(
        slug, slug, 7L, 11L, 1L, true, publicProduction, false, "SHARED", "ALLOW_NEW");
  }
}
