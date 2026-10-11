package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanonicalRegionBootstrapAssignmentResolverTest {
  private static final UUID CANONICAL_REGION_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATIONAL_REGION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID UNKNOWN_REGION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private WorldCanonicalInstanceLifecycleClient lifecycleClient;
  private CanonicalRegionBootstrapAssignmentResolver resolver;
  private WorldCanonicalInstanceLifecycleEvidence.Request request;
  private WorldCanonicalInstanceLifecycleEvidence activeEvidence;

  @BeforeEach
  void setUp() throws Exception {
    lifecycleClient = mock(WorldCanonicalInstanceLifecycleClient.class);
    resolver = new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient);
    activeEvidence = AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L);
    request = activeEvidence.request();
  }

  @Test
  void resolvesExactOperationalUuidFromAuthenticatedOwnerRead() {
    when(lifecycleClient.read(request)).thenReturn(activeEvidence);

    var assignment = resolver.resolve(request, CANONICAL_REGION_ID);

    assertThat(assignment.request()).isEqualTo(request);
    assertThat(assignment.canonicalRegionId()).isEqualTo(CANONICAL_REGION_ID);
    assertThat(assignment.operationalRegionId()).isEqualTo(OPERATIONAL_REGION_ID);
    assertThat(assignment.lifecycleStatus()).isEqualTo("ACTIVE");
    assertThat(assignment.lifecycleEpoch()).isEqualTo(activeEvidence.lifecycleEpoch());
    assertThat(assignment.lifecycleRowVersion()).isEqualTo(activeEvidence.rowVersion());
    assertThat(assignment.captureId()).isEqualTo(activeEvidence.captureId());
    assertThat(assignment.graphSha256()).isEqualTo(activeEvidence.graphSha256());
    assertThat(assignment.preparationInputDigest())
        .isEqualTo(activeEvidence.preparationInputDigest());
    verify(lifecycleClient).read(request);
  }

  @Test
  void rejectsNilCanonicalRegionBeforeOwnerRead() {
    assertThatThrownBy(() -> resolver.resolve(request, NIL_UUID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");

    verifyNoInteractions(lifecycleClient);
  }

  @Test
  void rejectsResponseBoundToDifferentTargetNamespace() {
    var mismatchedRequest = copyRequestWithDifferentTargetNamespace(request);
    var mismatchedEvidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(mismatchedEvidence.request()).thenReturn(mismatchedRequest);
    when(mismatchedEvidence.lifecycleStatus()).thenReturn("ACTIVE");
    when(lifecycleClient.read(request)).thenReturn(mismatchedEvidence);

    assertThatThrownBy(() -> resolver.resolve(request, CANONICAL_REGION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact requested target");
  }

  @Test
  void rejectsNonActiveWorldLifecycle() throws Exception {
    var terminatedEvidence = AuthoringFixtures.lifecycleEvidence("TERMINATED", 8L);
    when(lifecycleClient.read(request)).thenReturn(terminatedEvidence);

    assertThatThrownBy(() -> resolver.resolve(request, CANONICAL_REGION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ACTIVE");
  }

  @Test
  void rejectsMissingCanonicalRegionWithoutInventingOperationalFallback() {
    when(lifecycleClient.read(request)).thenReturn(activeEvidence);

    assertThatThrownBy(() -> resolver.resolve(request, UNKNOWN_REGION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no operational assignment");
  }

  @Test
  void propagatesUnavailableOwnerReadFailure() {
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(lifecycleClient.read(request)).thenThrow(unavailable);
    assertThatThrownBy(() -> resolver.resolve(request, CANONICAL_REGION_ID)).isSameAs(unavailable);
  }

  @Test
  void propagatesInvalidOwnerEvidenceFailure() {
    IllegalStateException invalidEvidence = new IllegalStateException("invalid owner evidence");
    when(lifecycleClient.read(request)).thenThrow(invalidEvidence);
    assertThatThrownBy(() -> resolver.resolve(request, CANONICAL_REGION_ID))
        .isSameAs(invalidEvidence);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request
      copyRequestWithDifferentTargetNamespace(
          WorldCanonicalInstanceLifecycleEvidence.Request source) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        source.schemaVersion(),
        source.readRequestId(),
        source.targetNamespace() + "-other",
        source.canonicalTenantId(),
        source.worldSlug(),
        source.canonicalGameInstanceId(),
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.publicProduction(),
        source.controlPlaneRequestId(),
        source.canonicalVersionId(),
        source.expectedDescriptorRequestDigest(),
        source.expectedDescriptorResultDigest(),
        source.expectedReleaseAttestationDigest());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
