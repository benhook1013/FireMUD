package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterService;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRosterGrpcAdapterTest {
  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void parsesCanonicalUuidTargetAndPolicyWithoutUsingCallerIdentityFallback() {
    var parsed = CanonicalGameplayRosterGrpcAdapter.parseRequest(request(target()));

    assertThat(parsed.requestUuid()).isNotNull();
    assertThat(parsed.canonicalAccountUuid()).isNotNull();
    assertThat(parsed.expectedTarget().entryPolicy())
        .isEqualTo(CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
    assertThat(parsed.expectedTarget().playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(parsed.expectedTarget().pointerVersion()).isEqualTo(17L);
    assertThat(parsed.expectedTarget().activeWorldEpoch()).isEqualTo(29L);
  }

  @Test
  void responseTargetEchoesExactOwnerCounters() {
    var expected =
        CanonicalGameplayRosterGrpcAdapter.parseRequest(request(target())).expectedTarget();

    var responseTarget = CanonicalGameplayRosterGrpcAdapter.toProto(expected);

    assertThat(responseTarget.getPointerVersion()).isEqualTo(17L);
    assertThat(responseTarget.getActiveWorldEpoch()).isEqualTo(29L);
  }

  @Test
  void rejectsMalformedIdentityAndUnknownTopLevelFieldsBeforeAnyLookup() {
    CanonicalGameplayRosterRequest malformed =
        request(target()).toBuilder().setCanonicalAccountUuid("not-a-uuid").build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical_account_uuid");

    CanonicalGameplayRosterRequest nilAccount =
        request(target()).toBuilder()
            .setCanonicalAccountUuid("00000000-0000-0000-0000-000000000000")
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(nilAccount))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("canonical_account_uuid must be a canonical non-nil UUID")
        .hasCauseInstanceOf(IllegalArgumentException.class);

    CanonicalGameplayRosterRequest unknown =
        request(target()).toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");
  }

  @Test
  void requiresFullyMatchingUnsignedContextAndUnselectedRosterContext() {
    CanonicalGameplayRosterRequest exact = request(target());
    CanonicalGameplayRosterRequest wrongTenant =
        exact.toBuilder()
            .setPlayerExecutionContext(
                exact.getPlayerExecutionContext().toBuilder()
                    .setTenantId(UUID.randomUUID().toString())
                    .build())
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(wrongTenant))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match exact target");

    CanonicalGameplayRosterRequest selectedCharacter =
        exact.toBuilder()
            .setPlayerExecutionContext(
                exact.getPlayerExecutionContext().toBuilder()
                    .setCharacterId(UUID.randomUUID().toString())
                    .build())
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(selectedCharacter))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("character_id must be unset for roster discovery");

    CanonicalGameplayRosterRequest numericScope =
        exact.toBuilder()
            .setPlayerExecutionContext(
                exact.getPlayerExecutionContext().toBuilder().setPlayableStateScope("0").build())
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(numericScope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be SHARED or ISOLATED");
  }

  @Test
  void rejectsUnknownNestedTargetFieldsAndUnsupportedScopeShape() {
    CanonicalGameplayRosterTarget unknownTarget =
        target().toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(unknownTarget)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Complete expected target is required");

    CanonicalGameplayRosterTarget noScope =
        target().toBuilder()
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(noScope)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("playable_state_scope is required");

    CanonicalGameplayRosterTarget noCanonicalVersion =
        target().toBuilder().clearCanonicalVersionUuid().build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(noCanonicalVersion)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("canonical_version_uuid is required");

    CanonicalGameplayRosterTarget noPointerVersion =
        target().toBuilder().setPointerVersion(0L).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(noPointerVersion)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pointerVersion must be positive");

    CanonicalGameplayRosterTarget negativeWorldEpoch =
        target().toBuilder().setActiveWorldEpoch(-1L).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(negativeWorldEpoch)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("activeWorldEpoch must be positive");

    CanonicalGameplayRosterTarget missingWorldEpoch =
        target().toBuilder().setActiveWorldEpoch(0L).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(missingWorldEpoch)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("activeWorldEpoch must be positive");
  }

  @Test
  void selectedAssignmentRequestParsesExactAccountActorAndCompleteTarget() {
    CanonicalGameplayRosterSelectedAssignmentRequest request = selectedAssignmentRequest(target());

    var parsed = CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(request);

    assertThat(parsed.requestUuid()).isEqualTo(UUID.fromString(request.getRequestUuid()));
    assertThat(parsed.canonicalAccountUuid())
        .isEqualTo(UUID.fromString(request.getCanonicalAccountUuid()));
    assertThat(parsed.selectedCharacterUuid())
        .isEqualTo(UUID.fromString(request.getSelectedCharacterUuid()));
    assertThat(parsed.playerExecutionContext().characterUuid())
        .isEqualTo(parsed.selectedCharacterUuid());
    assertThat(parsed.expectedSnapshot().snapshotUuid())
        .isEqualTo(UUID.fromString(request.getExpectedSnapshot().getSnapshotUuid()));
    assertThat(parsed.expectedTarget().entryPolicy())
        .isEqualTo(CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
    assertThat(parsed.expectedTarget().playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    UUID assignmentUuid = UUID.randomUUID();
    String intentDigest = "a".repeat(64);
    CanonicalGameplayRosterSnapshotReference expectedSnapshot =
        new CanonicalGameplayRosterSnapshotReference(UUID.randomUUID(), "b".repeat(64));
    var response =
        CanonicalGameplayRosterGrpcAdapter.toSelectedAssignmentResponse(
            new CanonicalGameplayRosterSelectedAssignmentReference(
                parsed.requestUuid(),
                parsed.canonicalAccountUuid(),
                parsed.selectedCharacterUuid(),
                parsed.expectedTarget(),
                expectedSnapshot,
                assignmentUuid,
                intentDigest));

    assertThat(response.getRequestUuid()).isEqualTo(request.getRequestUuid());
    assertThat(response.getCanonicalAccountUuid()).isEqualTo(request.getCanonicalAccountUuid());
    assertThat(response.getSelectedCharacterUuid()).isEqualTo(request.getSelectedCharacterUuid());
    assertThat(response.getTarget()).isEqualTo(request.getExpectedTarget());
    assertThat(response.getAssignmentUuid()).isEqualTo(assignmentUuid.toString());
    assertThat(response.getIntentDigest()).isEqualTo(intentDigest);
    assertThat(response.getSnapshot().getSnapshotUuid())
        .isEqualTo(expectedSnapshot.snapshotUuid().toString());
    assertThat(response.getSnapshot().getSnapshotDigest())
        .isEqualTo(expectedSnapshot.snapshotDigest());
  }

  @Test
  void selectedAssignmentRejectsUnknownFieldsAndMalformedActorIdentityBeforeLookup() {
    CanonicalGameplayRosterSelectedAssignmentRequest unknownRequest =
        selectedAssignmentRequest(target()).toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");

    CanonicalGameplayRosterTarget unknownTarget =
        target().toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(
                    selectedAssignmentRequest(unknownTarget)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Complete expected target is required");

    CanonicalGameplayRosterSelectedAssignmentRequest malformedCharacter =
        selectedAssignmentRequest(target()).toBuilder()
            .setSelectedCharacterUuid("not-a-uuid")
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(
                    malformedCharacter))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected_character_uuid");

    CanonicalGameplayRosterSelectedAssignmentRequest noSnapshot =
        selectedAssignmentRequest(target()).toBuilder().clearExpectedSnapshot().build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(noSnapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact expected_snapshot is required");

    CanonicalGameplayRosterSelectedAssignmentRequest selected = selectedAssignmentRequest(target());
    CanonicalGameplayRosterSelectedAssignmentRequest mismatchedContextActor =
        selected.toBuilder()
            .setPlayerExecutionContext(
                selected.getPlayerExecutionContext().toBuilder()
                    .setCharacterId(UUID.randomUUID().toString())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(
                    mismatchedContextActor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("playerExecutionContext.character_id must equal selectedCharacterUuid");
  }

  @Test
  void rpcErrorsUseCanonicalStatusesWithoutPartialResponses() {
    CanonicalGameplayRosterGrpcAdapter unavailableOwnerAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            new CanonicalGameplayRosterService(
                request -> {
                  throw new CanonicalGameplayRosterOwnerEvidencePort
                      .OwnerEvidenceUnavailableException();
                },
                mock(CharacterRepository.class)),
            new CanonicalGameplayRosterSelectedAssignmentService(
                request -> {
                  throw new CanonicalGameplayRosterOwnerEvidencePort
                      .OwnerEvidenceUnavailableException();
                },
                mock(CharacterRepository.class)),
            new SimpleMeterRegistry());
    RecordingObserver<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse>
        unavailableObserver = new RecordingObserver<>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
        () -> unavailableOwnerAdapter.listPreseededRoster(request(target()), unavailableObserver));
    assertOnlyStatus(unavailableObserver, Status.Code.UNAVAILABLE);

    CanonicalGameplayRosterGrpcAdapter malformedAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            new CanonicalGameplayRosterService(
                request -> {
                  throw new AssertionError("malformed request must not reach owner read");
                },
                mock(CharacterRepository.class)),
            new CanonicalGameplayRosterSelectedAssignmentService(
                request -> {
                  throw new AssertionError("malformed request must not reach owner read");
                },
                mock(CharacterRepository.class)),
            new SimpleMeterRegistry());
    RecordingObserver<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse>
        malformedObserver = new RecordingObserver<>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
        () ->
            malformedAdapter.listPreseededRoster(
                request(target()).toBuilder().setCanonicalAccountUuid("malformed").build(),
                malformedObserver));
    assertOnlyStatus(malformedObserver, Status.Code.INVALID_ARGUMENT);

    CanonicalGameplayRosterSelectedAssignmentRequest selectedRequest =
        selectedAssignmentRequest(target());
    CharacterRepository staleRepository = mock(CharacterRepository.class);
    when(staleRepository.readCurrentCanonicalGameplayRosterSnapshot(any(), any(), any()))
        .thenThrow(new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_STALE"));
    var selectedOwner = mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    when(selectedOwner.resolveCurrentTarget(any()))
        .thenAnswer(
            call -> {
              CanonicalGameplayRosterReadRequest request = call.getArgument(0);
              return new CanonicalGameplayRosterOwnerEvidence(
                  request.requestUuid(),
                  request.canonicalAccountUuid(),
                  request.expectedTarget(),
                  Instant.parse("2026-10-01T00:00:00Z"));
            });
    when(selectedOwner.readOriginalSelection(any()))
        .thenAnswer(
            call -> {
              CanonicalGameplayRosterReadRequest request = call.getArgument(0);
              var original =
                  CanonicalGameplayRosterReferenceEvidence.request(
                      request.requestUuid(),
                      request.canonicalAccountUuid(),
                      request.playerExecutionContext().sessionUuid(),
                      "gameplay",
                      request.expectedTarget().toWire());
              var receipt =
                  net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse
                      .newBuilder()
                      .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
                      .setTarget(request.expectedTarget().toWire())
                      .setSnapshotUuid(selectedRequest.getExpectedSnapshot().getSnapshotUuid())
                      .setSnapshotDigest(selectedRequest.getExpectedSnapshot().getSnapshotDigest())
                      .addActors(
                          net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor
                              .newBuilder()
                              .setCharacterUuid(selectedRequest.getSelectedCharacterUuid())
                              .setDisplayName("Fixture Actor")
                              .setActorKind(
                                  net.firedevops.firemud.entitymanagement.v1
                                      .CanonicalGameplayRosterActorKind
                                      .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
                      .build();
              return CanonicalGameplayRosterReferenceEvidence.response(
                      original, 1L, UUID.randomUUID(), receipt)
                  .toBuilder()
                  .setSelectionInput(
                      net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
                          .newBuilder()
                          .setSchemaVersion(1))
                  .setSelectedCharacterUuid(selectedRequest.getSelectedCharacterUuid())
                  .build();
            });
    CanonicalGameplayRosterGrpcAdapter staleSnapshotAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            new CanonicalGameplayRosterService(
                request -> {
                  throw new AssertionError("selected request must use selected service");
                },
                staleRepository),
            new CanonicalGameplayRosterSelectedAssignmentService(selectedOwner, staleRepository),
            new SimpleMeterRegistry());
    RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalGameplayRosterSelectedAssignmentResponse>
        staleSnapshotObserver = new RecordingObserver<>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.SELECTED_ASSIGNMENT_METHOD_NAME,
        () ->
            staleSnapshotAdapter.readSelectedPreseededAssignment(
                selectedRequest, staleSnapshotObserver));
    assertOnlyStatus(staleSnapshotObserver, Status.Code.FAILED_PRECONDITION);
    verify(staleRepository).readCurrentCanonicalGameplayRosterSnapshot(any(), any(), any());

    CanonicalGameplayRosterGrpcAdapter unauthenticatedAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            new CanonicalGameplayRosterService(
                request -> {
                  throw new AssertionError("unauthenticated request must not reach owner read");
                },
                mock(CharacterRepository.class)),
            new CanonicalGameplayRosterSelectedAssignmentService(
                request -> {
                  throw new AssertionError("unauthenticated request must not reach owner read");
                },
                mock(CharacterRepository.class)),
            new SimpleMeterRegistry());
    RecordingObserver<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse>
        unauthenticatedObserver = new RecordingObserver<>();
    unauthenticatedAdapter.listPreseededRoster(request(target()), unauthenticatedObserver);
    assertOnlyStatus(unauthenticatedObserver, Status.Code.UNAUTHENTICATED);
  }

  @Test
  void legacyConstructorReturnsUnavailableForBothUnregisteredCreatedMethods() {
    var adapter =
        new CanonicalGameplayRosterGrpcAdapter(
            mock(CanonicalGameplayRosterService.class),
            mock(CanonicalGameplayRosterSelectedAssignmentService.class),
            new SimpleMeterRegistry());
    var listObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse>();
    withTrustedPeer(
        "entity_management.v1.CanonicalGameplayRosterService/ListPlayerCreatedRoster",
        () -> adapter.listPlayerCreatedRoster(playerCreatedRequest(), listObserver));
    assertOnlyStatus(listObserver, Status.Code.UNAVAILABLE);

    var selectedObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalSelectedPlayerCreatedCharacterResponse>();
    withTrustedPeer(
        "entity_management.v1.CanonicalGameplayRosterService/ReadSelectedPlayerCreatedCharacter",
        () ->
            adapter.readSelectedPlayerCreatedCharacter(selectedCreatedRequest(), selectedObserver));
    assertOnlyStatus(selectedObserver, Status.Code.UNAVAILABLE);
  }

  @Test
  void createdMethodsValidateFullServiceResponsesBeforeReturningThem() {
    var createdService = mock(EntityPlayerCreatedRosterService.class);
    var meterRegistry = new SimpleMeterRegistry();
    var adapter =
        new CanonicalGameplayRosterGrpcAdapter(
            mock(CanonicalGameplayRosterService.class),
            mock(CanonicalGameplayRosterSelectedAssignmentService.class),
            createdService,
            meterRegistry);
    var request = playerCreatedRequest();
    when(createdService.list(request))
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse
                .getDefaultInstance());
    var listObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.PLAYER_CREATED_METHOD_NAME,
        () -> adapter.listPlayerCreatedRoster(request, listObserver));
    assertOnlyStatus(listObserver, Status.Code.INTERNAL);
    verify(createdService).list(request);

    var selectedRequest = selectedCreatedRequest();
    when(createdService.selected(selectedRequest))
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalSelectedPlayerCreatedCharacterResponse.getDefaultInstance());
    var selectedObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalSelectedPlayerCreatedCharacterResponse>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.SELECTED_PLAYER_CREATED_METHOD_NAME,
        () -> adapter.readSelectedPlayerCreatedCharacter(selectedRequest, selectedObserver));
    assertOnlyStatus(selectedObserver, Status.Code.INTERNAL);
    verify(createdService).selected(selectedRequest);
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isEqualTo(2.0);

    var malformedCallerService = mock(EntityPlayerCreatedRosterService.class);
    var malformedCallerAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            mock(CanonicalGameplayRosterService.class),
            mock(CanonicalGameplayRosterSelectedAssignmentService.class),
            malformedCallerService,
            new SimpleMeterRegistry());
    var malformedListObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.PLAYER_CREATED_METHOD_NAME,
        () ->
            malformedCallerAdapter.listPlayerCreatedRoster(
                playerCreatedRequest().toBuilder().setCanonicalAccountUuid("malformed").build(),
                malformedListObserver));
    assertOnlyStatus(malformedListObserver, Status.Code.INVALID_ARGUMENT);

    var malformedSelectedObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalSelectedPlayerCreatedCharacterResponse>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.SELECTED_PLAYER_CREATED_METHOD_NAME,
        () ->
            malformedCallerAdapter.readSelectedPlayerCreatedCharacter(
                selectedCreatedRequest().toBuilder().setCanonicalAccountUuid("malformed").build(),
                malformedSelectedObserver));
    assertOnlyStatus(malformedSelectedObserver, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(malformedCallerService);
  }

  @Test
  void createdServiceEvidenceValidationFailuresMapToUnavailableForBothMethods() {
    var createdService = mock(EntityPlayerCreatedRosterService.class);
    var meterRegistry = new SimpleMeterRegistry();
    var adapter =
        new CanonicalGameplayRosterGrpcAdapter(
            mock(CanonicalGameplayRosterService.class),
            mock(CanonicalGameplayRosterSelectedAssignmentService.class),
            createdService,
            meterRegistry);
    var listRequest = playerCreatedRequest();
    when(createdService.list(listRequest))
        .thenThrow(new IllegalArgumentException("retained owner evidence is invalid"));
    var listObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse>();

    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.PLAYER_CREATED_METHOD_NAME,
        () -> adapter.listPlayerCreatedRoster(listRequest, listObserver));

    assertOnlyStatus(listObserver, Status.Code.UNAVAILABLE);
    verify(createdService).list(listRequest);

    var selectedRequest = selectedCreatedRequest();
    when(createdService.selected(selectedRequest))
        .thenThrow(new IllegalArgumentException("retained selected evidence is invalid"));
    var selectedObserver =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalSelectedPlayerCreatedCharacterResponse>();

    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.SELECTED_PLAYER_CREATED_METHOD_NAME,
        () -> adapter.readSelectedPlayerCreatedCharacter(selectedRequest, selectedObserver));

    assertOnlyStatus(selectedObserver, Status.Code.UNAVAILABLE);
    verify(createdService).selected(selectedRequest);
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isZero();
  }

  @Test
  void createdOwnerDeadlineMapsToUnavailableWithoutInternalMetric() {
    var createdService = mock(EntityPlayerCreatedRosterService.class);
    var meterRegistry = new SimpleMeterRegistry();
    var adapter =
        new CanonicalGameplayRosterGrpcAdapter(
            mock(CanonicalGameplayRosterService.class),
            mock(CanonicalGameplayRosterSelectedAssignmentService.class),
            createdService,
            meterRegistry);
    var request = playerCreatedRequest();
    when(createdService.list(request)).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
    var observer =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse>();

    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.PLAYER_CREATED_METHOD_NAME,
        () -> adapter.listPlayerCreatedRoster(request, observer));

    assertOnlyStatus(observer, Status.Code.UNAVAILABLE);
    verify(createdService).list(request);
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isZero();
  }

  @Test
  void changedCreatedSnapshotReturnsFailedPreconditionWithoutInternalMetric() {
    var createdService = mock(EntityPlayerCreatedRosterService.class);
    var request = selectedCreatedRequest();
    when(createdService.selected(request))
        .thenThrow(new IllegalStateException("CANONICAL_ROSTER_SNAPSHOT_STALE"));
    var meters = new SimpleMeterRegistry();
    var adapter =
        new CanonicalGameplayRosterGrpcAdapter(
            mock(CanonicalGameplayRosterService.class),
            mock(CanonicalGameplayRosterSelectedAssignmentService.class),
            createdService,
            meters);
    var observer =
        new RecordingObserver<
            net.firedevops.firemud.entitymanagement.v1
                .CanonicalSelectedPlayerCreatedCharacterResponse>();
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.SELECTED_PLAYER_CREATED_METHOD_NAME,
        () -> adapter.readSelectedPlayerCreatedCharacter(request, observer));
    assertOnlyStatus(observer, Status.Code.FAILED_PRECONDITION);
    assertThat(meters.counter("grpc.app_error", "code", "INTERNAL").count()).isZero();
    verify(createdService).selected(request);
  }

  @Test
  void expectedOwnerRejectionsReturnFailedPreconditionWithoutPartialResponse() {
    for (String reasonCode :
        java.util.List.of(
            "CANONICAL_ROSTER_ORIGINAL_DEREFERENCE_CLOSED",
            "CANONICAL_ROSTER_LOCAL_ADMISSION_REQUEST_CONFLICT",
            "CANONICAL_ROSTER_LOCAL_ADMISSION_DISPOSED",
            "CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT",
            "CANONICAL_ROSTER_PLAYER_CREATED_SOURCE_CHANGED")) {
      var createdService = mock(EntityPlayerCreatedRosterService.class);
      when(createdService.list(any())).thenThrow(new IllegalStateException(reasonCode));
      var adapter =
          new CanonicalGameplayRosterGrpcAdapter(
              mock(CanonicalGameplayRosterService.class),
              mock(CanonicalGameplayRosterSelectedAssignmentService.class),
              createdService,
              new SimpleMeterRegistry());
      var observer =
          new RecordingObserver<
              net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse>();

      withTrustedPeer(
          CanonicalGameplayRosterPeerInterceptor.PLAYER_CREATED_METHOD_NAME,
          () -> adapter.listPlayerCreatedRoster(playerCreatedRequest(), observer));

      assertOnlyStatus(observer, Status.Code.FAILED_PRECONDITION);
      verify(createdService).list(any());
    }
  }

  @Test
  void unexpectedIllegalStateExceptionReturnsInternalAndRecordsInternalMetric() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AtomicInteger failures = new AtomicInteger();
    var repository = mock(CharacterRepository.class);
    when(repository.reserveOriginalRosterAdmission(any())).thenReturn(UUID.randomUUID());
    var owner = mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    when(owner.readOriginalSelection(any()))
        .thenAnswer(
            call -> {
              CanonicalGameplayRosterReadRequest request = call.getArgument(0);
              var original =
                  CanonicalGameplayRosterReferenceEvidence.request(
                      request.requestUuid(),
                      request.canonicalAccountUuid(),
                      request.playerExecutionContext().sessionUuid(),
                      "gameplay",
                      request.expectedTarget().toWire());
              return CanonicalGameplayRosterReferenceEvidence.response(
                  original, 1L, UUID.randomUUID(), null);
            });
    when(owner.resolveCurrentTarget(any()))
        .thenAnswer(
            call -> {
              throw new IllegalStateException(
                  failures.getAndIncrement() == 0 ? null : "unexpected invariant");
            });
    CanonicalGameplayRosterGrpcAdapter adapter =
        new CanonicalGameplayRosterGrpcAdapter(
            new CanonicalGameplayRosterService(owner, repository),
            new CanonicalGameplayRosterSelectedAssignmentService(
                request -> null, mock(CharacterRepository.class)),
            meterRegistry);
    RecordingObserver<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse>
        observer = new RecordingObserver<>();
    RecordingObserver<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse>
        unexpectedMessageObserver = new RecordingObserver<>();

    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
        () -> adapter.listPreseededRoster(request(target()), observer));
    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
        () -> adapter.listPreseededRoster(request(target()), unexpectedMessageObserver));

    assertOnlyStatus(observer, Status.Code.INTERNAL);
    assertOnlyStatus(unexpectedMessageObserver, Status.Code.INTERNAL);
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isEqualTo(2.0);
  }

  @Test
  void isolatedScopeReturnsFailedPreconditionBeforeOwnerOrRepositoryRead() {
    GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient =
        mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    var ownerEvidenceAdapter =
        new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(ownerClient, "gameplay");
    var grpcAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            new CanonicalGameplayRosterService(ownerEvidenceAdapter, repository),
            new CanonicalGameplayRosterSelectedAssignmentService(request -> null, repository),
            new SimpleMeterRegistry());
    CanonicalGameplayRosterTarget isolatedTarget =
        target().toBuilder()
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
            .build();
    RecordingObserver<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse>
        observer = new RecordingObserver<>();

    withTrustedPeer(
        CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
        () -> grpcAdapter.listPreseededRoster(request(isolatedTarget), observer));

    assertOnlyStatus(observer, Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(ownerClient, repository);
  }

  private static void assertOnlyStatus(RecordingObserver<?> observer, Status.Code expectedCode) {
    assertThat(observer.values).isEmpty();
    assertThat(observer.errorCount).isEqualTo(1);
    assertThat(observer.completed).isFalse();
    assertThat(observer.statusRuntimeFailure).isTrue();
    assertThat(observer.failureCode).isEqualTo(expectedCode);
  }

  private static void withTrustedPeer(String methodName, Runnable action) {
    ServerCall<Object, Object> call = mock(ServerCall.class);
    MethodDescriptor<Object, Object> descriptor = mock(MethodDescriptor.class);
    when(descriptor.getFullMethodName()).thenReturn(methodName);
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    SSLSession trustedSession = peerSession("spiffe://firemud/ns/gameplay/sa/game-session-service");
    when(call.getAttributes())
        .thenReturn(
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, trustedSession).build());
    ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              action.run();
              return new ServerCall.Listener<>() {};
            });

    new CanonicalGameplayRosterPeerInterceptor("gameplay")
        .interceptCall(call, new Metadata(), next);
    verify(next).startCall(any(), any());
  }

  private static SSLSession peerSession(String uri) {
    X509Certificate leaf = mock(X509Certificate.class);
    try {
      when(leaf.getSubjectAlternativeNames())
          .thenReturn(java.util.List.of(java.util.List.of(6, uri)));
      SSLSession session = mock(SSLSession.class);
      when(session.getPeerCertificates()).thenReturn(new Certificate[] {leaf});
      return session;
    } catch (java.security.cert.CertificateParsingException
        | javax.net.ssl.SSLPeerUnverifiedException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final java.util.List<T> values = new java.util.ArrayList<>();
    private Status.Code failureCode;
    private boolean statusRuntimeFailure;
    private int errorCount;
    private boolean completed;

    @Override
    public void onNext(T value) {
      values.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      errorCount++;
      statusRuntimeFailure = throwable instanceof StatusRuntimeException;
      failureCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static CanonicalGameplayRosterRequest request(CanonicalGameplayRosterTarget target) {
    UUID requestUuid = UUID.randomUUID();
    UUID accountUuid = UUID.randomUUID();
    return CanonicalGameplayRosterRequest.newBuilder()
        .setRequestUuid(requestUuid.toString())
        .setCanonicalAccountUuid(accountUuid.toString())
        .setExpectedTarget(target)
        .setPlayerExecutionContext(executionContext(target, requestUuid, accountUuid, null))
        .build();
  }

  private static CanonicalPlayerCreatedRosterRequest playerCreatedRequest() {
    UUID requestUuid = UUID.randomUUID();
    UUID accountUuid = UUID.randomUUID();
    var target = playerCreatedTarget();
    return CanonicalPlayerCreatedRosterEvidence.requireRequest(
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(requestUuid.toString())
            .setCanonicalAccountUuid(accountUuid.toString())
            .setExpectedTarget(target)
            .setExpectedSource(playerCreatedSource())
            .setPlayerExecutionContext(executionContext(target, requestUuid, accountUuid, null))
            .build());
  }

  private static CanonicalSelectedPlayerCreatedCharacterRequest selectedCreatedRequest() {
    var list = playerCreatedRequest();
    UUID selected = UUID.randomUUID();
    return CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(
        CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
            .setRequestUuid(list.getRequestUuid())
            .setCanonicalAccountUuid(list.getCanonicalAccountUuid())
            .setSelectedCharacterUuid(selected.toString())
            .setExpectedTarget(list.getExpectedTarget())
            .setExpectedSnapshot(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                    .newBuilder()
                    .setSnapshotUuid(UUID.randomUUID().toString())
                    .setSnapshotDigest("a".repeat(64)))
            .setExpectedSource(list.getExpectedSource())
            .setPlayerExecutionContext(
                list.getPlayerExecutionContext().toBuilder().setCharacterId(selected.toString()))
            .build());
  }

  private static CanonicalGameplayRosterTarget playerCreatedTarget() {
    return target().toBuilder()
        .setEntryPolicy(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
        .build();
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity playerCreatedSource() {
    return CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
        CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
            .setSchemaVersion(1)
            .setDescriptorUuid(UUID.randomUUID().toString())
            .setDescriptorRevisionUuid(UUID.randomUUID().toString())
            .setDescriptorDigest("sha256:" + "1".repeat(64))
            .setAuthoredBindingUuid(UUID.randomUUID().toString())
            .setAuthoredBindingDigest("sha256:" + "2".repeat(64))
            .build());
  }

  private static CanonicalGameplayRosterSelectedAssignmentRequest selectedAssignmentRequest(
      CanonicalGameplayRosterTarget target) {
    UUID requestUuid = UUID.randomUUID();
    UUID accountUuid = UUID.randomUUID();
    UUID selectedUuid = UUID.randomUUID();
    return CanonicalGameplayRosterSelectedAssignmentRequest.newBuilder()
        .setRequestUuid(requestUuid.toString())
        .setCanonicalAccountUuid(accountUuid.toString())
        .setSelectedCharacterUuid(selectedUuid.toString())
        .setExpectedTarget(target)
        .setExpectedSnapshot(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                .newBuilder()
                .setSnapshotUuid(UUID.randomUUID().toString())
                .setSnapshotDigest("b".repeat(64))
                .build())
        .setPlayerExecutionContext(executionContext(target, requestUuid, accountUuid, selectedUuid))
        .build();
  }

  private static PlayerExecutionContext executionContext(
      CanonicalGameplayRosterTarget target,
      UUID requestUuid,
      UUID accountUuid,
      UUID characterUuid) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(accountUuid.toString())
        .setTenantId(target.getTenantUuid())
        .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
        .setGameInstanceId(target.getGameInstanceUuid())
        .setCharacterId(characterUuid == null ? "" : characterUuid.toString())
        .setSessionId(UUID.randomUUID().toString())
        .setRealmId(target.getRealmUuid())
        .setRequestId(requestUuid.toString())
        .setPlayableStateScope(
            target.getPlayableStateScope() == PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
                ? "SHARED"
                : "ISOLATED")
        .build();
  }

  private static CanonicalGameplayRosterTarget target() {
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(UUID.randomUUID().toString())
        .setRealmUuid(UUID.randomUUID().toString())
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .setGameInstanceUuid(UUID.randomUUID().toString())
        .setCatalogRevision(3L)
        .setCanonicalVersionUuid("17000000-0000-4000-8000-000000000017")
        .setPointerVersion(17L)
        .setActiveWorldEpoch(29L)
        .setPublishedPolicyDigest("1".repeat(64))
        .setPublishedReleaseBundleRef("release/test")
        .setAdmissionPointerSnapshotDigest("2".repeat(64))
        .setPublishedOwnerProofDigest("3".repeat(64))
        .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
        .build();
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
