package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
  void directPublicProductionGuardReadsOnlyTheExpectedTenant() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointers())
        .thenReturn(
            Arrays.asList(
                expectedPointer,
                new GameplayAdmissionPointerSnapshot(
                    "broken",
                    "Broken World",
                    "production",
                    "Live Realm",
                    2L,
                    22L,
                    0L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    when(authorityService.listPointersByTenant(1L)).thenReturn(List.of(expectedPointer));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer);

    verify(authorityService).listPointersByTenant(1L);
    verify(authorityService, never()).listPointers();
  }

  @Test
  void directPublicProductionGuardRejectsAmbiguousTenantCatalog() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(expectedPointer, publicPointer("alternate", "Alternate World", 1L, 12L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("incomplete or ambiguous");
  }

  @Test
  void directPublicProductionGuardRejectsIncompleteTenantRows() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                expectedPointer,
                new GameplayAdmissionPointerSnapshot(
                    "broken",
                    "Broken World",
                    "preview",
                    "Preview",
                    1L,
                    22L,
                    1L,
                    false,
                    false,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    0L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("pointer is incomplete");
  }

  @Test
  void directPublicProductionGuardRejectsMismatchedTenantRows() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(List.of(publicPointer("other", "Other World", 2L, 22L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("escaped the requested tenant scope");
  }

  @Test
  void directPublicProductionGuardRejectsRowsWithUnknownTenant() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(List.of(publicPointer("unknown", "Unknown World", 0L, 22L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("escaped the requested tenant scope");
  }

  @Test
  void directPublicProductionGuardRejectsExpectedPointerMismatch() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 99L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(List.of(publicPointer("demo", "Demo World", 1L, 11L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("not unique");
  }

  @Test
  void directPublicProductionGuardRejectsFoldedVisiblePrivateRealmAlias() {
    GameplayAdmissionPointerSnapshot publicRealm = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                publicRealm,
                privatePointer("demo", "Demo World", "LIVE", "Private Alias", 1L, 12L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(publicRealm))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("ambiguous folded selectors");
  }

  @Test
  void directPublicProductionGuardRejectsFoldedVisibleWorldAlias() {
    GameplayAdmissionPointerSnapshot publicRealm = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                publicRealm,
                privatePointer("Demo", "Demo World Alias", "preview", "Preview", 1L, 12L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(publicRealm))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("ambiguous folded selectors");
  }

  @Test
  void directPrivateRealmGuardAcceptsRealmFromHealthyTenantCatalog() {
    GameplayAdmissionPointerSnapshot expectedPrivateRealm =
        pointer("private", "Private World", "preview", "Preview", 1L, 12L, 1L);
    GameplayAdmissionPointerSnapshot publicRealm = publicPointer("demo", "Demo World", 1L, 11L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(List.of(expectedPrivateRealm, publicRealm));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    catalog.requireHealthyVisiblePrivateRealm(expectedPrivateRealm);

    verify(authorityService).listPointersByTenant(1L);
    verify(authorityService, never()).listPointers();
  }

  @Test
  void directPrivateRealmGuardRejectsFoldedVisiblePublicRealmAlias() {
    GameplayAdmissionPointerSnapshot publicRealm = publicPointer("demo", "Demo World", 1L, 11L);
    GameplayAdmissionPointerSnapshot privateRealm =
        privatePointer("demo", "Demo World", "LIVE", "Private Alias", 1L, 12L);
    when(authorityService.listPointersByTenant(1L)).thenReturn(List.of(publicRealm, privateRealm));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireHealthyVisiblePrivateRealm(privateRealm))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("ambiguous folded selectors");
  }

  @Test
  void directPrivateRealmGuardRejectsTenantWithoutPublicRealm() {
    GameplayAdmissionPointerSnapshot expectedPrivateRealm =
        pointer("private", "Private World", "preview", "Preview", 1L, 12L, 1L);
    when(authorityService.listPointersByTenant(1L)).thenReturn(List.of(expectedPrivateRealm));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireHealthyVisiblePrivateRealm(expectedPrivateRealm))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("incomplete or ambiguous");
  }

  @Test
  void directPrivateRealmGuardRejectsAmbiguousPublicRealmCatalog() {
    GameplayAdmissionPointerSnapshot expectedPrivateRealm =
        pointer("private", "Private World", "preview", "Preview", 1L, 13L, 1L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                expectedPrivateRealm,
                publicPointer("alpha", "Alpha World", 1L, 11L),
                publicPointer("beta", "Beta World", 1L, 12L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireHealthyVisiblePrivateRealm(expectedPrivateRealm))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("incomplete or ambiguous");
  }

  @Test
  void directPrivateRealmGuardRejectsIncompleteTenantPointer() {
    GameplayAdmissionPointerSnapshot expectedPrivateRealm =
        pointer("private", "Private World", "preview", "Preview", 1L, 12L, 1L);
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                expectedPrivateRealm,
                publicPointer("demo", "Demo World", 1L, 11L),
                new GameplayAdmissionPointerSnapshot(
                    "broken",
                    "Broken World",
                    "preview",
                    "Preview",
                    1L,
                    99L,
                    1L,
                    false,
                    false,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    0L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.requireHealthyVisiblePrivateRealm(expectedPrivateRealm))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("pointer is incomplete");
  }

  @Test
  void syntheticCatalogFixtureCannotAuthorizeDirectPublicProductionRead() {
    GameplayAdmissionPointerSnapshot expectedPointer = publicPointer("demo", "Demo World", 1L, 11L);
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo",
                    "Demo World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "live",
                            "Live Realm",
                            1L,
                            11L,
                            1L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW")))));

    assertThatThrownBy(() -> catalog.requireUniqueVisiblePublicProductionRealm(expectedPointer))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("tenant gameplay pointer list is unavailable");
  }

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
  void authoritySnapshotSuppressesTenantWithMultiplePublicRealmsAndKeepsHealthyTenant() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("alpha", "Alpha World", 1L, 11L),
                publicPointer("beta", "Beta World", 1L, 12L),
                publicPointer("healthy", "Healthy World", 2L, 21L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorldsFromAuthoritySnapshot())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("healthy");
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("alpha")).isEmpty();
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("healthy")).isPresent();
  }

  @Test
  void authoritySnapshotSuppressesTenantWithoutPublicRealmAndKeepsHealthyTenant() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("private", "Private World", "preview", "Preview", 1L, 11L, 7L),
                publicPointer("healthy", "Healthy World", 2L, 21L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorldsFromAuthoritySnapshot())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("healthy");
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("private")).isEmpty();
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("healthy")).isPresent();
  }

  @Test
  void authoritySnapshotSuppressesTenantWithIncompletePointerAndKeepsHealthyTenant() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("invalid", "Invalid World", 1L, 11L),
                new GameplayAdmissionPointerSnapshot(
                    "broken",
                    "Broken World",
                    "production",
                    "Live Realm",
                    1L,
                    12L,
                    0L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW"),
                publicPointer("healthy", "Healthy World", 2L, 21L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorldsFromAuthoritySnapshot())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("healthy");
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("invalid")).isEmpty();
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("broken")).isEmpty();
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("healthy")).isPresent();
  }

  @Test
  void textBrowseAndResolveSuppressTenantWithIncompletePointerAndKeepHealthyTenant() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("invalid", "Invalid World", 1L, 11L),
                new GameplayAdmissionPointerSnapshot(
                    "broken",
                    "Broken World",
                    "production",
                    "Live Realm",
                    1L,
                    12L,
                    0L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW"),
                publicPointer("healthy", "Healthy World", 2L, 21L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("healthy");
    assertThat(catalog.resolveWorld("invalid")).isEmpty();
    assertThat(catalog.resolveWorld("broken")).isEmpty();
    assertThat(catalog.resolveWorld("healthy")).isPresent();
  }

  @Test
  void authoritySnapshotFailsClosedWhenPointerTenantCannotBeIdentified() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("healthy", "Healthy World", 2L, 21L),
                pointer("unknown", "Unknown Tenant", "production", "Live Realm", 0L, 11L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::visibleWorldsFromAuthoritySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("tenant identity is unavailable");
  }

  @Test
  void authoritySnapshotFailsClosedWhenPointerEntryIsNull() {
    when(authorityService.listPointers())
        .thenReturn(Arrays.asList(publicPointer("healthy", "Healthy World", 2L, 21L), null));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::visibleWorldsFromAuthoritySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("pointer identity is unavailable");
  }

  @Test
  void authoritySnapshotFailsClosedWhenPointerListIsUnavailable() {
    when(authorityService.listPointers()).thenReturn(null);
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::visibleWorldsFromAuthoritySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("pointer list is unavailable");
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

  @Test
  void authoritySnapshotSuppressesCrossTenantWorldSlugCollisionAndKeepsOtherWorlds() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("shared-world", "Shared World", 1L, 11L),
                pointer("shared-world", "Shared World", "first-realm", "First Realm", 1L, 12L, 1L),
                publicPointer("shared-world", "Shared World", 2L, 21L),
                pointer(
                    "shared-world", "Shared World", "second-realm", "Second Realm", 2L, 22L, 1L),
                publicPointer("unrelated", "Unrelated World", 3L, 31L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorldsFromAuthoritySnapshot())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("unrelated");
  }

  @Test
  void authoritySnapshotSuppressesSameTenantCaseFoldedWorldSlugCollision() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("demo", "Demo World", 1L, 11L),
                pointer("Demo", "Demo World", "preview", "Preview Realm", 1L, 12L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.resolveWorld("demo")).isEmpty();
    assertThat(catalog.resolveWorld("DEMO")).isEmpty();
    assertThat(catalog.browseView().worlds()).isEmpty();
  }

  @Test
  void authoritySnapshotSuppressesSameTenantCaseFoldedRealmSlugCollision() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("demo", "Demo World", 1L, 11L),
                privatePointer("demo", "Demo World", "production", "Private Production", 1L, 12L),
                privatePointer("demo", "Demo World", "Production", "Private Alias", 1L, 13L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);
    GameplayWorldCatalog.WorldView world = catalog.resolveWorld("demo").orElseThrow();

    assertThat(catalog.visibleRealms(world))
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("live");
    assertThat(catalog.resolveRealm(world, "production")).isEmpty();
    assertThat(catalog.resolveRealmForAdmission(world, "PRODUCTION")).isEmpty();
    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo");
  }

  @Test
  void fixtureCatalogSuppressesCaseFoldedWorldAndRealmSelectorCollisions() {
    GameplayWorldCatalog worldCollisionCatalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("demo", "production", 7L, true),
                worldWithRealm("Demo", "preview", 7L, false)));
    GameplayWorldCatalog.WorldView realmCollisionWorld = worldWithTargetRealm("Production", true);
    GameplayWorldCatalog realmCollisionCatalog =
        GameplayWorldCatalog.forWorldViews(List.of(realmCollisionWorld));

    assertThat(worldCollisionCatalog.resolveWorld("demo")).isEmpty();
    assertThat(worldCollisionCatalog.browseView().worlds()).isEmpty();
    assertThat(realmCollisionCatalog.resolveWorld("demo")).isEmpty();
    assertThat(realmCollisionCatalog.resolveRealmForAdmission(realmCollisionWorld, "production"))
        .isEmpty();
  }

  @Test
  void singleCasefoldedSelectorStillResolvesOneWorldAndRealm() {
    GameplayWorldCatalog.WorldView sourceWorld = worldWithTargetRealm("Preview", true);
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(sourceWorld));
    GameplayWorldCatalog.WorldView world = catalog.resolveWorld("DEMO").orElseThrow();

    assertThat(catalog.resolveRealm(world, "pRoDuCtIoN")).isPresent();
    assertThat(catalog.resolveRealmForAdmission(world, " pReViEw ")).isPresent();
  }

  @Test
  void publicTextDiscoveryOmitsVisiblePrivateAndPrivateOnlyRealms() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("demo", "Demo World", 1L, 11L),
                pointer("demo", "Demo World", "preview", "Private Realm", 1L, 12L, 1L),
                pointer(
                    "private-world", "Private World", "playtest", "Private Only", 1L, 13L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo");
    assertThat(catalog.browseRealms("demo").orElseThrow().realms())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput.RealmEntry
                ::realmSlug)
        .containsExactly("live");
    assertThat(catalog.browseRealms("private-world")).isEmpty();
    assertThat(catalog.resolveWorld("private-world")).isPresent();
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
  void browseViewUsesOneWorldSnapshotForDefaultRealmAndCardinalityChecks() {
    AtomicInteger supplierCalls = new AtomicInteger();
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () ->
                supplierCalls.getAndIncrement() == 0
                    ? List.of(worldWithRealm("demo", "production", 7L, true))
                    : List.of(worldWithRealm("demo", "preview", 7L, false)));

    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo");
    assertThat(supplierCalls).hasValue(1);
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

  private static GameplayAdmissionPointerSnapshot privatePointer(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId) {
    UUID realmId = stableId("realm/" + tenantId + "/" + worldSlug + "/" + realmSlug);
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        1L,
        true,
        false,
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
