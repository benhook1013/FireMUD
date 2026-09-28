package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
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
