package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalCurrentPlayerLocationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalCurrentPlayerLocationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationService;
import org.junit.jupiter.api.Test;

class WorldCanonicalCurrentPlayerLocationServiceTest {
  @Test
  void productionDefaultDeniesBeforeOwnerRead() {
    var repository = mock(WorldCanonicalCurrentPlayerLocationRepository.class);
    var service = new WorldCanonicalCurrentPlayerLocationService(repository);

    assertThatThrownBy(() -> service.read(request()))
        .isInstanceOf(WorldCanonicalInitialPlayerLocationService.PlacementDeniedException.class)
        .hasMessageContaining("no authenticated current Entity assignment");
    verifyNoInteractions(repository);
  }

  @Test
  void currentAuthorityIsHeldAcrossTheOwnerReadIncludingAnAbsentResult() {
    var repository = mock(WorldCanonicalCurrentPlayerLocationRepository.class);
    when(repository.read(any(), any())).thenReturn(Optional.empty());
    AtomicInteger heldChecks = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    var service =
        new WorldCanonicalCurrentPlayerLocationService(
            repository,
            ignored ->
                new WorldCanonicalInitialPlayerLocationService.HeldPlacementAuthority() {
                  @Override
                  public void requireHeld() {
                    heldChecks.incrementAndGet();
                  }

                  @Override
                  public void close() {
                    closes.incrementAndGet();
                  }
                });

    assertThat(service.read(request())).isEmpty();
    assertThat(heldChecks).hasValue(2);
    assertThat(closes).hasValue(1);
  }

  private static WorldCanonicalInitialPlayerLocation.Request request() {
    UUID tenantId = uuid("20000000-0000-4000-8000-000000000001");
    UUID versionId = uuid("30000000-0000-4000-8000-000000000001");
    UUID instanceId = uuid("40000000-0000-4000-8000-000000000001");
    UUID namespaceId = uuid("50000000-0000-4000-8000-000000000001");
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.canonicalTenantId()).thenReturn(tenantId);
    when(lifecycleRequest.worldSlug()).thenReturn("starter-world");
    when(lifecycleRequest.canonicalGameInstanceId()).thenReturn(instanceId);
    when(lifecycleRequest.playableStateNamespaceId()).thenReturn(namespaceId);
    when(lifecycleRequest.playableStateScope()).thenReturn("SHARED");
    var lifecycle = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(lifecycle.lifecycleStatus()).thenReturn("ACTIVE");
    when(lifecycle.request()).thenReturn(lifecycleRequest);
    when(lifecycle.startLocation())
        .thenReturn(
            new RoomTemplateRef(tenantId, versionId, uuid("60000000-0000-4000-8000-000000000001")));
    when(lifecycle.runtimeRoomInstanceId()).thenReturn(23L);

    return new WorldCanonicalInitialPlayerLocation.Request(
        uuid("10000000-0000-4000-8000-000000000001"),
        tenantId,
        uuid("70000000-0000-4000-8000-000000000001"),
        "starter-world",
        instanceId,
        namespaceId,
        "SHARED",
        uuid("80000000-0000-4000-8000-000000000001"),
        uuid("90000000-0000-4000-8000-000000000001"),
        uuid("a0000000-0000-4000-8000-000000000001"),
        "a".repeat(64),
        uuid("b0000000-0000-4000-8000-000000000001"),
        uuid("c0000000-0000-4000-8000-000000000001"),
        "initial-request",
        "d".repeat(64),
        1,
        "owner-proof",
        "e".repeat(64),
        "pointer-audit",
        1,
        WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        lifecycle);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
