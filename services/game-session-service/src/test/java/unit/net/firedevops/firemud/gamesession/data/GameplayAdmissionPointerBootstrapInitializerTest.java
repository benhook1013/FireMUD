package net.firedevops.firemud.gamesession.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamesession.config.GameplayAdmissionPointerBootstrapProperties;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class GameplayAdmissionPointerBootstrapInitializerTest {
  @Mock private GameplayAdmissionPointerRepository pointerRepository;
  @Mock private GameplayAdmissionPointerAuthorityService authorityService;

  private GameplayAdmissionPointerBootstrapProperties properties;
  private GameplayAdmissionPointerBootstrapInitializer initializer;

  @BeforeEach
  void setUp() {
    properties = new GameplayAdmissionPointerBootstrapProperties();
    initializer =
        new GameplayAdmissionPointerBootstrapInitializer(
            pointerRepository, authorityService, properties);
  }

  @Test
  void runSeedsBootstrapPointersWhenAuthorityStoreIsEmpty() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false),
            pointerSeed("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, true)));

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    ArgumentCaptor<GameplayAdmissionPointerMutation> mutationCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointerMutation.class);
    verify(authorityService, org.mockito.Mockito.times(2)).upsertPointer(mutationCaptor.capture());
    List<GameplayAdmissionPointerMutation> mutations = mutationCaptor.getAllValues();
    assertEquals(2, mutations.size());
    assertEquals("demo", mutations.get(0).worldSlug());
    assertEquals("Demo World", mutations.get(0).worldDisplayName());
    assertEquals("production", mutations.get(0).realmSlug());
    assertEquals("Live Realm", mutations.get(0).realmDisplayName());
    assertEquals(1L, mutations.get(0).tenantId());
    assertEquals(1L, mutations.get(0).gameInstanceId());
    assertEquals("SHARED", mutations.get(0).stateScope());
    assertEquals("ALLOW_NEW", mutations.get(0).characterCreationPolicy());
    assertEquals("system/bootstrap", mutations.get(0).actorPrincipal());
    assertEquals("Initial gameplay pointer bootstrap", mutations.get(0).reason());
    assertEquals("bootstrap:1:1:demo:production", mutations.get(0).controlPlaneRequestId());
    assertEquals("sandbox", mutations.get(1).worldSlug());
    assertTrue(mutations.get(1).requiresCharacterSelection());
  }

  @Test
  void runDoesNothingWhenAuthorityStoreAlreadyHasPointers() throws Exception {
    when(pointerRepository.count()).thenReturn(3L);

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    verify(authorityService, never()).upsertPointer(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void runSkipsBlankBootstrapPointerSeeds() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        new java.util.ArrayList<>(
            java.util.Arrays.asList(
                pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false),
                pointerSeed("", "Broken World", "production", "Live Realm", 1L, 2L, false),
                pointerSeed("sandbox", "Builder Sandbox", "", "Live Realm", 1L, 3L, true),
                null)));

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    ArgumentCaptor<GameplayAdmissionPointerMutation> mutationCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointerMutation.class);
    verify(authorityService).upsertPointer(mutationCaptor.capture());
    assertEquals("demo", mutationCaptor.getValue().worldSlug());
  }

  @Test
  void runDefaultsNullEnumFieldsDuringBootstrapMutation() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    GameplayAdmissionPointerBootstrapProperties.PointerSeed pointer =
        pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false);
    pointer.setStateScope(null);
    pointer.setCharacterCreationPolicy(null);
    properties.setPointers(List.of(pointer));

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    ArgumentCaptor<GameplayAdmissionPointerMutation> mutationCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointerMutation.class);
    verify(authorityService).upsertPointer(mutationCaptor.capture());
    assertEquals("SHARED", mutationCaptor.getValue().stateScope());
    assertEquals("ALLOW_NEW", mutationCaptor.getValue().characterCreationPolicy());
  }

  private static GameplayAdmissionPointerBootstrapProperties.PointerSeed pointerSeed(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    GameplayAdmissionPointerBootstrapProperties.PointerSeed pointerSeed =
        new GameplayAdmissionPointerBootstrapProperties.PointerSeed();
    pointerSeed.setWorldSlug(worldSlug);
    pointerSeed.setWorldDisplayName(worldDisplayName);
    pointerSeed.setRealmSlug(realmSlug);
    pointerSeed.setRealmDisplayName(realmDisplayName);
    pointerSeed.setTenantId(tenantId);
    pointerSeed.setGameInstanceId(gameInstanceId);
    pointerSeed.setVisible(true);
    pointerSeed.setPublicProductionRealm(true);
    pointerSeed.setRequiresCharacterSelection(requiresCharacterSelection);
    pointerSeed.setStateScope(GameplayAdmissionPointerBootstrapProperties.StateScope.SHARED);
    pointerSeed.setCharacterCreationPolicy(
        GameplayAdmissionPointerBootstrapProperties.CharacterCreationPolicy.ALLOW_NEW);
    return pointerSeed;
  }
}
