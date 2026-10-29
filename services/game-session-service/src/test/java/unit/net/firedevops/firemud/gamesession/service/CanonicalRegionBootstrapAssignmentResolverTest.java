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
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
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
  private CanonicalInitialPlayerLocationRequest initialLocationRequest;

  @BeforeEach
  void setUp() throws Exception {
    lifecycleClient = mock(WorldCanonicalInstanceLifecycleClient.class);
    resolver = new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient);
    activeEvidence = AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L);
    request = activeEvidence.request();
    initialLocationRequest = initialLocationRequest(request);
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

  @Test
  void initialLocationRegionIsRevalidatedAgainstTheAuthenticatedLifecycleOwner() {
    var initialClient = mock(CanonicalInitialPlayerLocationClient.class);
    when(lifecycleClient.read(request)).thenReturn(activeEvidence);
    var response = initialLocationRegionResponse(initialLocationRequest, activeEvidence);
    when(initialClient.readInitialPlayerLocationRegion(initialLocationRequest))
        .thenReturn(response);
    var resolver =
        new CanonicalInitialPlayerLocationRegionResolver(
            initialClient, new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));

    var resolved = resolver.resolve(initialLocationRequest);

    assertThat(resolved.canonicalRegionInstanceId()).isEqualTo(CANONICAL_REGION_ID);
    assertThat(resolved.operationalRegionId()).isEqualTo(OPERATIONAL_REGION_ID);
    assertThat(resolved.lifecycleAssignment().lifecycleEpoch())
        .isEqualTo(activeEvidence.lifecycleEpoch());
    verify(lifecycleClient).read(request);
  }

  @Test
  void initialLocationRegionRejectsLifecycleReadbackRace() {
    var initialClient = mock(CanonicalInitialPlayerLocationClient.class);
    when(lifecycleClient.read(request)).thenReturn(activeEvidence);
    var response = initialLocationRegionResponse(initialLocationRequest, activeEvidence);
    when(initialClient.readInitialPlayerLocationRegion(initialLocationRequest))
        .thenReturn(
            response.toBuilder()
                .setLifecycle(response.getLifecycle().toBuilder().setRowVersion(99L))
                .build());
    var resolver =
        new CanonicalInitialPlayerLocationRegionResolver(
            initialClient, new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));

    assertThatThrownBy(() -> resolver.resolve(initialLocationRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed during lifecycle revalidation");
  }

  @Test
  void initialLocationRegionRejectsEpochCaptureGraphAndPreparationRaces() {
    var initialClient = mock(CanonicalInitialPlayerLocationClient.class);
    var response = initialLocationRegionResponse(initialLocationRequest, activeEvidence);
    when(initialClient.readInitialPlayerLocationRegion(initialLocationRequest))
        .thenReturn(response);
    var resolver =
        new CanonicalInitialPlayerLocationRegionResolver(
            initialClient, new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));
    var changedEvidence =
        new WorldCanonicalInstanceLifecycleEvidence[] {
          changedEvidence(
              activeEvidence,
              activeEvidence.lifecycleEpoch() + 1,
              activeEvidence.rowVersion(),
              activeEvidence.captureId(),
              activeEvidence.graphSha256(),
              activeEvidence.preparationInputDigest()),
          changedEvidence(
              activeEvidence,
              activeEvidence.lifecycleEpoch(),
              activeEvidence.rowVersion(),
              uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
              activeEvidence.graphSha256(),
              activeEvidence.preparationInputDigest()),
          changedEvidence(
              activeEvidence,
              activeEvidence.lifecycleEpoch(),
              activeEvidence.rowVersion(),
              activeEvidence.captureId(),
              "9".repeat(64),
              activeEvidence.preparationInputDigest()),
          changedEvidence(
              activeEvidence,
              activeEvidence.lifecycleEpoch(),
              activeEvidence.rowVersion(),
              activeEvidence.captureId(),
              activeEvidence.graphSha256(),
              "sha256:" + "9".repeat(64))
        };

    for (var raced : changedEvidence) {
      when(lifecycleClient.read(request)).thenReturn(raced);
      assertThatThrownBy(() -> resolver.resolve(initialLocationRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("changed during lifecycle revalidation");
    }
  }

  @Test
  void initialLocationRegionRejectsOperationalAssignmentSubstitution() {
    var initialClient = mock(CanonicalInitialPlayerLocationClient.class);
    when(lifecycleClient.read(request)).thenReturn(activeEvidence);
    var response = initialLocationRegionResponse(initialLocationRequest, activeEvidence);
    when(initialClient.readInitialPlayerLocationRegion(initialLocationRequest))
        .thenReturn(
            response.toBuilder().setOperationalRegionUuid(UNKNOWN_REGION_ID.toString()).build());
    var resolver =
        new CanonicalInitialPlayerLocationRegionResolver(
            initialClient, new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));

    assertThatThrownBy(() -> resolver.resolve(initialLocationRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed during lifecycle revalidation");
  }

  @Test
  void initialLocationRegionRejectsCanonicalRegionSubstitution() {
    var initialClient = mock(CanonicalInitialPlayerLocationClient.class);
    var lifecycleResolver = mock(CanonicalRegionBootstrapAssignmentResolver.class);
    var current = mock(CanonicalRegionBootstrapAssignmentResolver.Assignment.class);
    when(current.request()).thenReturn(request);
    when(current.canonicalRegionId()).thenReturn(CANONICAL_REGION_ID);
    when(current.operationalRegionId()).thenReturn(OPERATIONAL_REGION_ID);
    when(current.lifecycleStatus()).thenReturn(activeEvidence.lifecycleStatus());
    when(current.lifecycleEpoch()).thenReturn(activeEvidence.lifecycleEpoch());
    when(current.lifecycleRowVersion()).thenReturn(activeEvidence.rowVersion());
    when(current.captureId()).thenReturn(activeEvidence.captureId());
    when(current.graphSha256()).thenReturn(activeEvidence.graphSha256());
    when(current.preparationInputDigest()).thenReturn(activeEvidence.preparationInputDigest());
    when(lifecycleResolver.resolve(request, UNKNOWN_REGION_ID)).thenReturn(current);
    var response = initialLocationRegionResponse(initialLocationRequest, activeEvidence);
    var substitutedResponse =
        response.toBuilder().setCanonicalRegionInstanceUuid(UNKNOWN_REGION_ID.toString()).build();
    when(initialClient.readInitialPlayerLocationRegion(initialLocationRequest))
        .thenReturn(substitutedResponse);
    var resolver =
        new CanonicalInitialPlayerLocationRegionResolver(initialClient, lifecycleResolver);

    assertThatThrownBy(() -> resolver.resolve(initialLocationRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed during lifecycle revalidation");
  }

  private static CanonicalInitialPlayerLocationRequest initialLocationRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    var target =
        PreseededActorAssignmentExpectedTarget.newBuilder()
            .setCanonicalTenantUuid(lifecycleRequest.canonicalTenantId().toString())
            .setRealmUuid("33333333-3333-4333-8333-333333333333")
            .setWorldSlug(lifecycleRequest.worldSlug())
            .setRealmSlug("test-realm")
            .setGameInstanceId(lifecycleRequest.canonicalGameInstanceId().toString())
            .setCatalogRevision(1L)
            .setCanonicalVersionUuid(lifecycleRequest.canonicalVersionId().toString())
            .setFrozenPolicyDigest("a".repeat(64))
            .setPlayableStateNamespaceUuid(lifecycleRequest.playableStateNamespaceId().toString())
            .setPublishedReleaseBundleRef("published/test/v1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build();
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid("44444444-4444-4444-8444-444444444444")
        .setExpectedTarget(target)
        .setCanonicalAccountUuid("55555555-5555-4555-8555-555555555555")
        .setCharacterUuid("66666666-6666-4666-8666-666666666666")
        .setAssignmentUuid("77777777-7777-4777-8777-777777777777")
        .setAssignmentIntentDigest("b".repeat(64))
        .setInitialAdmissionHoldUuid("88888888-8888-4888-8888-888888888888")
        .setInitialAdmissionHoldFence("99999999-9999-4999-8999-999999999999")
        .setInitialAdmissionRequestId("initial-attempt-1")
        .setInitialAdmissionRequestDigest("c".repeat(64))
        .setActiveLifecycleEpoch(7L)
        .setTerminalProofDigest("sha256:" + "d".repeat(64))
        .setCommittedPointerVersion(1L)
        .setAuditEventId(1L)
        .build();
  }

  private static CanonicalInitialPlayerLocationRegionResponse initialLocationRegionResponse(
      CanonicalInitialPlayerLocationRequest request,
      WorldCanonicalInstanceLifecycleEvidence evidence) {
    var placement =
        CanonicalInitialPlayerLocationResponse.newBuilder()
            .setSchemaVersion(1)
            .setPlacementOperationUuid(request.getPlacementOperationUuid())
            .setRequestDigest("sha256:" + "e".repeat(64))
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setCharacterUuid(request.getCharacterUuid())
            .setAssignmentUuid(request.getAssignmentUuid())
            .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
            .setResultDigest("sha256:" + "f".repeat(64))
            .setOutcome(
                CanonicalInitialPlayerLocationOutcome
                    .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRoom
                    .newBuilder()
                    .setCanonicalTenantUuid(request.getExpectedTarget().getCanonicalTenantUuid())
                    .setCanonicalVersionUuid(request.getExpectedTarget().getCanonicalVersionUuid())
                    .setRoomTemplateUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            .setPrivateRuntimeRoomKey("91")
            .build();
    var lifecycle =
        net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationLifecycleConsequence
            .newBuilder()
            .setCanonicalRequestBytes(
                com.google.protobuf.ByteString.copyFrom(evidence.request().canonicalBytes()))
            .setLifecycleStatus(evidence.lifecycleStatus())
            .setLifecycleEpoch(evidence.lifecycleEpoch())
            .setRowVersion(evidence.rowVersion())
            .setCaptureUuid(evidence.captureId().toString())
            .setGraphSha256(evidence.graphSha256())
            .setPreparationInputDigest(evidence.preparationInputDigest())
            .build();
    return CanonicalInitialPlayerLocationRegionResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementResult(placement)
        .setExpectedTarget(request.getExpectedTarget())
        .setCanonicalRegionInstanceUuid(CANONICAL_REGION_ID.toString())
        .setOperationalRegionUuid(OPERATIONAL_REGION_ID.toString())
        .setPrivateWorldInstanceKey(7L)
        .setObservedRuntimeRoomKey(91L)
        .setLifecycle(lifecycle)
        .build();
  }

  private static WorldCanonicalInstanceLifecycleEvidence changedEvidence(
      WorldCanonicalInstanceLifecycleEvidence original,
      long epoch,
      long rowVersion,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest) {
    var changed = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(changed.request()).thenReturn(original.request());
    when(changed.lifecycleStatus()).thenReturn("ACTIVE");
    when(changed.lifecycleEpoch()).thenReturn(epoch);
    when(changed.rowVersion()).thenReturn(rowVersion);
    when(changed.captureId()).thenReturn(captureId);
    when(changed.graphSha256()).thenReturn(graphSha256);
    when(changed.preparationInputDigest()).thenReturn(preparationInputDigest);
    when(changed.operationalRegionAssignments())
        .thenReturn(original.operationalRegionAssignments());
    return changed;
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
