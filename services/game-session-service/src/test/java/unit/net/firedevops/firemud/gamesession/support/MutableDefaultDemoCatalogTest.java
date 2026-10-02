package unit.net.firedevops.firemud.gamesession.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.junit.jupiter.api.Test;

class MutableDefaultDemoCatalogTest {
  private static final long TENANT_ID = 41L;
  private static final long BASELINE_GAME_INSTANCE_ID = 73L;

  @Test
  void defaultDemoIsAbsentUntilTheTestBaselineIsEnabled() {
    MutablePointerAuthority authority = new MutablePointerAuthority();
    TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture = fixture(authority);
    GameplayWorldCatalog catalog = fixture.catalog();

    assertThat(catalog.resolveWorld("demo")).isEmpty();

    fixture.useDefaultDemo(TENANT_ID, BASELINE_GAME_INSTANCE_ID);

    GameplayWorldCatalog.RealmView realm =
        catalog.resolveDefaultRealm(catalog.resolveWorld("demo").orElseThrow()).orElseThrow();
    assertThat(realm.tenantId()).isEqualTo(TENANT_ID);
    assertThat(realm.gameInstanceId()).isEqualTo(BASELINE_GAME_INSTANCE_ID);
    assertThat(realm.pointerVersion()).isEqualTo(1L);
    assertThat(realm.catalogRevision()).isEqualTo(1L);
    assertThat(realm.realmId()).isNotNull();
    assertThat(realm.playableStateNamespaceId()).isNotNull();
    assertThat(catalog.hasValidPublicProductionRealm(TENANT_ID)).isTrue();
  }

  @Test
  void persistedMatchingPointerTakesPrecedenceOverTheBaselineFallback() {
    MutablePointerAuthority authority = new MutablePointerAuthority();
    TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture = fixture(authority);
    fixture.useDefaultDemo(TENANT_ID, BASELINE_GAME_INSTANCE_ID);
    authority.setPointers(List.of(pointer("demo", "production", TENANT_ID, 99L, 8L, 12L)));

    GameplayWorldCatalog.RealmView realm =
        fixture
            .catalog()
            .resolveDefaultRealm(fixture.catalog().resolveWorld("demo").orElseThrow())
            .orElseThrow();

    assertThat(realm.gameInstanceId()).isEqualTo(99L);
    assertThat(realm.pointerVersion()).isEqualTo(8L);
    assertThat(realm.catalogRevision()).isEqualTo(12L);
  }

  @Test
  void selectorForAnotherTenantAlsoOccupiesTheDefaultFallbackSelector() {
    MutablePointerAuthority authority = new MutablePointerAuthority();
    TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture = fixture(authority);
    fixture.useDefaultDemo(TENANT_ID, BASELINE_GAME_INSTANCE_ID);
    authority.setPointers(List.of(pointer("demo", "production", TENANT_ID + 1L, 99L, 8L, 12L)));

    GameplayWorldCatalog catalog = fixture.catalog();
    GameplayWorldCatalog.RealmView realm =
        catalog.resolveDefaultRealm(catalog.resolveWorld("demo").orElseThrow()).orElseThrow();

    assertThat(realm.tenantId()).isEqualTo(TENANT_ID + 1L);
    assertThat(realm.gameInstanceId()).isEqualTo(99L);
  }

  @Test
  void clearingTheFallbackRestoresMissingAuthority() {
    MutablePointerAuthority authority = new MutablePointerAuthority();
    TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture = fixture(authority);
    fixture.useDefaultDemo(TENANT_ID, BASELINE_GAME_INSTANCE_ID);
    assertThat(fixture.catalog().resolveWorld("demo")).isPresent();

    fixture.clearDefaultDemo();

    assertThat(fixture.catalog().resolveWorld("demo")).isEmpty();
  }

  @Test
  void incompletePersistedPointerIsNotReplacedByTheFallback() {
    MutablePointerAuthority authority = new MutablePointerAuthority();
    TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture = fixture(authority);
    fixture.useDefaultDemo(TENANT_ID, BASELINE_GAME_INSTANCE_ID);
    authority.setPointers(
        List.of(
            new GameplayAdmissionPointerSnapshot(
                "demo",
                "Demo World",
                "production",
                "Live Realm",
                TENANT_ID,
                99L,
                8L,
                true,
                true,
                false,
                "SHARED",
                "ALLOW_NEW",
                12L)));

    assertThatThrownBy(() -> fixture.catalog().resolveWorld("demo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("test catalog pointer is incomplete");
  }

  @Test
  void ambiguousPersistedPublicAuthorityIsNotHiddenByTheFallback() {
    MutablePointerAuthority authority = new MutablePointerAuthority();
    TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture = fixture(authority);
    fixture.useDefaultDemo(TENANT_ID, BASELINE_GAME_INSTANCE_ID);
    authority.setPointers(List.of(pointer("other", "production", TENANT_ID, 99L, 8L, 12L)));

    assertThatThrownBy(() -> fixture.catalog().resolveWorld("demo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("test catalog requires exactly one visible public production realm per tenant");
  }

  private static TestGameplayWorldCatalogs.MutableDefaultDemoCatalog fixture(
      MutablePointerAuthority authority) {
    return new TestGameplayWorldCatalogs.MutableDefaultDemoCatalog(authority);
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      long catalogRevision) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldSlug.equals("demo") ? "Demo World" : "Other World",
        realmSlug,
        "Live Realm",
        tenantId,
        gameInstanceId,
        pointerVersion,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        catalogRevision,
        UUID.nameUUIDFromBytes(
            (worldSlug + ":realm").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes(
            (worldSlug + ":namespace").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static final class MutablePointerAuthority
      implements GameplayAdmissionPointerAuthorityService {
    private List<GameplayAdmissionPointerSnapshot> pointers = List.of();

    private void setPointers(List<GameplayAdmissionPointerSnapshot> pointers) {
      this.pointers = new ArrayList<>(pointers);
    }

    @Override
    public List<GameplayAdmissionPointerSnapshot> listPointers() {
      return List.copyOf(pointers);
    }

    @Override
    public List<GameplayAdmissionPointerSnapshot> listPointersForTenants(List<Long> tenantIds) {
      return pointers.stream()
          .filter(pointer -> tenantIds.contains(pointer.tenantId()))
          .toList();
    }

    @Override
    public List<GameplayAdmissionPointerSnapshot> listPointersByTenant(long tenantId) {
      return pointers.stream().filter(pointer -> pointer.tenantId() == tenantId).toList();
    }

    @Override
    public Optional<GameplayAdmissionPointerSnapshot> findPointer(
        long tenantId, String worldSlug, String realmSlug) {
      return pointers.stream()
          .filter(pointer -> pointer.tenantId() == tenantId)
          .filter(pointer -> pointer.worldSlug().equals(worldSlug))
          .filter(pointer -> pointer.realmSlug().equals(realmSlug))
          .findFirst();
    }

    @Override
    public List<GameplayAdmissionPointerSnapshot> listByRuntimeTarget(
        long tenantId, long gameInstanceId) {
      return pointers.stream()
          .filter(pointer -> pointer.tenantId() == tenantId)
          .filter(pointer -> pointer.gameInstanceId() == gameInstanceId)
          .toList();
    }

    @Override
    public GameplayAdmissionPointerSnapshot upsertPointer(
        GameplayAdmissionPointerMutation mutation) {
      throw new UnsupportedOperationException("catalog tests do not mutate persisted pointers");
    }

    @Override
    public List<GameplayAdmissionPointerAuditEntry> listPointerAudit(
        long tenantId, String worldSlug, String realmSlug) {
      return List.of();
    }

    @Override
    public Optional<GameplayAdmissionPointerAuditEntry> findLatestPointerAudit(
        long tenantId, String worldSlug, String realmSlug) {
      return Optional.empty();
    }
  }
}
