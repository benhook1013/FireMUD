package net.firedevops.firemud.gamesession.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.config.GameplayAdmissionPointerBootstrapProperties;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
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
  void runKeepsAdmissionClosedWithoutPointerWritesOrBootstrapLockWhenStoreIsEmpty()
      throws Exception {
    when(pointerRepository.count()).thenReturn(0L);

    initializer.run(new DefaultApplicationArguments(new String[] {}));

    verify(pointerRepository).count();
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
    assertSmokeRuntimeTargets(shipped.worldManagementTargets(), 1L, 1L, 1L, 2L);

    when(pointerRepository.count()).thenReturn(0L);
    initializer =
        new GameplayAdmissionPointerBootstrapInitializer(pointerRepository, shipped.gameSession());
    initializer.run(new DefaultApplicationArguments(new String[] {}));

    verify(pointerRepository).count();
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
    assertSmokeRuntimeTargets(shipped.worldManagementTargets(), 7L, 101L, 7L, 202L);

    when(pointerRepository.count()).thenReturn(0L);
    initializer =
        new GameplayAdmissionPointerBootstrapInitializer(pointerRepository, shipped.gameSession());
    initializer.run(new DefaultApplicationArguments(new String[] {}));
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
    assertTrue(
        failure.getMessage().contains("exactly one visible public production realm for tenant 2"));

    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }

  @Test
  void runPreservesExistingPointerAuthorityOnRestart() throws Exception {
    when(pointerRepository.count()).thenReturn(3L);

    initializer.run(new DefaultApplicationArguments(new String[] {}));

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
  void runKeepsAdmissionClosedForValidSeedsAcrossTenantsWithoutPointerWritesOrBootstrapLock()
      throws Exception {
    when(pointerRepository.count()).thenReturn(0L);
    properties.setPointers(
        List.of(
            pointerSeed("demo", "Demo World", "production", "Live Realm", 1L, 1L, true, false),
            pointerSeed("demo", "Other Demo", "production", "Live Realm", 2L, 1L, true, true)));

    initializer.run(new DefaultApplicationArguments(new String[] {}));
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

  private static ShippedBootstrapConfiguration loadShippedConfiguration(
      Map<String, Object> environmentOverrides) throws IOException {
    MockEnvironment environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("bootstrap-test-overrides", environmentOverrides));
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    // Read source YAML directly to avoid test-resource shadowing; this is not packaged or live-boot
    // proof.
    environment
        .getPropertySources()
        .addLast(
            loader
                .load(
                    "game-session-application",
                    new FileSystemResource(
                        repositoryFile(
                            "services/game-session-service/src/main/resources/application.yml")))
                .get(0));
    environment
        .getPropertySources()
        .addLast(
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
            .orElseThrow(() -> new IllegalStateException("Missing Game Session bootstrap YAML"));
    List<SmokeRuntimeTarget> worldManagementTargets =
        binder
            .bind(
                "firemud.smoke.seed-demo-runtime.targets",
                Bindable.listOf(SmokeRuntimeTarget.class))
            .orElseThrow(() -> new IllegalStateException("Missing World runtime-target YAML"));
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

  private static void assertSmokeRuntimeTargets(
      List<SmokeRuntimeTarget> actualTargets, long... expectedTenantAndInstanceIds) {
    assertEquals(expectedTenantAndInstanceIds.length / 2, actualTargets.size());
    for (int index = 0; index < actualTargets.size(); index++) {
      assertEquals(expectedTenantAndInstanceIds[index * 2], actualTargets.get(index).getTenantId());
      assertEquals(
          expectedTenantAndInstanceIds[index * 2 + 1],
          actualTargets.get(index).getGameInstanceId());
    }
  }

  private record ShippedBootstrapConfiguration(
      GameplayAdmissionPointerBootstrapProperties gameSession,
      List<SmokeRuntimeTarget> worldManagementTargets) {}

  public static class SmokeRuntimeTarget {
    private long tenantId;
    private long gameInstanceId;

    public long getTenantId() {
      return tenantId;
    }

    public void setTenantId(long tenantId) {
      this.tenantId = tenantId;
    }

    public long getGameInstanceId() {
      return gameInstanceId;
    }

    public void setGameInstanceId(long gameInstanceId) {
      this.gameInstanceId = gameInstanceId;
    }
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
    verify(pointerRepository).count();
    verifyNoMoreInteractions(pointerRepository);
  }
}
