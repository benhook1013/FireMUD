package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Outcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.RegionResult;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Result;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.junit.jupiter.api.Test;

class CanonicalInitialPlayerLocationGrpcCodecTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID GAME_INSTANCE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID NAMESPACE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ACCOUNT = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID CHARACTER = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID ASSIGNMENT = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID HOLD = uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID FENCE = uuid("ffffffff-ffff-4fff-8fff-ffffffffffff");

  @Test
  void decodesTheCompleteEntityTargetAndExactOwnerSelectors() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());

    assertThat(selection.placementOperationId()).isEqualTo(OPERATION);
    assertThat(selection.expectedTarget()).isEqualTo(target());
    assertThat(selection.canonicalTenantId()).isEqualTo(TENANT);
    assertThat(selection.realmId()).isEqualTo(REALM);
    assertThat(selection.worldSlug()).isEqualTo("green-hollow");
    assertThat(selection.realmSlug()).isEqualTo("sunvale");
    assertThat(selection.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE);
    assertThat(selection.canonicalVersionId()).isEqualTo(VERSION);
    assertThat(selection.playableStateNamespaceId()).isEqualTo(NAMESPACE);
    assertThat(selection.canonicalAccountId()).isEqualTo(ACCOUNT);
    assertThat(selection.characterId()).isEqualTo(CHARACTER);
    assertThat(selection.assignmentId()).isEqualTo(ASSIGNMENT);
    assertThat(selection.initialAdmissionHoldId()).isEqualTo(HOLD);
    assertThat(selection.initialAdmissionHoldFence()).isEqualTo(FENCE);
    assertThat(selection.activeLifecycleEpoch()).isEqualTo(7L);
    assertThat(selection.committedPointerVersion()).isEqualTo(1L);
    assertThat(selection.auditEventId()).isEqualTo(41L);
  }

  @Test
  void rejectsMissingUnknownAndMalformedSelectorsBeforeOwnerReads() {
    assertThatThrownBy(
            () ->
                CanonicalInitialPlayerLocationGrpcCodec.fromRequest(
                    CanonicalInitialPlayerLocationRequest.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);

    var unknownRequest =
        request().toBuilder()
            .mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(() -> CanonicalInitialPlayerLocationGrpcCodec.fromRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class);

    var unknownTarget =
        request().toBuilder()
            .setExpectedTarget(
                target().toBuilder()
                    .mergeUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(() -> CanonicalInitialPlayerLocationGrpcCodec.fromRequest(unknownTarget))
        .isInstanceOf(IllegalArgumentException.class);

    assertInvalid(request().toBuilder().setActiveLifecycleEpoch(0L).build());
    assertInvalid(request().toBuilder().setCommittedPointerVersion(2L).build());
    assertInvalid(request().toBuilder().setAssignmentIntentDigest("SHA256").build());
    assertInvalid(request().toBuilder().setPlacementOperationUuid("not-a-uuid").build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(target().toBuilder().setGameInstanceId("opaque-instance:preserved"))
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(
                target().toBuilder().setGameInstanceId("00000000-0000-0000-0000-000000000000"))
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(target().toBuilder().setPublishedReleaseBundleRef("r".repeat(1025)))
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(target().toBuilder().setPublishedReleaseBundleRef("r".repeat(1024)))
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(target().toBuilder().setPublishedReleaseBundleRef("r".repeat(129)))
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(
                target().toBuilder()
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
            .build());
  }

  @Test
  void acceptsReleaseReferencesAt128Characters() {
    var candidate =
        request().toBuilder()
            .setExpectedTarget(target().toBuilder().setPublishedReleaseBundleRef("r".repeat(128)))
            .build();

    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(candidate);

    assertThat(selection.publishedReleaseBundleRef()).hasSize(128);
  }

  @Test
  void rendersOnlyRetainedAppliedOrConflictOutcomes() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var applied =
        new Result(
            Outcome.APPLIED,
            null,
            new RoomTemplateRef(TENANT, VERSION, uuid("12121212-1212-4212-8212-121212121212")),
            17L,
            new byte[] {1},
            "sha256:" + "a".repeat(64),
            new byte[] {2},
            "sha256:" + "b".repeat(64),
            true);

    var appliedResponse = CanonicalInitialPlayerLocationGrpcCodec.toResponse(selection, applied);
    assertThat(appliedResponse.getOutcome())
        .isEqualTo(
            CanonicalInitialPlayerLocationOutcome
                .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED);
    assertThat(appliedResponse.hasStartLocation()).isTrue();
    assertThat(appliedResponse.getPrivateRuntimeRoomKey()).isEqualTo("17");
    assertThat(appliedResponse.getCanonicalAccountUuid()).isEqualTo(ACCOUNT.toString());
    assertThat(appliedResponse.getAssignmentIntentDigest()).isEqualTo("a".repeat(64));

    var conflict =
        new Result(
            Outcome.CONFLICT,
            "CHARACTER_ALREADY_PLACED",
            null,
            null,
            new byte[] {1},
            "sha256:" + "a".repeat(64),
            new byte[] {3},
            "sha256:" + "c".repeat(64),
            true);
    var conflictResponse = CanonicalInitialPlayerLocationGrpcCodec.toResponse(selection, conflict);
    assertThat(conflictResponse.getOutcome())
        .isEqualTo(
            CanonicalInitialPlayerLocationOutcome
                .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_CONFLICT);
    assertThat(conflictResponse.getConflictCode()).isEqualTo("CHARACTER_ALREADY_PLACED");
    assertThat(conflictResponse.hasStartLocation()).isFalse();
    assertThat(conflictResponse.getPrivateRuntimeRoomKey()).isEmpty();

    assertThatThrownBy(
            () ->
                CanonicalInitialPlayerLocationGrpcCodec.toResponse(
                    selection,
                    new Result(
                        Outcome.UNREGISTERED, null, null, null, null, null, null, null, false)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void emitsFreshRegionConsequenceOutsideTheImmutablePlacementResult() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var start = new RoomTemplateRef(TENANT, VERSION, uuid("12121212-1212-4212-8212-121212121212"));
    var placement =
        new Result(
            Outcome.APPLIED,
            null,
            start,
            917L,
            new byte[] {1},
            "sha256:" + "a".repeat(64),
            new byte[] {2},
            "sha256:" + "b".repeat(64),
            true);
    UUID canonicalRegion = uuid("13131313-1313-4313-8313-131313131313");
    UUID operationalRegion = uuid("14141414-1414-4414-8414-141414141414");
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            WorldCanonicalInstanceLifecycleEvidence.initialLocationReadRequestId(
                selection.placementOperationId()),
            "world-management",
            TENANT,
            selection.worldSlug(),
            GAME_INSTANCE,
            NAMESPACE,
            "SHARED",
            true,
            "control-plane-17",
            VERSION,
            "sha256:" + "c".repeat(64),
            "sha256:" + "d".repeat(64),
            "sha256:" + "e".repeat(64));
    var lifecycle = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(lifecycle.request()).thenReturn(lifecycleRequest);
    when(lifecycle.startLocation()).thenReturn(start);
    when(lifecycle.runtimeRoomInstanceId()).thenReturn(917L);
    when(lifecycle.lifecycleStatus()).thenReturn("ACTIVE");
    when(lifecycle.lifecycleEpoch()).thenReturn(selection.activeLifecycleEpoch());
    when(lifecycle.rowVersion()).thenReturn(9L);
    when(lifecycle.captureId()).thenReturn(uuid("16161616-1616-4616-8616-161616161616"));
    when(lifecycle.graphSha256()).thenReturn("f".repeat(64));
    when(lifecycle.preparationInputDigest()).thenReturn("sha256:" + "1".repeat(64));
    when(lifecycle.operationalRegionAssignments())
        .thenReturn(Map.of(canonicalRegion, operationalRegion));

    var response =
        CanonicalInitialPlayerLocationGrpcCodec.toRegionResponse(
            selection,
            new RegionResult(placement, 4L, 917L, canonicalRegion, operationalRegion, lifecycle));

    assertThat(response.getPlacementResult().getRequestDigest())
        .isEqualTo(placement.requestDigest());
    assertThat(response.getPlacementResult().getResultDigest()).isEqualTo(placement.resultDigest());
    assertThat(response.getExpectedTarget()).isEqualTo(selection.expectedTarget());
    assertThat(response.getCanonicalRegionInstanceUuid()).isEqualTo(canonicalRegion.toString());
    assertThat(response.getOperationalRegionUuid()).isEqualTo(operationalRegion.toString());
    assertThat(response.getObservedRuntimeRoomKey()).isEqualTo(917L);
    assertThat(response.getPrivateWorldInstanceKey()).isEqualTo(4L);
    assertThat(response.getLifecycle().getCanonicalRequestBytes().toByteArray())
        .containsExactly(lifecycleRequest.canonicalBytes());
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(CanonicalInitialPlayerLocationGrpcCodec.MAX_REGION_RESPONSE_BYTES);
  }

  private static void assertInvalid(CanonicalInitialPlayerLocationRequest request) {
    assertThatThrownBy(() -> CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalInitialPlayerLocationRequest request() {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(OPERATION.toString())
        .setExpectedTarget(target())
        .setCanonicalAccountUuid(ACCOUNT.toString())
        .setCharacterUuid(CHARACTER.toString())
        .setAssignmentUuid(ASSIGNMENT.toString())
        .setAssignmentIntentDigest("a".repeat(64))
        .setInitialAdmissionHoldUuid(HOLD.toString())
        .setInitialAdmissionHoldFence(FENCE.toString())
        .setInitialAdmissionRequestId("initial-admission-41")
        .setInitialAdmissionRequestDigest("b".repeat(64))
        .setActiveLifecycleEpoch(7L)
        .setTerminalProofDigest("sha256:" + "c".repeat(64))
        .setCommittedPointerVersion(1L)
        .setAuditEventId(41L)
        .build();
  }

  private static PreseededActorAssignmentExpectedTarget target() {
    return PreseededActorAssignmentExpectedTarget.newBuilder()
        .setCanonicalTenantUuid(TENANT.toString())
        .setRealmUuid(REALM.toString())
        .setWorldSlug("green-hollow")
        .setRealmSlug("sunvale")
        .setGameInstanceId(GAME_INSTANCE.toString())
        .setCatalogRevision(17L)
        .setCanonicalVersionUuid(VERSION.toString())
        .setFrozenPolicyDigest("d".repeat(64))
        .setPlayableStateNamespaceUuid(NAMESPACE.toString())
        .setPublishedReleaseBundleRef("published/green-hollow/42")
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
