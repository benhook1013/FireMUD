package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.junit.jupiter.api.Test;
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
  void sameTenantCannotExposeTwoVisiblePublicRealmsInOneWorld() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(realm("production", true), realm("seasonal", true)))));

    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.resolvePublicWorld("demo")).isEmpty();
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

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
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
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.resolveWorld("sandbox")).isEmpty();
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
    assertThat(catalog.resolveRealmForAdmission(world, "hidden-production")).isEmpty();
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
  void resolvesWorldFromOneListSnapshot() {
    AtomicInteger supplierCalls = new AtomicInteger();
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              supplierCalls.incrementAndGet();
              return List.of(
                  new GameplayWorldCatalog.WorldView(
                      "demo", "Demo World", List.of(realm("production", true))));
            });

    assertThat(catalog.resolveWorld("1")).isPresent();
    assertThat(supplierCalls).hasValue(1);
  }

  @Test
  void ambiguousPublicRealmSelectorInvalidatesTenantCatalog() {
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
    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.resolveRealmTarget("demo", "production")).isEmpty();
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
  void tenantWithNoVisiblePublicRealmFailsClosedForDiscoveryAndSelection() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "private-only",
                    "Private Only",
                    List.of(realm("private-a", false), realm("private-b", false)))));

    assertThat(catalog.visibleWorlds()).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
    assertThat(catalog.resolvePublicWorld("private-only")).isEmpty();
    assertThat(catalog.resolveWorld("private-only")).isEmpty();
  }

  @Test
  void numericWorldAliasUsesPublicMenuWhileExplicitSlugRetainsAdmissionResolution() {
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
                    "public-world",
                    "Public World",
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
                            "ALLOW_NEW")))));

    assertThat(catalog.resolveWorld("1"))
        .map(GameplayWorldCatalog.WorldView::slug)
        .contains("public-world");
    assertThat(catalog.resolveWorld("private-world")).isPresent();
    assertThat(catalog.resolvePublicWorld("1"))
        .map(GameplayWorldCatalog.WorldView::slug)
        .contains("public-world");
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

  private static GameplayWorldCatalog.RealmView realm(
      String realmSlug, boolean publicProductionRealm) {
    return new GameplayWorldCatalog.RealmView(
        realmSlug, "Realm", 7L, 11L, 1L, true, publicProductionRealm, false, "SHARED", "ALLOW_NEW");
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
        "ALLOW_NEW");
  }
}
