package net.firedevops.firemud.gamesession.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamesession.config.GameplayAdmissionPointerBootstrapProperties;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.stereotype.Component;

@ExtendWith(MockitoExtension.class)
class GameplayAdmissionPointerBootstrapInitializerTest {
  @Mock private GameplayAdmissionPointerRepository pointerRepository;

  private GameplayAdmissionPointerBootstrapProperties properties;
  private GameplayAdmissionPointerBootstrapInitializer initializer;

  @BeforeEach
  void setUp() {
    properties = new GameplayAdmissionPointerBootstrapProperties();
    initializer = new GameplayAdmissionPointerBootstrapInitializer(pointerRepository, properties);
  }

  @Test
  void runKeepsAdmissionClosedForValidDefaultSeedsWhenAuthorityStoreIsEmpty() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    InOrder bootstrapOrder = inOrder(pointerRepository);
    bootstrapOrder.verify(pointerRepository).lockForBootstrap();
    bootstrapOrder.verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void defaultEffectiveBootstrapSeedsHaveOneVisiblePublicProductionRealmPerTenant() {
    List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers =
        properties.getPointers();

    assertEquals(2, pointers.size());
    assertEquals("demo", pointers.get(0).getWorldSlug());
    assertTrue(pointers.get(0).isVisible());
    assertTrue(pointers.get(0).isPublicProductionRealm());
    assertEquals("sandbox", pointers.get(1).getWorldSlug());
    assertTrue(pointers.get(1).isVisible());
    assertFalse(pointers.get(1).isPublicProductionRealm());
  }

  @Test
  void runPreservesExistingPointerAuthorityOnRestart() throws Exception {
    when(pointerRepository.count()).thenReturn(3L);

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void runRejectsMalformedBootstrapPointerSeedsBeforeAnyMutation() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        new java.util.ArrayList<>(
            java.util.Arrays.asList(
                pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false),
                pointerSeed("", "Broken World", "production", "Live Realm", 1L, 2L, false),
                pointerSeed("sandbox", "Builder Sandbox", "", "Live Realm", 1L, 3L, true),
                null)));

    assertThrows(
        IllegalArgumentException.class,
        () -> initializer.run(new DefaultApplicationArguments(new String[] {})));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void runRejectsMultipleVisiblePublicProductionSeedsForTenantBeforeAnyMutation() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    GameplayAdmissionPointerBootstrapProperties.PointerSeed sandbox =
        pointerSeed("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, true);
    properties.setPointers(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false), sandbox));

    assertThrows(
        IllegalArgumentException.class,
        () -> initializer.run(new DefaultApplicationArguments(new String[] {})));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void runRejectsNoVisiblePublicProductionSeedForTenantBeforeAnyMutation() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    GameplayAdmissionPointerBootstrapProperties.PointerSeed demo =
        pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false);
    demo.setPublicProductionRealm(false);
    properties.setPointers(List.of(demo));

    assertThrows(
        IllegalArgumentException.class,
        () -> initializer.run(new DefaultApplicationArguments(new String[] {})));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void runRejectsDuplicateWorldRealmSeedsBeforeAnyMutation() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false),
            pointerSeed(" DEMO ", "Duplicate World", "PRODUCTION", "Live Realm", 1L, 2L, true)));

    assertThrows(
        IllegalArgumentException.class,
        () -> initializer.run(new DefaultApplicationArguments(new String[] {})));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void runKeepsAdmissionClosedForValidSeedsAcrossTenants() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false),
            pointerSeed("demo", "Other Demo", "production", "Live Realm", 2L, 1L, false)));

    initializer.run(new DefaultApplicationArguments(new String[] {}));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void initializerIsRegisteredAfterAuditIdentityBecomesComplete() {
    assertTrue(
        GameplayAdmissionPointerBootstrapInitializer.class.isAnnotationPresent(Component.class));
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
