package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.worldmanagement.client.GameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Outcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.RequestSelector;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Result;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.junit.jupiter.api.Test;

class CanonicalInitialPlayerLocationServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID GAME_INSTANCE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID PLAYABLE_NAMESPACE = uuid("55555555-5555-4555-8555-555555555555");

  @Test
  void unavailableEntityReceiptStopsBeforeHoldOrPlacementWrite() {
    var request = request();
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request);
    var location = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    when(location.readByOperationId(
            eq(selection.placementOperationId()), any(RequestSelector.class)))
        .thenReturn(Optional.empty());
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var service =
        service(
            selection,
            location,
            holds,
            gameSession,
            ignored -> {
              throw Status.UNAVAILABLE
                  .withDescription("controlled Entity outage")
                  .asRuntimeException();
            });

    assertThatThrownBy(() -> service.place(selection))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));

    verifyNoInteractions(holds, gameSession);
    verify(location, never()).store(any());
  }

  @Test
  void absentReadDoesNotConsultCurrentLifecycleOrAdmissionOwners() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var location = emptyLocation(selection);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var entityReads = new AtomicInteger();
    var service =
        service(
            selection,
            location,
            holds,
            gameSession,
            ignored -> {
              entityReads.incrementAndGet();
              throw new AssertionError("Absent retained operation must not read current sources");
            });

    org.assertj.core.api.Assertions.assertThat(service.read(selection)).isEmpty();
    assertThat(entityReads.get()).isZero();
    verifyNoInteractions(holds, gameSession);
  }

  @Test
  void retainedReadUsesTheStoredPlacementAndImmutableEntityReceiptOnly() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var retained =
        new Result(
            Outcome.CONFLICT,
            "CHARACTER_ALREADY_PLACED",
            null,
            null,
            new byte[] {1},
            "sha256:" + "a".repeat(64),
            new byte[] {2},
            "sha256:" + "b".repeat(64),
            true);
    var location = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    when(location.readByOperationId(
            eq(selection.placementOperationId()), any(RequestSelector.class)))
        .thenReturn(Optional.of(retained));
    var associations = mock(WorldCanonicalInstanceAssociationRepository.class);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var entityReads = new AtomicInteger();
    var service =
        new CanonicalInitialPlayerLocationService(
            NAMESPACE,
            associations,
            holds,
            location,
            gameSession,
            ignored -> {
              entityReads.incrementAndGet();
              return entityReceipt(selection);
            });

    assertThat(service.read(selection)).contains(retained);
    assertThat(entityReads.get()).isEqualTo(1);
    verifyNoInteractions(associations, holds, gameSession);
  }

  @Test
  void regionReadRequiresRetainedAppliedPlacementAndFreshExactEntityReceipt() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var placement =
        new Result(
            Outcome.APPLIED,
            null,
            new RoomTemplateRef(TENANT, VERSION, uuid("abababab-abab-4bab-8bab-abababababab")),
            9182L,
            new byte[] {1},
            "sha256:" + "a".repeat(64),
            new byte[] {2},
            "sha256:" + "b".repeat(64),
            true);
    var location = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    when(location.readByOperationId(
            eq(selection.placementOperationId()), any(RequestSelector.class)))
        .thenReturn(Optional.of(placement));
    UUID canonicalRegion = uuid("abababab-abab-4bab-8bab-abababababac");
    UUID operationalRegion = uuid("abababab-abab-4bab-8bab-abababababaf");
    var lifecycle = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(lifecycle.operationalRegionAssignments())
        .thenReturn(Map.of(canonicalRegion, operationalRegion));
    var region =
        new WorldCanonicalInitialPlayerLocationRepository.RegionResult(
            placement, 77L, 9182L, canonicalRegion, operationalRegion, lifecycle);
    when(location.readInitialPlayerLocationRegion(any(RequestSelector.class)))
        .thenReturn(Optional.of(region));
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var entityReads = new AtomicInteger();
    var service =
        service(
            selection,
            location,
            holds,
            gameSession,
            ignored -> {
              entityReads.incrementAndGet();
              return entityReceipt(selection);
            });

    assertThat(service.readRegion(selection)).contains(region);
    assertThat(entityReads.get()).isEqualTo(1);
    verify(location).readInitialPlayerLocationRegion(any(RequestSelector.class));
    verifyNoInteractions(holds, gameSession);
  }

  @Test
  void mismatchedEntityReceiptStopsBeforeHoldOrPlacementWrite() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var location = emptyLocation(selection);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var service =
        service(
            selection,
            location,
            holds,
            gameSession,
            ignored ->
                entityReceipt(selection).toBuilder()
                    .setCharacterUuid(uuid("66666666-6666-4666-8666-666666666666").toString())
                    .build());

    assertThatThrownBy(() -> service.place(selection))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    verifyNoInteractions(holds, gameSession);
    verify(location, never()).store(any());
  }

  @Test
  void missingCommittedWorldHoldStopsBeforeGameSessionOrPlacementWrite() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var location = emptyLocation(selection);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(holds.readState(any(), any())).thenReturn(Optional.empty());
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var service =
        service(selection, location, holds, gameSession, ignored -> entityReceipt(selection));

    assertThatThrownBy(() -> service.place(selection))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    verifyNoInteractions(gameSession);
    verify(location, never()).store(any());
  }

  @Test
  void mismatchedWorldHoldFenceStopsBeforeGameSessionOrPlacementWrite() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var location = emptyLocation(selection);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(holds.readState(any(), any()))
        .thenAnswer(
            invocation -> {
              HoldIdentity requested = invocation.getArgument(0);
              var mismatchedIdentity =
                  new HoldIdentity(
                      requested.request(),
                      requested.holdId(),
                      uuid("12121212-1212-4212-8212-121212121212"));
              var state = mock(WorldCanonicalInitialAdmissionHoldState.class);
              when(state.holdIdentity()).thenReturn(mismatchedIdentity);
              when(state.holdStatus()).thenReturn(HoldStatus.COMMITTED);
              return Optional.of(state);
            });
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var service =
        service(selection, location, holds, gameSession, ignored -> entityReceipt(selection));

    assertThatThrownBy(() -> service.place(selection))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    verifyNoInteractions(gameSession);
    verify(location, never()).store(any());
  }

  @Test
  void staleWorldActiveEpochStopsBeforeGameSessionOrPlacementWrite() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var location = emptyLocation(selection);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(holds.readState(any(), any()))
        .thenAnswer(
            invocation -> {
              var state = mock(WorldCanonicalInitialAdmissionHoldState.class);
              when(state.holdIdentity()).thenReturn(invocation.getArgument(0));
              when(state.holdStatus()).thenReturn(HoldStatus.COMMITTED);
              var lifecycle = mock(WorldCanonicalInstanceLifecycleEvidence.class);
              when(lifecycle.lifecycleStatus()).thenReturn("ACTIVE");
              when(lifecycle.lifecycleEpoch()).thenReturn(selection.activeLifecycleEpoch() - 1);
              when(state.lifecycleEvidence()).thenReturn(lifecycle);
              return Optional.of(state);
            });
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var service =
        service(selection, location, holds, gameSession, ignored -> entityReceipt(selection));

    assertThatThrownBy(() -> service.place(selection))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    verifyNoInteractions(gameSession);
    verify(location, never()).store(any());
  }

  @Test
  void nonCommittedGameSessionTerminalStopsBeforePlacementWrite() {
    var selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request());
    var location = emptyLocation(selection);
    var holds = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var readIdentity = new AtomicReference<HoldIdentity>();
    when(holds.readState(any(), any()))
        .thenAnswer(
            invocation -> {
              readIdentity.set(invocation.getArgument(0));
              var lifecycleRequest = mock(Request.class);
              when(lifecycleRequest.canonicalGameInstanceId()).thenReturn(GAME_INSTANCE);
              when(lifecycleRequest.canonicalTenantId()).thenReturn(TENANT);
              when(lifecycleRequest.canonicalVersionId()).thenReturn(VERSION);
              when(lifecycleRequest.playableStateNamespaceId()).thenReturn(PLAYABLE_NAMESPACE);
              var start = mock(RoomTemplateRef.class);
              when(start.tenantId()).thenReturn(TENANT);
              when(start.versionId()).thenReturn(VERSION);
              var lifecycle = mock(WorldCanonicalInstanceLifecycleEvidence.class);
              when(lifecycle.lifecycleStatus()).thenReturn("ACTIVE");
              when(lifecycle.lifecycleEpoch()).thenReturn(selection.activeLifecycleEpoch());
              when(lifecycle.request()).thenReturn(lifecycleRequest);
              when(lifecycle.startLocation()).thenReturn(start);
              var state = mock(WorldCanonicalInitialAdmissionHoldState.class);
              when(state.holdIdentity()).thenReturn(invocation.getArgument(0));
              when(state.holdStatus()).thenReturn(HoldStatus.COMMITTED);
              when(state.lifecycleEvidence()).thenReturn(lifecycle);
              return Optional.of(state);
            });
    var terminal = mock(GameSessionCanonicalInitialAdmissionOwnerProof.class);
    when(terminal.holdIdentity()).thenAnswer(invocation -> readIdentity.get());
    when(terminal.outcome())
        .thenReturn(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    var gameSession = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    when(gameSession.read(any())).thenReturn(terminal);

    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    when(descriptor.requestDigest()).thenReturn("sha256:" + "a".repeat(64));
    when(descriptor.resultDigest()).thenReturn("sha256:" + "b".repeat(64));
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
    when(release.evidenceDigest()).thenReturn("sha256:" + "c".repeat(64));
    var bindingEvidence = mock(CompleteLaunchBindingEvidence.class);
    when(bindingEvidence.releaseAttestation()).thenReturn(release);
    var binding = mock(WorldCompleteLaunchBindingReceipt.class);
    when(binding.descriptor()).thenReturn(descriptor);
    when(binding.evidence()).thenReturn(bindingEvidence);
    var association = association(binding);
    var service =
        service(
            selection,
            location,
            holds,
            gameSession,
            ignored -> entityReceipt(selection),
            association);

    assertThatThrownBy(() -> service.place(selection))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    verify(gameSession).read(any());
    verify(location, never()).store(any());
  }

  private static CanonicalInitialPlayerLocationService service(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      WorldCanonicalInitialPlayerLocationRepository location,
      WorldCanonicalInitialAdmissionHoldRepository holds,
      GameSessionCanonicalInitialAdmissionOwnerClient gameSession,
      CanonicalInitialPlayerLocationService.EntityReceiptReader entity) {
    return service(selection, location, holds, gameSession, entity, association(mockBinding()));
  }

  private static CanonicalInitialPlayerLocationService service(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      WorldCanonicalInitialPlayerLocationRepository location,
      WorldCanonicalInitialAdmissionHoldRepository holds,
      GameSessionCanonicalInitialAdmissionOwnerClient gameSession,
      CanonicalInitialPlayerLocationService.EntityReceiptReader entity,
      WorldCanonicalInstanceAssociation association) {
    var associations = mock(WorldCanonicalInstanceAssociationRepository.class);
    when(associations.readOwnerAssociation(selection.canonicalGameInstanceId()))
        .thenReturn(Optional.of(association));
    return new CanonicalInitialPlayerLocationService(
        NAMESPACE, associations, holds, location, gameSession, entity);
  }

  private static WorldCanonicalInitialPlayerLocationRepository emptyLocation(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    var location = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    when(location.readByOperationId(
            eq(selection.placementOperationId()), any(RequestSelector.class)))
        .thenReturn(Optional.empty());
    return location;
  }

  private static WorldCanonicalInstanceAssociation association(
      WorldCompleteLaunchBindingReceipt binding) {
    var identity =
        new WorldCanonicalInstanceAssociation.CanonicalIdentity(
            GAME_INSTANCE,
            NAMESPACE,
            TENANT,
            "green-hollow",
            PLAYABLE_NAMESPACE,
            "SHARED",
            true,
            REALM,
            17L,
            "control-17");
    var prepare =
        new WorldCanonicalInstanceAssociation.WorldPrepareFields(
            5L,
            6L,
            7L,
            8L,
            "control-17",
            "descriptor-17",
            9L,
            null,
            "{}",
            "generation-17",
            10L,
            "published/green-hollow/42",
            1L,
            null);
    var association = mock(WorldCanonicalInstanceAssociation.class);
    when(association.isRunOwnedFixture()).thenReturn(false);
    when(association.identity()).thenReturn(identity);
    when(association.canonicalVersionId()).thenReturn(VERSION);
    when(association.worldPrepareFields()).thenReturn(prepare);
    when(association.completeLaunchBinding()).thenReturn(binding);
    return association;
  }

  private static WorldCompleteLaunchBindingReceipt mockBinding() {
    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    when(descriptor.requestDigest()).thenReturn("sha256:" + "a".repeat(64));
    when(descriptor.resultDigest()).thenReturn("sha256:" + "b".repeat(64));
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
    when(release.evidenceDigest()).thenReturn("sha256:" + "c".repeat(64));
    var evidence = mock(CompleteLaunchBindingEvidence.class);
    when(evidence.releaseAttestation()).thenReturn(release);
    var binding = mock(WorldCompleteLaunchBindingReceipt.class);
    when(binding.descriptor()).thenReturn(descriptor);
    when(binding.evidence()).thenReturn(evidence);
    return binding;
  }

  private static ReadPreseededActorAssignmentReceiptResponse entityReceipt(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    return ReadPreseededActorAssignmentReceiptResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(selection.placementOperationId().toString())
        .setAssignmentUuid(selection.assignmentId().toString())
        .setCanonicalAccountUuid(selection.canonicalAccountId().toString())
        .setCharacterUuid(selection.characterId().toString())
        .setIntentDigest(selection.assignmentIntentDigest())
        .setExpectedTarget(selection.expectedTarget())
        .setCorePayload(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                .newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Test Player")
                .build())
        .setStatus(
            PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED)
        .build();
  }

  private static CanonicalInitialPlayerLocationRequest request() {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString())
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid(TENANT.toString())
                .setRealmUuid(REALM.toString())
                .setWorldSlug("green-hollow")
                .setRealmSlug("sunvale")
                .setGameInstanceId(GAME_INSTANCE.toString())
                .setCatalogRevision(17L)
                .setCanonicalVersionUuid(VERSION.toString())
                .setFrozenPolicyDigest("d".repeat(64))
                .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE.toString())
                .setPublishedReleaseBundleRef("published/green-hollow/42")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .build())
        .setCanonicalAccountUuid(uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString())
        .setCharacterUuid(uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toString())
        .setAssignmentUuid(uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString())
        .setAssignmentIntentDigest("a".repeat(64))
        .setInitialAdmissionHoldUuid(uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee").toString())
        .setInitialAdmissionHoldFence(uuid("ffffffff-ffff-4fff-8fff-ffffffffffff").toString())
        .setInitialAdmissionRequestId("initial-admission-41")
        .setInitialAdmissionRequestDigest("b".repeat(64))
        .setActiveLifecycleEpoch(7L)
        .setTerminalProofDigest("sha256:" + "c".repeat(64))
        .setCommittedPointerVersion(1L)
        .setAuditEventId(41L)
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
