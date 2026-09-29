package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
  void visibleWorldsDropsWorldSlugSharedAcrossTenants() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("DEMO", "Other Demo", "production", "Other Live", 2L, 21L, 8L),
                pointer("other", "Other World", "production", "Other Live", 1L, 22L, 9L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("other");
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.publicProductionRealmCardinality(1L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.MULTIPLE);
    assertThat(catalog.readDiscoverySnapshot().output().worlds()).isEmpty();
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
}
