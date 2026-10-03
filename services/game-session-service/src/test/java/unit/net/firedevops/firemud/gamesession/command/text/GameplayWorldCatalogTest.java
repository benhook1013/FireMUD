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
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.jooq.exception.DataAccessException;
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
  void visibleWorldsKeepsSameWorldSlugSeparateAcrossTenants() {
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                pointer("demo", "Demo World", "production", "Live Realm", 1L, 11L, 7L),
                pointer("DEMO", "Other Demo", "production", "Other Live", 2L, 21L, 8L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThat(catalog.visibleWorlds())
        .extracting(GameplayWorldCatalog.WorldView::slug)
        .containsExactly("demo", "DEMO");
    assertThat(catalog.resolveWorld("demo")).isEmpty();
    GameplayWorldCatalog.DiscoverySnapshot snapshot = catalog.readDiscoverySnapshot();
    assertThat(snapshot.output().worlds())
        .extracting(WorldsViewOutput.WorldEntry::ordinal)
        .containsExactly(1, 2);
    assertThat(snapshot.output().worlds())
        .extracting(WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo", "DEMO");
    assertThat(snapshot.ordinalTargets())
        .extracting(DirectTextConnectScopeSessionStore.WorldOrdinalTarget::tenantId)
        .containsExactly(1L, 2L);
    assertThat(catalog.resolveSnapshotOrdinal(snapshot, snapshot.ordinalTargets().get(1)))
        .hasValueSatisfying(
            world -> assertThat(world.realms().getFirst().tenantId()).isEqualTo(2L));
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
        .thenThrow(new DataAccessException("authority down"))
        .thenReturn(null);
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::readDiscoverySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
    assertThatThrownBy(catalog::readDiscoverySnapshot)
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
  }

  @Test
  void unexpectedPointerReadDefectPropagates() {
    IllegalStateException defect = new IllegalStateException("unexpected mapping defect");
    when(authorityService.listPointers()).thenThrow(defect);
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(catalog::readDiscoverySnapshot).isSameAs(defect);
  }

  @Test
  void authorityWorldResolutionUsesReadOutageClassificationBeforeSnapshotValidation() {
    when(authorityService.listPointers())
        .thenThrow(new DataAccessException("authority down"))
        .thenReturn(null)
        .thenReturn(
            List.of(pointer("demo", "Demo World", "production", "Live Realm", 0L, 11L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.resolveWorldFromAuthoritySnapshot("demo"))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
    assertThatThrownBy(() -> catalog.resolveWorldFromAuthoritySnapshot("demo"))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
    assertThatThrownBy(() -> catalog.resolveWorldFromAuthoritySnapshot("demo"))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .isNotInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
  }

  @Test
  void publicProductionCardinalityKeepsReadOutageDistinctFromMalformedSnapshot() {
    when(authorityService.listPointers())
        .thenThrow(new DataAccessException("authority down"))
        .thenReturn(
            List.of(pointer("demo", "Demo World", "production", "Live Realm", 0L, 11L, 1L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);

    assertThatThrownBy(() -> catalog.publicProductionRealmCardinality(1L))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
    assertThatThrownBy(() -> catalog.publicProductionRealmCardinality(1L))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerUnavailableException.class)
        .isNotInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class)
        .hasMessageContaining("tenant identity is unavailable");
  }

  @Test
  void matchesCurrentAdmissionPointerReturnsFalseForMalformedTenantSnapshot() {
    CurrentAdmissionTarget target = currentAdmissionTarget();
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "live",
                    "Live Realm",
                    1L,
                    11L,
                    1L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    0L)));

    assertThat(target.catalog().matchesCurrentAdmissionPointer(target.world(), target.realm()))
        .isFalse();
  }

  @Test
  void matchesCurrentAdmissionPointerReturnsFalseForSelectorCollision() {
    CurrentAdmissionTarget target = currentAdmissionTarget();
    when(authorityService.listPointersByTenant(1L))
        .thenReturn(
            List.of(
                publicPointer("demo", "Demo World", 1L, 11L),
                privatePointer("Demo", "Demo World Alias", "preview", "Preview Realm", 1L, 12L)));

    assertThat(target.catalog().matchesCurrentAdmissionPointer(target.world(), target.realm()))
        .isFalse();
  }

  @Test
  void matchesCurrentAdmissionPointerPropagatesTenantReadOutage() {
    CurrentAdmissionTarget target = currentAdmissionTarget();
    when(authorityService.listPointersByTenant(1L))
        .thenThrow(new DataAccessException("authority down"));

    assertThatThrownBy(
            () -> target.catalog().matchesCurrentAdmissionPointer(target.world(), target.realm()))
        .isInstanceOf(GameplayWorldCatalog.AuthorityPointerReadUnavailableException.class);
  }

  @Test
  void matchesCurrentAdmissionPointerPropagatesUnexpectedTenantReadDefect() {
    CurrentAdmissionTarget target = currentAdmissionTarget();
    IllegalStateException defect = new IllegalStateException("unexpected mapping defect");
    when(authorityService.listPointersByTenant(1L)).thenThrow(defect);

    assertThatThrownBy(
            () -> target.catalog().matchesCurrentAdmissionPointer(target.world(), target.realm()))
        .isSameAs(defect);
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
  void realmSnapshotFingerprintsPublicCatalogAndPreservesVisibleResponseTargets() {
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
    GameplayWorldCatalog.RealmView privateChanged =
        new GameplayWorldCatalog.RealmView(
            "preview",
            "Preview Realm",
            7L,
            99L,
            99L,
            true,
            false,
            false,
            "ISOLATED",
            "ALLOW_NEW",
            99L,
            UUID.fromString("a11df0f1-1b57-465e-9c4b-bb34f8153d31"),
            UUID.fromString("b2a958e0-13a2-41d0-9c39-59a96cf31412"));
    GameplayWorldCatalog.WorldView original =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(production, preview));
    GameplayWorldCatalog.WorldView reordered =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(preview, production));
    GameplayWorldCatalog.WorldView changedPrivateAndReordered =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(privateChanged, production));
    GameplayWorldCatalog originalCatalog = GameplayWorldCatalog.forWorldViews(List.of(original));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldViews(List.of(reordered));
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        originalCatalog.realmDiscoverySnapshot(original, List.of(production, preview));

    assertThat(snapshot.ordinalTargets())
        .extracting(DirectTextConnectScopeSessionStore.RealmOrdinalTarget::realmSlug)
        .containsExactly("production", "preview");
    assertThat(catalog.readRealmDiscoverySnapshot(reordered).catalogFingerprint())
        .isEqualTo(snapshot.catalogFingerprint());
    assertThat(catalog.readRealmDiscoverySnapshot(reordered).ordinalTargets())
        .extracting(DirectTextConnectScopeSessionStore.RealmOrdinalTarget::realmSlug)
        .containsExactly("production");
    assertThat(
            catalog.resolveRealmSnapshotOrdinal(
                reordered, snapshot, snapshot.ordinalTargets().get(0)))
        .contains(production);
    assertThat(
            catalog.resolveRealmSnapshotOrdinal(
                reordered, snapshot, snapshot.ordinalTargets().get(1)))
        .contains(preview);

    GameplayWorldCatalog changedPrivateCatalog =
        GameplayWorldCatalog.forWorldViews(List.of(changedPrivateAndReordered));
    assertThat(
            changedPrivateCatalog
                .readRealmDiscoverySnapshot(changedPrivateAndReordered)
                .catalogFingerprint())
        .isEqualTo(snapshot.catalogFingerprint());
    assertThat(
            changedPrivateCatalog.resolveRealmSnapshotOrdinal(
                changedPrivateAndReordered, snapshot, snapshot.ordinalTargets().get(1)))
        .isEmpty();

    GameplayWorldCatalog.RealmView hidden =
        new GameplayWorldCatalog.RealmView(
            "secret",
            "Secret Realm",
            7L,
            13L,
            1L,
            false,
            false,
            false,
            "ISOLATED",
            "ALLOW_NEW",
            31L,
            UUID.randomUUID(),
            UUID.randomUUID());
    assertThatThrownBy(() -> catalog.realmDiscoverySnapshot(original, List.of(hidden)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-visible");

    GameplayWorldCatalog.RealmView reroutedProduction =
        new GameplayWorldCatalog.RealmView(
            production.slug(),
            production.displayName(),
            production.tenantId(),
            production.gameInstanceId() + 1L,
            production.pointerVersion() + 1L,
            production.visible(),
            production.publicProductionRealm(),
            production.requiresCharacterSelection(),
            production.stateScope(),
            production.characterCreationPolicy(),
            production.catalogRevision() + 1L,
            production.realmId(),
            production.playableStateNamespaceId());
    GameplayWorldCatalog.WorldView reroutedWorld =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(reroutedProduction, preview));
    assertThat(
            GameplayWorldCatalog.forWorldViews(List.of(reroutedWorld))
                .readRealmDiscoverySnapshot(reroutedWorld)
                .catalogFingerprint())
        .isNotEqualTo(snapshot.catalogFingerprint());

    GameplayWorldCatalog.RealmView noLongerPublic =
        new GameplayWorldCatalog.RealmView(
            production.slug(),
            production.displayName(),
            production.tenantId(),
            production.gameInstanceId(),
            production.pointerVersion(),
            production.visible(),
            false,
            production.requiresCharacterSelection(),
            production.stateScope(),
            production.characterCreationPolicy(),
            production.catalogRevision() + 1L,
            production.realmId(),
            production.playableStateNamespaceId());
    GameplayWorldCatalog.WorldView policyChangedWorld =
        new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(noLongerPublic, preview));
    assertThat(
            GameplayWorldCatalog.forWorldViews(List.of(policyChangedWorld))
                .readRealmDiscoverySnapshot(policyChangedWorld)
                .catalogFingerprint())
        .isNotEqualTo(snapshot.catalogFingerprint());

    GameplayWorldCatalog.RealmView otherTenantProduction =
        new GameplayWorldCatalog.RealmView(
            "other-live",
            "Other Live Realm",
            8L,
            18L,
            2L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            31L,
            UUID.fromString("c11df0f1-1b57-465e-9c4b-bb34f8153d31"),
            UUID.fromString("d2a958e0-13a2-41d0-9c39-59a96cf31412"));
    GameplayWorldCatalog.WorldView responseWorld =
        new GameplayWorldCatalog.WorldView(
            "demo", "Demo", List.of(production, otherTenantProduction, preview));
    GameplayWorldCatalog.RealmDiscoverySnapshot responseSnapshot =
        GameplayWorldCatalog.forWorldViews(List.of(responseWorld))
            .realmDiscoverySnapshot(responseWorld, List.of(otherTenantProduction, production));
    assertThat(responseSnapshot.ordinalTargets())
        .extracting(DirectTextConnectScopeSessionStore.RealmOrdinalTarget::realmSlug)
        .containsExactly("other-live", "production");
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

  @Test
  void propertyCatalogRealmIdsIncludeWorldAndRemainDeterministic() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    GameplayCatalogProperties.World worldOne = propertyWorld("world-one", 7L, 11L, true);
    worldOne.setRealms(
        List.of(
            worldOne.getRealms().getFirst(),
            propertyRealm(
                "preview-one", 7L, 13L, false, GameplayCatalogProperties.RealmStateScope.ISOLATED),
            propertyRealm(
                "preview-two",
                7L,
                14L,
                false,
                GameplayCatalogProperties.RealmStateScope.ISOLATED)));
    GameplayCatalogProperties.World worldTwo = propertyWorld("world-two", 7L, 12L, false);
    worldTwo.setRealms(
        List.of(
            worldTwo.getRealms().getFirst(),
            propertyRealm(
                "preview-one",
                7L,
                15L,
                false,
                GameplayCatalogProperties.RealmStateScope.ISOLATED)));
    properties.setWorlds(List.of(worldOne, worldTwo));

    GameplayWorldCatalog catalog = TestGameplayWorldCatalogs.fromProperties(properties);
    GameplayWorldCatalog.RealmView firstRealm =
        catalog
            .resolveRealm(catalog.resolveWorld("world-one").orElseThrow(), "shared")
            .orElseThrow();
    GameplayWorldCatalog.RealmView secondRealm =
        catalog
            .resolveRealm(catalog.resolveWorld("world-two").orElseThrow(), "shared")
            .orElseThrow();
    GameplayWorldCatalog repeatedCatalog = TestGameplayWorldCatalogs.fromProperties(properties);
    GameplayWorldCatalog.RealmView repeatedFirstRealm =
        repeatedCatalog
            .resolveRealm(repeatedCatalog.resolveWorld("world-one").orElseThrow(), "shared")
            .orElseThrow();
    GameplayCatalogProperties otherTenantProperties = new GameplayCatalogProperties();
    otherTenantProperties.setWorlds(List.of(propertyWorld("world-one", 8L, 11L, true)));
    GameplayWorldCatalog tenantCatalog =
        TestGameplayWorldCatalogs.fromProperties(otherTenantProperties);
    GameplayWorldCatalog.RealmView otherTenantRealm =
        tenantCatalog
            .resolveRealm(tenantCatalog.resolveWorld("world-one").orElseThrow(), "shared")
            .orElseThrow();
    GameplayWorldCatalog.RealmView firstIsolatedRealm =
        catalog
            .resolveRealm(catalog.resolveWorld("world-one").orElseThrow(), "preview-one")
            .orElseThrow();
    GameplayWorldCatalog.RealmView secondIsolatedRealm =
        catalog
            .resolveRealm(catalog.resolveWorld("world-one").orElseThrow(), "preview-two")
            .orElseThrow();
    GameplayWorldCatalog.RealmView otherWorldIsolatedRealm =
        catalog
            .resolveRealm(catalog.resolveWorld("world-two").orElseThrow(), "preview-one")
            .orElseThrow();
    GameplayCatalogProperties.World otherTenantIsolatedWorld =
        propertyWorld("world-one", 8L, 16L, true);
    otherTenantIsolatedWorld.getRealms().getFirst().setSlug("preview-one");
    otherTenantIsolatedWorld
        .getRealms()
        .getFirst()
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    GameplayWorldCatalog otherTenantIsolatedCatalog =
        TestGameplayWorldCatalogs.fromProperties(
            propertiesWithWorlds(List.of(otherTenantIsolatedWorld)));
    GameplayWorldCatalog.RealmView otherTenantIsolatedRealm =
        otherTenantIsolatedCatalog
            .resolveRealm(
                otherTenantIsolatedCatalog.resolveWorld("world-one").orElseThrow(), "preview-one")
            .orElseThrow();
    GameplayCatalogProperties.World replacedSharedWorld =
        propertyWorld("world-one", 7L, 111L, true);
    GameplayWorldCatalog replacedSharedCatalog =
        TestGameplayWorldCatalogs.fromProperties(
            propertiesWithWorlds(List.of(replacedSharedWorld)));
    GameplayCatalogProperties.World replacedIsolatedWorld =
        propertyWorld("world-one", 7L, 112L, false);
    replacedIsolatedWorld.getRealms().getFirst().setSlug("preview-one");
    replacedIsolatedWorld
        .getRealms()
        .getFirst()
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    GameplayWorldCatalog replacedIsolatedCatalog =
        TestGameplayWorldCatalogs.fromProperties(
            propertiesWithWorlds(List.of(replacedIsolatedWorld)));

    assertThat(firstRealm.realmId()).isNotEqualTo(secondRealm.realmId());
    assertThat(otherTenantRealm.realmId()).isNotEqualTo(firstRealm.realmId());
    assertThat(repeatedFirstRealm.realmId()).isEqualTo(firstRealm.realmId());
    assertThat(firstRealm.playableStateNamespaceId())
        .isEqualTo(secondRealm.playableStateNamespaceId());
    assertThat(firstRealm.playableStateNamespaceId())
        .isNotEqualTo(otherTenantRealm.playableStateNamespaceId());
    assertThat(repeatedFirstRealm.playableStateNamespaceId())
        .isEqualTo(firstRealm.playableStateNamespaceId());
    assertThat(firstIsolatedRealm.playableStateNamespaceId())
        .isNotEqualTo(firstRealm.playableStateNamespaceId())
        .isNotEqualTo(secondIsolatedRealm.playableStateNamespaceId())
        .isNotEqualTo(otherWorldIsolatedRealm.playableStateNamespaceId())
        .isNotEqualTo(otherTenantIsolatedRealm.playableStateNamespaceId());
    assertThat(
            replacedSharedCatalog
                .resolveRealm(
                    replacedSharedCatalog.resolveWorld("world-one").orElseThrow(), "shared")
                .orElseThrow()
                .playableStateNamespaceId())
        .isEqualTo(firstRealm.playableStateNamespaceId());
    assertThat(
            replacedIsolatedCatalog
                .resolveRealm(
                    replacedIsolatedCatalog.resolveWorld("world-one").orElseThrow(), "preview-one")
                .orElseThrow()
                .playableStateNamespaceId())
        .isEqualTo(firstIsolatedRealm.playableStateNamespaceId());
  }

  private static GameplayCatalogProperties.World propertyWorld(
      String worldSlug, long tenantId, long gameInstanceId, boolean publicProductionRealm) {
    GameplayCatalogProperties.Realm realm =
        propertyRealm(
            "shared",
            tenantId,
            gameInstanceId,
            publicProductionRealm,
            GameplayCatalogProperties.RealmStateScope.SHARED);

    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(worldSlug);
    world.setDisplayName(worldSlug);
    world.setRealms(List.of(realm));
    return world;
  }

  private static GameplayCatalogProperties.Realm propertyRealm(
      String realmSlug,
      long tenantId,
      long gameInstanceId,
      boolean publicProductionRealm,
      GameplayCatalogProperties.RealmStateScope stateScope) {
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug(realmSlug);
    realm.setDisplayName(realmSlug);
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setPointerVersion(1L);
    realm.setVisible(true);
    realm.setPublicProductionRealm(publicProductionRealm);
    realm.setStateScope(stateScope);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    return realm;
  }

  private static GameplayCatalogProperties propertiesWithWorlds(
      List<GameplayCatalogProperties.World> worlds) {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(worlds);
    return properties;
  }

  private CurrentAdmissionTarget currentAdmissionTarget() {
    when(authorityService.listPointers())
        .thenReturn(List.of(publicPointer("demo", "Demo World", 1L, 11L)));
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);
    GameplayWorldCatalog.WorldView world =
        catalog.resolveWorldFromAuthoritySnapshot("demo").orElseThrow();
    GameplayWorldCatalog.RealmView realm = catalog.resolveRealm(world, "live").orElseThrow();
    return new CurrentAdmissionTarget(catalog, world, realm);
  }

  private record CurrentAdmissionTarget(
      GameplayWorldCatalog catalog,
      GameplayWorldCatalog.WorldView world,
      GameplayWorldCatalog.RealmView realm) {}

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
}
