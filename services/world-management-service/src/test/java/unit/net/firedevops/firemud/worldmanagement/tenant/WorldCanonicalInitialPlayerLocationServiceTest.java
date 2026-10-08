package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationService;
import org.junit.jupiter.api.Test;

class WorldCanonicalInitialPlayerLocationServiceTest {
  @Test
  void defaultServiceDeniesPlacementBeforeRepositoryAccess() {
    var repository = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    var service = new WorldCanonicalInitialPlayerLocationService(repository);

    assertThatThrownBy(() -> service.place(request()))
        .isInstanceOf(WorldCanonicalInitialPlayerLocationService.PlacementDeniedException.class)
        .hasMessageContaining("authenticated current Entity assignment");

    verify(repository, never())
        .place(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void currentHeldAuthorityIsRequiredAndReleasedOnEveryRetry() {
    var repository = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    var request = request();
    var result = WorldCanonicalInitialPlayerLocation.Result.conflict(request, "TEST_CONFLICT");
    when(repository.place(
            org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.any()))
        .thenReturn(result);
    AtomicInteger checks = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    var service =
        new WorldCanonicalInitialPlayerLocationService(
            repository,
            ignored ->
                new WorldCanonicalInitialPlayerLocationService.HeldPlacementAuthority() {
                  private boolean held = true;

                  @Override
                  public void requireHeld() {
                    if (!held) throw new IllegalStateException("authority was released");
                    checks.incrementAndGet();
                  }

                  @Override
                  public void close() {
                    held = false;
                    closes.incrementAndGet();
                  }
                });

    service.place(request);
    service.place(request);

    verify(repository, org.mockito.Mockito.times(2))
        .place(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.any());
    org.assertj.core.api.Assertions.assertThat(checks).hasValue(4);
    org.assertj.core.api.Assertions.assertThat(closes).hasValue(2);
  }

  @Test
  void historicalOutcomeReadDoesNotInvokeCurrentPlacementAuthority() {
    var repository = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    var request = request();
    when(repository.readTerminalOutcome(request)).thenReturn(Optional.empty());
    AtomicInteger verifierCalls = new AtomicInteger();
    var service =
        new WorldCanonicalInitialPlayerLocationService(
            repository,
            ignored -> {
              verifierCalls.incrementAndGet();
              throw new AssertionError(
                  "historical outcome read must not acquire placement authority");
            });

    org.assertj.core.api.Assertions.assertThat(service.readTerminalOutcome(request)).isEmpty();

    verify(repository).readTerminalOutcome(request);
    verify(repository, never())
        .place(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.assertj.core.api.Assertions.assertThat(verifierCalls).hasValue(0);
  }

  private static WorldCanonicalInitialPlayerLocation.Request request() {
    UUID tenant = uuid("10000000-0000-4000-8000-000000000001");
    UUID instance = uuid("20000000-0000-4000-8000-000000000001");
    UUID namespace = uuid("30000000-0000-4000-8000-000000000001");
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.canonicalTenantId()).thenReturn(tenant);
    when(lifecycleRequest.worldSlug()).thenReturn("starter-world");
    when(lifecycleRequest.canonicalGameInstanceId()).thenReturn(instance);
    when(lifecycleRequest.playableStateNamespaceId()).thenReturn(namespace);
    when(lifecycleRequest.playableStateScope()).thenReturn("SHARED");
    when(lifecycleRequest.canonicalVersionId())
        .thenReturn(uuid("70000000-0000-4000-8000-000000000001"));
    var lifecycle = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(lifecycle.lifecycleStatus()).thenReturn("ACTIVE");
    when(lifecycle.request()).thenReturn(lifecycleRequest);
    var room =
        new net.firedevops.firemud.common.world.RoomTemplateRef(
            tenant,
            uuid("70000000-0000-4000-8000-000000000001"),
            uuid("80000000-0000-4000-8000-000000000001"));
    when(lifecycle.startLocation()).thenReturn(room);
    when(lifecycle.runtimeRoomInstanceId()).thenReturn(23L);
    when(lifecycle.canonicalBytes())
        .thenReturn(
            ("{\"schema\":\"world-canonical-instance-lifecycle-evidence/v1\","
                    + "\"lifecycleStatus\":\"ACTIVE\",\"lifecycleEpoch\":\"1\",\"request\":{"
                    + "\"readRequestId\":\"b0000000-0000-4000-8000-000000000001\","
                    + "\"canonicalTenantId\":\""
                    + tenant
                    + "\",\"canonicalVersionId\":\"70000000-0000-4000-8000-000000000001\","
                    + "\"worldSlug\":\"starter-world\",\"canonicalGameInstanceId\":\""
                    + instance
                    + "\","
                    + "\"playableStateNamespaceId\":\""
                    + namespace
                    + "\",\"playableStateScope\":\"SHARED\"},"
                    + "\"startLocation\":{\"tenantId\":\""
                    + tenant
                    + "\","
                    + "\"versionId\":\"70000000-0000-4000-8000-000000000001\","
                    + "\"roomTemplateId\":\"80000000-0000-4000-8000-000000000001\"},"
                    + "\"runtimeRoomInstanceId\":\"23\"}")
                .getBytes(StandardCharsets.UTF_8));
    return new WorldCanonicalInitialPlayerLocation.Request(
        uuid("40000000-0000-4000-8000-000000000001"),
        tenant,
        uuid("50000000-0000-4000-8000-000000000001"),
        "starter-world",
        instance,
        namespace,
        "SHARED",
        uuid("60000000-0000-4000-8000-000000000001"),
        uuid("70000000-0000-4000-8000-000000000001"),
        uuid("80000000-0000-4000-8000-000000000001"),
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        uuid("90000000-0000-4000-8000-000000000001"),
        uuid("a0000000-0000-4000-8000-000000000001"),
        "initial-request",
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        1,
        "owner-proof",
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        "pointer-audit",
        1,
        WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        lifecycle);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
