package net.firedevops.firemud.gamesession.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.config.GameplayAdmissionPointerBootstrapProperties;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
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
  void shippedYamlBindsPublicDemoAndPrivateSandboxToTheSameTenantWithoutPersistingSeeds()
      throws Exception {
    ShippedBootstrapConfiguration shipped = loadShippedConfiguration(Map.of());
    List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers =
        shipped.gameSession().getPointers();

    assertEquals(1L, pointers.get(0).getTenantId());
    assertEquals(1L, pointers.get(0).getGameInstanceId());
    assertTrue(pointers.get(0).isPublicProductionRealm());
    assertEquals(1L, pointers.get(1).getTenantId());
    assertEquals(2L, pointers.get(1).getGameInstanceId());
    assertFalse(pointers.get(1).isPublicProductionRealm());
    assertTrue(pointers.get(1).isRequiresCharacterSelection());
    assertEquals(
        List.of(new SmokeRuntimeTarget(1L, 1L), new SmokeRuntimeTarget(1L, 2L)),
        shipped.worldManagementTargets());

    when(pointerRepository.count()).thenReturn(0L);
    initializer =
        new GameplayAdmissionPointerBootstrapInitializer(pointerRepository, shipped.gameSession());
    initializer.run(new DefaultApplicationArguments(new String[] {}));

    InOrder bootstrapOrder = inOrder(pointerRepository);
    bootstrapOrder.verify(pointerRepository).lockForBootstrap();
    bootstrapOrder.verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void shippedYamlTenantOverridesKeepGameSessionAndWorldTargetsAligned() throws Exception {
    ShippedBootstrapConfiguration shipped =
        loadShippedConfiguration(
            Map.of(
                "FIREMUD_BOOTSTRAP_DEMO_TENANT_ID", "7",
                "FIREMUD_BOOTSTRAP_SANDBOX_TENANT_ID", "7",
                "FIREMUD_BOOTSTRAP_DEMO_GAME_INSTANCE_ID", "101",
                "FIREMUD_BOOTSTRAP_SANDBOX_GAME_INSTANCE_ID", "202"));

    assertEquals(7L, shipped.gameSession().getPointers().get(0).getTenantId());
    assertEquals(101L, shipped.gameSession().getPointers().get(0).getGameInstanceId());
    assertEquals(7L, shipped.gameSession().getPointers().get(1).getTenantId());
    assertEquals(202L, shipped.gameSession().getPointers().get(1).getGameInstanceId());
    assertFalse(shipped.gameSession().getPointers().get(1).isPublicProductionRealm());
    assertEquals(
        List.of(new SmokeRuntimeTarget(7L, 101L), new SmokeRuntimeTarget(7L, 202L)),
        shipped.worldManagementTargets());

    when(pointerRepository.count()).thenReturn(0L);
    initializer =
        new GameplayAdmissionPointerBootstrapInitializer(pointerRepository, shipped.gameSession());
    initializer.run(new DefaultApplicationArguments(new String[] {}));
    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void shippedYamlPrivateSandboxOverrideToAnotherTenantFailsBeforeMutation() throws Exception {
    ShippedBootstrapConfiguration shipped =
        loadShippedConfiguration(Map.of("FIREMUD_BOOTSTRAP_SANDBOX_TENANT_ID", "2"));
    when(pointerRepository.count()).thenReturn(0L);
    initializer =
        new GameplayAdmissionPointerBootstrapInitializer(pointerRepository, shipped.gameSession());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> initializer.run(new DefaultApplicationArguments(new String[] {})));
    assertTrue(failure.getMessage().contains("exactly one visible public production realm for tenant 2"));

    verify(pointerRepository).lockForBootstrap();
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
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

  private static ShippedBootstrapConfiguration loadShippedConfiguration(
      Map<String, Object> environmentOverrides) throws IOException {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(
        new MapPropertySource("bootstrap-test-overrides", environmentOverrides));
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    // Read source YAML directly to avoid test-resource shadowing; this is not packaged or live-boot proof.
    environment.getPropertySources().addLast(
        loader
            .load(
                "game-session-application",
                new FileSystemResource(
                    repositoryFile(
                        "services/game-session-service/src/main/resources/application.yml")))
            .get(0));
    environment.getPropertySources().addLast(
        loader
            .load(
                "world-management-application",
                new FileSystemResource(
                    repositoryFile(
                        "services/world-management-service/src/main/resources/application.yml")))
            .get(0));

    Binder binder = Binder.get(environment);
    GameplayAdmissionPointerBootstrapProperties gameSession =
        binder
            .bind(
                "firemud.gameplay.pointer-bootstrap",
                Bindable.of(GameplayAdmissionPointerBootstrapProperties.class))
            .orElseThrow();
    List<SmokeRuntimeTarget> worldManagementTargets =
        binder
            .bind(
                "firemud.smoke.seed-demo-runtime.targets",
                Bindable.listOf(SmokeRuntimeTarget.class))
            .orElseThrow();
    return new ShippedBootstrapConfiguration(gameSession, worldManagementTargets);
  }

  private static Path repositoryFile(String relativePath) {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve(relativePath);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Cannot locate repository file " + relativePath);
  }

  private record ShippedBootstrapConfiguration(
      GameplayAdmissionPointerBootstrapProperties gameSession,
      List<SmokeRuntimeTarget> worldManagementTargets) {}

  private record SmokeRuntimeTarget(long tenantId, long gameInstanceId) {}
}
