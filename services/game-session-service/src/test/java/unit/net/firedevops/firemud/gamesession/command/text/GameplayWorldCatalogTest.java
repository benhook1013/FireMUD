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
import java.util.concurrent.atomic.AtomicReference;
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
  void authoritySnapshotReadsOnceAndRetainsSuppressedPointerComparisonEvidence() {
    GameplayAdmissionPointerSnapshot visible = publicPointer("demo", "Demo World", 1L, 11L);
    GameplayAdmissionPointerSnapshot hidden =
        new GameplayAdmissionPointerSnapshot(
            "hidden",
            "Hidden",
            "production",
            "Hidden Realm",
            2L,
            21L,
            1L,
            false,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            stableId("hidden-realm"),
            stableId("hidden-namespace"));
    AtomicReference<List<GameplayAdmissionPointerSnapshot>> source =
        new AtomicReference<>(List.of(visible, hidden));
    AtomicInteger reads = new AtomicInteger();
    when(authorityService.listPointers())
        .thenAnswer(
            invocation -> {
              reads.incrementAndGet();
              return source.get();
            });
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    GameplayWorldCatalog.DiscoverySnapshot original = catalog.discoverySnapshot();
    assertThat(reads).hasValue(1);
    assertThat(original.worlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("demo");
    source.set(
        List.of(
            visible,
            new GameplayAdmissionPointerSnapshot(
                hidden.worldSlug(),
                hidden.worldDisplayName(),
                hidden.realmSlug(),
                hidden.realmDisplayName(),
                hidden.tenantId(),
                hidden.gameInstanceId(),
                2L,
                hidden.visible(),
                hidden.publicProductionRealm(),
                hidden.requiresCharacterSelection(),
                hidden.stateScope(),
                hidden.characterCreationPolicy(),
                hidden.catalogRevision(),
                hidden.realmId(),
                hidden.playableStateNamespaceId())));

    GameplayWorldCatalog.DiscoverySnapshot changed = catalog.discoverySnapshot();
    assertThat(reads).hasValue(2);
    assertThat(changed.worlds()).isEqualTo(original.worlds());
    assertThat(changed.fingerprint()).isNotEqualTo(original.fingerprint());
  }

  @Test
  void discoverySnapshotReadsItsSourceOnce() {
    AtomicInteger supplierCalls = new AtomicInteger();
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              supplierCalls.incrementAndGet();
              return List.of(worldWithRealm("demo", "production", 7L, true));
            });

    GameplayWorldCatalog.DiscoverySnapshot snapshot = catalog.discoverySnapshot();

    assertThat(supplierCalls).hasValue(1);
    assertThat(snapshot.worlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("demo");
  }

  @Test
  void callerFreeRealmDiscoveryReadsOnceAndDoesNotExposePrivateOrHiddenRealms() {
    AtomicInteger supplierCalls = new AtomicInteger();
    GameplayWorldCatalog.WorldView selectedWorld =
        new GameplayWorldCatalog.WorldView(
            "demo",
            "Demo World",
            List.of(
                realmView("live", "Live Realm", 7L, 11L, 1L, true, true, "ALLOW_NEW"),
                realmView("preview", "Preview Realm", 7L, 12L, 2L, true, false, "ALLOW_NEW"),
                realmView("hidden", "Hidden Realm", 7L, 13L, 3L, false, false, "ALLOW_NEW")));
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              supplierCalls.incrementAndGet();
              return List.of(selectedWorld);
            });
    GameplayWorldCatalog.DiscoverySnapshot discovery = catalog.discoverySnapshot();
    assertThat(supplierCalls).hasValue(1);

    GameplayWorldCatalog.RealmDiscoverySnapshot realms =
        catalog.realmDiscoverySnapshot(discovery.worlds().getFirst()).orElseThrow();

    assertThat(supplierCalls).hasValue(2);
    assertThat(realms.world().slug()).isEqualTo(discovery.worlds().getFirst().slug());
    assertThat(realms.realms())
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("live");
    assertThat(realms.world().realms())
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("live");
    assertThat(realms.realms().getFirst())
        .isEqualTo(discovery.worlds().getFirst().realms().getFirst());
    assertThat(realms.fingerprint()).isEqualTo(discovery.fingerprint());
  }

  @Test
  void retainedDiscoverySnapshotOrderAndDataDoNotFollowLaterSupplierReordering() {
    AtomicReference<List<GameplayWorldCatalog.WorldView>> source =
        new AtomicReference<>(
            List.of(
                worldWithRealm("alpha", "production", 1L, true),
                worldWithRealm("beta", "production", 2L, true)));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldSupplier(source::get);

    GameplayWorldCatalog.DiscoverySnapshot responseSnapshot = catalog.discoverySnapshot();
    source.set(
        List.of(
            worldWithRealm("beta", "production", 2L, true),
            worldWithRealm("alpha", "production", 1L, true)));

    assertThat(catalog.discoverySnapshot().worlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("beta", "alpha");
    assertThat(responseSnapshot.worlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("alpha", "beta");
    assertThat(responseSnapshot.worlds().getFirst().displayName()).isEqualTo("alpha");
    assertThatThrownBy(() -> responseSnapshot.worlds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> responseSnapshot.worlds().getFirst().realms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void discoveryFingerprintChangesWithCurrentTenantRealmNamespaceRevisionPointerAndPolicyData() {
    GameplayWorldCatalog.RealmView baselineRealm =
        realmView("live", "Live Realm", 7L, 11L, 3L, true, true, "ALLOW_NEW");
    GameplayWorldCatalog.WorldView baselineWorld =
        new GameplayWorldCatalog.WorldView("demo", "Demo World", List.of(baselineRealm));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> source =
        new AtomicReference<>(List.of(baselineWorld));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldSupplier(source::get);
    String baselineFingerprint = catalog.discoverySnapshot().fingerprint();

    List<GameplayWorldCatalog.RealmView> changedRealms =
        List.of(
            copyRealm(
                baselineRealm,
                8L,
                "live",
                11L,
                3L,
                "ALLOW_NEW",
                baselineRealm.realmId(),
                baselineRealm.playableStateNamespaceId()),
            copyRealm(
                baselineRealm,
                7L,
                "changed-realm",
                11L,
                3L,
                "ALLOW_NEW",
                baselineRealm.realmId(),
                baselineRealm.playableStateNamespaceId()),
            copyRealm(
                baselineRealm,
                7L,
                "live",
                12L,
                3L,
                "ALLOW_NEW",
                baselineRealm.realmId(),
                baselineRealm.playableStateNamespaceId()),
            copyRealm(
                baselineRealm,
                7L,
                "live",
                11L,
                3L,
                "ALLOW_NEW",
                stableId("other-realm"),
                baselineRealm.playableStateNamespaceId()),
            copyRealm(
                baselineRealm,
                7L,
                "live",
                11L,
                3L,
                "ALLOW_NEW",
                baselineRealm.realmId(),
                stableId("other-namespace")),
            copyRealm(
                baselineRealm,
                7L,
                "live",
                11L,
                3L,
                "ALLOW_NEW",
                baselineRealm.realmId(),
                baselineRealm.playableStateNamespaceId(),
                4L),
            copyRealm(
                baselineRealm,
                7L,
                "live",
                11L,
                4L,
                "ALLOW_NEW",
                baselineRealm.realmId(),
                baselineRealm.playableStateNamespaceId()),
            copyRealm(
                baselineRealm,
                7L,
                "live",
                11L,
                3L,
                "DENY_NEW",
                baselineRealm.realmId(),
                baselineRealm.playableStateNamespaceId()),
            copyRealmWithStateScope(baselineRealm, "ISOLATED"));

    for (GameplayWorldCatalog.RealmView changedRealm : changedRealms) {
      source.set(
          List.of(new GameplayWorldCatalog.WorldView("demo", "Demo World", List.of(changedRealm))));
      assertThat(catalog.discoverySnapshot().fingerprint())
          .as("changed catalog evidence for %s", changedRealm)
          .isNotEqualTo(baselineFingerprint);
    }
  }

  @Test
  void discoverySnapshotPreservesTenantCollisionAndUnavailableSourceRefusals() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                publicPointer("shared", "Shared World", 1L, 11L),
                publicPointer("shared", "Shared World", 2L, 21L),
                publicPointer("healthy", "Healthy World", 3L, 31L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.discoverySnapshot().worlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("healthy");

    when(authorityService.listPointers()).thenReturn(null);
    assertThatThrownBy(catalog::discoverySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .hasMessageContaining("pointer list is unavailable");
  }

  @Test
  void discoveryFingerprintIncludesHiddenRowsWithoutExposingThemInTheWorldProjection() {
    GameplayWorldCatalog.RealmView publicRealm =
        realmView("live", "Live Realm", 7L, 11L, 1L, true, true, "ALLOW_NEW");
    GameplayWorldCatalog.RealmView hiddenRealm =
        realmView("hidden", "Hidden Realm", 7L, 12L, 2L, false, false, "ALLOW_NEW");
    AtomicReference<List<GameplayWorldCatalog.WorldView>> source =
        new AtomicReference<>(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo", "Demo World", List.of(publicRealm, hiddenRealm))));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldSupplier(source::get);
    GameplayWorldCatalog.DiscoverySnapshot initial = catalog.discoverySnapshot();

    source.set(
        List.of(
            new GameplayWorldCatalog.WorldView(
                "demo",
                "Demo World",
                List.of(
                    publicRealm,
                    realmView(
                        "hidden",
                        "Changed Hidden Realm",
                        7L,
                        12L,
                        2L,
                        false,
                        false,
                        "ALLOW_NEW")))));
    GameplayWorldCatalog.DiscoverySnapshot changed = catalog.discoverySnapshot();

    assertThat(initial.worlds().getFirst().realms())
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("live");
    assertThat(changed.worlds().getFirst().realms())
        .extracting(GameplayWorldCatalog.RealmView::slug)
        .containsExactly("live");
    assertThat(changed.fingerprint()).isNotEqualTo(initial.fingerprint());
  }

  @Test
  void realmDiscoverySnapshotRefusesAWorldEntryThatIsNoLongerCurrent() {
    AtomicReference<List<GameplayWorldCatalog.WorldView>> source =
        new AtomicReference<>(List.of(worldWithRealm("demo", "production", 7L, true)));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldSupplier(source::get);
    GameplayWorldCatalog.WorldView selectedWorld = catalog.discoverySnapshot().worlds().getFirst();
    source.set(List.of(worldWithRealm("other", "production", 7L, true)));

    assertThat(catalog.realmDiscoverySnapshot(selectedWorld)).isEmpty();
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
  void numericWorldSelectionRequiresARetainedTransportMenuRatherThanAFreshCatalog() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("invalid", "preview", 7L, false),
                worldWithRealm("demo", "production", 8L, true)));

    assertThat(catalog.browseView().worlds())
        .extracting(
            net.firedevops.firemud.gamesession.presentation.WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo");
    assertThat(catalog.resolveWorld("1")).isEmpty();
    assertThat(catalog.resolveWorldFromAuthoritySnapshot("1")).isEmpty();
    assertThat(catalog.resolvePublicWorldFromAuthoritySnapshot("1")).isEmpty();
    assertThat(catalog.browseRealms("1")).isEmpty();
    assertThat(catalog.resolveWorld("invalid")).isPresent();
  }

  @Test
  void commandReadSnapshotUsesOneActualPointerListForSelectionDefaultAndCardinality() {
    GameplayAdmissionPointerSnapshot original = publicPointer("demo", "Demo World", 7L, 11L);
    when(authorityService.listPointers()).thenReturn(List.of(original), List.of());
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    GameplayWorldCatalog.ReadSnapshot snapshot = catalog.readSnapshot();
    GameplayWorldCatalog.WorldView world = snapshot.resolveWorld("demo").orElseThrow();

    assertThat(snapshot.resolveDefaultRealm(world)).contains(world.realms().getFirst());
    assertThat(snapshot.hasValidPublicProductionRealm(7L)).isTrue();
    assertThat(snapshot.resolveWorld("demo")).contains(world);
    verify(authorityService).listPointers();
    verify(authorityService, never()).listPointersByTenant(Mockito.anyLong());
    assertThat(catalog.readSnapshot().resolveWorld("demo")).isEmpty();
  }

  @Test
  void unboundNumericRealmSelectorsCannotBecomeRealmSlugs() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(List.of(worldWithRealm("demo", "1", 7L, true)));
    GameplayWorldCatalog.WorldView world = catalog.resolveWorld("demo").orElseThrow();

    assertThat(catalog.resolveRealm(world, "1")).isEmpty();
    assertThat(catalog.resolveRealmForAdmission(world, "1")).isEmpty();
  }

  @Test
  void defaultRealmNeverChoosesTheFirstOfSeveralPrivateRealms() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                worldWithRealm("production-world", "live", 7L, true),
                new GameplayWorldCatalog.WorldView(
                    "private-world",
                    "Private World",
                    List.of(
                        realmView("alpha", "Alpha", 7L, 12L, 1L, true, false, "ALLOW_NEW"),
                        realmView("beta", "Beta", 7L, 13L, 1L, true, false, "ALLOW_NEW")))));

    assertThat(catalog.resolveDefaultRealm(catalog.resolveWorld("private-world").orElseThrow()))
        .isEmpty();
  }

  private static GameplayWorldCatalog.RealmView realmView(
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      String characterCreationPolicy) {
    UUID realmId = stableId("realm/" + tenantId + "/demo/" + realmSlug);
    return new GameplayWorldCatalog.RealmView(
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        visible,
        publicProductionRealm,
        false,
        "SHARED",
        characterCreationPolicy,
        1L,
        realmId,
        stableId("namespace/" + realmId));
  }

  private static GameplayWorldCatalog.RealmView copyRealm(
      GameplayWorldCatalog.RealmView realm,
      long tenantId,
      String realmSlug,
      long gameInstanceId,
      long pointerVersion,
      String characterCreationPolicy,
      UUID realmId,
      UUID playableStateNamespaceId) {
    return copyRealm(
        realm,
        tenantId,
        realmSlug,
        gameInstanceId,
        pointerVersion,
        characterCreationPolicy,
        realmId,
        playableStateNamespaceId,
        realm.catalogRevision());
  }

  private static GameplayWorldCatalog.RealmView copyRealm(
      GameplayWorldCatalog.RealmView realm,
      long tenantId,
      String realmSlug,
      long gameInstanceId,
      long pointerVersion,
      String characterCreationPolicy,
      UUID realmId,
      UUID playableStateNamespaceId,
      long catalogRevision) {
    return new GameplayWorldCatalog.RealmView(
        realmSlug,
        realm.displayName(),
        tenantId,
        gameInstanceId,
        pointerVersion,
        realm.visible(),
        realm.publicProductionRealm(),
        realm.requiresCharacterSelection(),
        realm.stateScope(),
        characterCreationPolicy,
        catalogRevision,
        realmId,
        playableStateNamespaceId);
  }

  private static GameplayWorldCatalog.RealmView copyRealmWithStateScope(
      GameplayWorldCatalog.RealmView realm, String stateScope) {
    return new GameplayWorldCatalog.RealmView(
        realm.slug(),
        realm.displayName(),
        realm.tenantId(),
        realm.gameInstanceId(),
        realm.pointerVersion(),
        realm.visible(),
        realm.publicProductionRealm(),
        realm.requiresCharacterSelection(),
        stateScope,
        realm.characterCreationPolicy(),
        realm.catalogRevision(),
        realm.realmId(),
        realm.playableStateNamespaceId());
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
