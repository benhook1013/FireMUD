package net.firedevops.firemud.gamesession.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
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
  void runRejectsMissingBootstrapPointerSeedsBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(), "Gameplay admission pointer bootstrap seeds are required");
  }

  @Test
  void runRejectsNullBootstrapPointerSeedBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        Arrays.asList(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            null),
        "Invalid gameplay admission pointer bootstrap seed at index 1: must not be null");
  }

  @Test
  void runRejectsBlankWorldSlugBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed(" ", "Broken World", "sandbox", "Sandbox Realm", 1L, 2L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: world slug is required");
  }

  @Test
  void runRejectsBlankWorldDisplayNameBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed("sandbox", " ", "sandbox", "Sandbox Realm", 1L, 2L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: world display name is "
            + "required");
  }

  @Test
  void runRejectsBlankRealmSlugBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed("sandbox", "Sandbox World", "", "Sandbox Realm", 1L, 2L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: realm slug is required");
  }

  @Test
  void runRejectsBlankRealmDisplayNameBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed("sandbox", "Sandbox World", "sandbox", " ", 1L, 2L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: realm display name is "
            + "required");
  }

  @Test
  void runRejectsNonPositiveTenantIdBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed(
                "sandbox", "Sandbox World", "sandbox", "Sandbox Realm", 0L, 2L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: tenant ID must be positive");
  }

  @Test
  void runRejectsNonPositiveGameInstanceIdBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed(
                "sandbox", "Sandbox World", "sandbox", "Sandbox Realm", 1L, 0L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: game instance ID must be "
            + "positive");
  }

  @Test
  void runRejectsMultipleVisiblePublicProductionSeedsForTenantBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed(
                "sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, true, true)),
        "Gameplay admission pointer bootstrap must define exactly one visible public production "
            + "realm for tenant 1");
  }

  @Test
  void runRejectsNoVisiblePublicProductionSeedForTenantBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, false, false)),
        "Gameplay admission pointer bootstrap must define exactly one visible public production "
            + "realm for tenant 1");
  }

  @Test
  void runRejectsDuplicateWorldRealmSeedsBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed(
                " DEMO ", "Duplicate World", "PRODUCTION", "Live Realm", 1L, 2L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: duplicates a tenant world "
            + "and realm selector");
  }

  @Test
  void runRejectsDuplicateRuntimeTargetSeedsBeforeAnyMutation() throws Exception {
    assertRejectedBeforeMutation(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed(
                "sandbox", "Sandbox World", "sandbox", "Sandbox Realm", 1L, 1L, false, true)),
        "Invalid gameplay admission pointer bootstrap seed at index 1: duplicates a tenant runtime "
            + "target");
  }

  @Test
  void runKeepsAdmissionClosedForValidSeedsAcrossTenants() throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed("demo", "Other Demo", "production", "Live Realm", 2L, 1L, true, true)));

    initializer.run(new DefaultApplicationArguments(new String[] {}));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void initializerIsRegisteredAsSpringComponent() {
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
      boolean publicProductionRealm,
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
    pointerSeed.setPublicProductionRealm(publicProductionRealm);
    pointerSeed.setRequiresCharacterSelection(requiresCharacterSelection);
    pointerSeed.setStateScope(GameplayAdmissionPointerBootstrapProperties.StateScope.SHARED);
    pointerSeed.setCharacterCreationPolicy(
        GameplayAdmissionPointerBootstrapProperties.CharacterCreationPolicy.ALLOW_NEW);
    return pointerSeed;
  }

  private void assertRejectedBeforeMutation(
      List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers,
      String expectedMessage)
      throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(pointers);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> initializer.run(new DefaultApplicationArguments(new String[] {})));

    assertEquals(expectedMessage, error.getMessage());
    InOrder bootstrapOrder = inOrder(pointerRepository);
    bootstrapOrder.verify(pointerRepository).lockForBootstrap();
    bootstrapOrder.verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }
}
