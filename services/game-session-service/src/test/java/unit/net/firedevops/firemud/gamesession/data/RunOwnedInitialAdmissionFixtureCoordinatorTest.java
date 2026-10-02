package net.firedevops.firemud.gamesession.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindRequest;
import net.firedevops.firemud.gamesession.service.RunOwnedInitialLaunchResult;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHoldStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

class RunOwnedInitialAdmissionFixtureCoordinatorTest {
  private static final String RUN_ID = "compose-smoke-2939";
  private static final String PROJECT_NAME = "firemud-smoke-compose-smoke-2939";
  private static final String CAPABILITY_PATH = "/fixture/capability.json";
  private static final String OPERATION_ID = "d2db8478-9c56-42ab-99c2-9fbead6841ba";
  private static final UUID REALM_ID = UUID.fromString("23d39978-9d44-4e1a-8659-998ff9239b01");
  private static final UUID NAMESPACE_ID = UUID.fromString("ed1b4d88-81f8-4404-af7c-9a5dc91d3043");
  private static final UUID HOLD_ID = UUID.fromString("5fdd38a3-09e3-46bb-b11a-e2ff2a096211");
  private static final UUID HOLD_FENCE = UUID.fromString("e385de73-4324-44a8-a815-d0c806b9a0f7");
  private static final UUID ATTEMPT_ID = UUID.fromString("6f39929c-44ee-45c7-9e96-8c6548b84e65");

  @Test
  void commitsOnlyAfterDurableIntentAndExactWorldHoldAndLeavesWorldFinalizationAsync() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);

    RunOwnedInitialAdmissionFixtureCoordinator.BootstrapResult result =
        fixture.coordinator.coordinate(fixture.capability);

    assertThat(result)
        .isEqualTo(RunOwnedInitialAdmissionFixtureCoordinator.BootstrapResult.COMMITTED);
    InOrder order = inOrder(fixture.gameInstanceService, fixture.ownerService, fixture.worldClient);
    order.verify(fixture.gameInstanceService).startRunOwnedInitialLaunch(any());
    order.verify(fixture.ownerService).registerPublicSharedFixtureCatalog(any());
    order.verify(fixture.ownerService).beginIntent(any());
    order
        .verify(fixture.worldClient)
        .acquireInitialAdmissionBindHold(
            eq(7L),
            eq(42L),
            eq(11L),
            eq(4L),
            eq(OPERATION_ID),
            anyString(),
            eq(REALM_ID),
            eq(NAMESPACE_ID),
            eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            eq(3L));
    order.verify(fixture.ownerService).attachHold(any());
    order.verify(fixture.ownerService).commit(any());
    verify(fixture.ownerService, never()).abort(any());
    verify(fixture.ownerService, never()).read(any());
  }

  @Test
  void unresolvedOwnerProofDoesNotReleaseHoldOrCompensate() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
    doAnswer(
            invocation -> {
              InitialAdmissionBindHoldBinding binding = invocation.getArgument(0);
              return proof(binding, Outcome.PENDING, ATTEMPT_ID, null, 0L, null, false);
            })
        .when(fixture.ownerService)
        .commit(any());

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.capability))
        .isInstanceOf(IllegalStateException.class);

    verify(fixture.ownerService, never()).abort(any());
    verify(fixture.ownerService).attachHold(any());
    verify(fixture.ownerService).commit(any());
  }

  @Test
  void rejectsMismatchedWorldHoldBeforeAttachingOrCommitting() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
    doAnswer(
            invocation ->
                AcquireInitialAdmissionBindHoldResponse.newBuilder()
                    .setHold(
                        hold(
                            InitialAdmissionBindHoldStatus
                                .INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING,
                            UUID.fromString("49de9f26-f314-40e1-96a7-5655c5e5b16e"),
                            fixture.request.get().requestDigest()))
                    .build())
        .when(fixture.worldClient)
        .acquireInitialAdmissionBindHold(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(UUID.class),
            any(PlayableStateScope.class),
            anyLong());

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.capability))
        .isInstanceOf(IllegalStateException.class);

    verify(fixture.ownerService, never()).attachHold(any());
    verify(fixture.ownerService, never()).commit(any());
    verify(fixture.ownerService, never()).abort(any());
  }

  @Test
  void rejectsMalformedWorldHoldIdentityBeforeAttachingOrCommitting() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
    doAnswer(
            invocation -> {
              UUID namespaceId = invocation.getArgument(7);
              String requestDigest = invocation.getArgument(5);
              return AcquireInitialAdmissionBindHoldResponse.newBuilder()
                  .setHold(
                      hold(
                              InitialAdmissionBindHoldStatus
                                  .INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING,
                              namespaceId,
                              requestDigest)
                          .toBuilder()
                          .setHoldFence("not-a-uuid")
                          .build())
                  .build();
            })
        .when(fixture.worldClient)
        .acquireInitialAdmissionBindHold(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(UUID.class),
            any(PlayableStateScope.class),
            anyLong());

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.capability))
        .isInstanceOf(IllegalStateException.class);

    verify(fixture.ownerService, never()).attachHold(any());
    verify(fixture.ownerService, never()).commit(any());
    verify(fixture.ownerService, never()).abort(any());
  }

  @Test
  void rejectsMissingOrNoncanonicalCommittedAuditId() {
    for (String auditId : new String[] {null, "031"}) {
      Fixture fixture =
          fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
      doAnswer(
              invocation -> {
                InitialAdmissionBindHoldBinding binding = invocation.getArgument(0);
                return proof(
                    binding,
                    Outcome.COMMITTED,
                    ATTEMPT_ID,
                    auditId,
                    1L,
                    binding.requestDigest(),
                    false);
              })
          .when(fixture.ownerService)
          .commit(any());

      assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.capability))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void terminalWorldAbortUsesExactReadbackWithoutTryingToCommit() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_ABORTED);
    when(fixture.ownerService.read(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHoldBinding binding = invocation.getArgument(0);
              return proof(binding, Outcome.ABORTED, ATTEMPT_ID, null, 0L, null, true);
            });

    assertThat(fixture.coordinator.coordinate(fixture.capability))
        .isEqualTo(RunOwnedInitialAdmissionFixtureCoordinator.BootstrapResult.ABORTED);

    verify(fixture.ownerService).read(any());
    verify(fixture.ownerService, never()).attachHold(any());
    verify(fixture.ownerService, never()).commit(any());
    verify(fixture.ownerService, never()).abort(any());
  }

  @Test
  void disabledCoordinatorDoesNotLoadCapabilityOrCallOwners() {
    GameInstanceService gameInstanceService = org.mockito.Mockito.mock(GameInstanceService.class);
    InitialAdmissionBindOwnerService ownerService =
        org.mockito.Mockito.mock(InitialAdmissionBindOwnerService.class);
    WorldManagementClient worldClient = org.mockito.Mockito.mock(WorldManagementClient.class);
    RunOwnedInitialAdmissionFixtureCoordinator coordinator =
        new RunOwnedInitialAdmissionFixtureCoordinator(
            false,
            "",
            "",
            "",
            new CommonGrpcClientProperties(),
            gameInstanceService,
            ownerService,
            worldClient);

    coordinator.retryRunOwnedFixtureBootstrap();

    verifyNoInteractions(gameInstanceService, ownerService, worldClient);
  }

  @Test
  void exhaustedRetryBudgetStopsMutationAndFreshCoordinatorReplaysSameClaim() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
    AtomicInteger worldAcquisitions = new AtomicInteger();
    doAnswer(
            invocation -> {
              int acquisition = worldAcquisitions.incrementAndGet();
              if (acquisition <= 12) {
                throw new IllegalStateException("Simulated unresolved World response");
              }
              String requestDigest = invocation.getArgument(5);
              return AcquireInitialAdmissionBindHoldResponse.newBuilder()
                  .setHold(
                      hold(
                          InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING,
                          NAMESPACE_ID,
                          requestDigest))
                  .build();
            })
        .when(fixture.worldClient)
        .acquireInitialAdmissionBindHold(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(UUID.class),
            any(PlayableStateScope.class),
            anyLong());

    try (MockedStatic<RunOwnedInitialAdmissionFixtureCapability> capabilityLoader =
        mockStatic(RunOwnedInitialAdmissionFixtureCapability.class)) {
      capabilityLoader
          .when(
              () ->
                  RunOwnedInitialAdmissionFixtureCapability.load(
                      any(Path.class), eq(RUN_ID), eq(PROJECT_NAME), same(fixture.grpcProperties)))
          .thenReturn(fixture.capability);

      // Orchestration unit proof only; capability TLS and durable cross-service behavior have
      // separate proof boundaries.
      for (int attempt = 0; attempt < 12; attempt++) {
        fixture.coordinator.retryRunOwnedFixtureBootstrap();
      }

      verify(fixture.ownerService, times(12)).beginIntent(any());
      verify(fixture.worldClient, times(12))
          .acquireInitialAdmissionBindHold(
              anyLong(),
              anyLong(),
              anyLong(),
              anyLong(),
              eq(OPERATION_ID),
              anyString(),
              eq(REALM_ID),
              eq(NAMESPACE_ID),
              eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
              eq(3L));
      verify(fixture.ownerService, never()).attachHold(any());
      verify(fixture.ownerService, never()).commit(any());
      capabilityLoader.verify(
          () ->
              RunOwnedInitialAdmissionFixtureCapability.load(
                  eq(Path.of(CAPABILITY_PATH)),
                  eq(RUN_ID),
                  eq(PROJECT_NAME),
                  same(fixture.grpcProperties)),
          times(12));

      fixture.coordinator.retryRunOwnedFixtureBootstrap();
      fixture.coordinator.retryRunOwnedFixtureBootstrap();
      verify(fixture.ownerService, times(12)).beginIntent(any());
      verify(fixture.worldClient, times(12))
          .acquireInitialAdmissionBindHold(
              anyLong(),
              anyLong(),
              anyLong(),
              anyLong(),
              anyString(),
              anyString(),
              any(UUID.class),
              any(UUID.class),
              any(PlayableStateScope.class),
              anyLong());
      capabilityLoader.verify(
          () ->
              RunOwnedInitialAdmissionFixtureCapability.load(
                  any(Path.class), anyString(), anyString(), any(CommonGrpcClientProperties.class)),
          times(12));

      RunOwnedInitialAdmissionFixtureCoordinator restarted = newCoordinator(fixture);
      restarted.retryRunOwnedFixtureBootstrap();

      ArgumentCaptor<StartSessionRequest> launchRequests =
          ArgumentCaptor.forClass(StartSessionRequest.class);
      verify(fixture.gameInstanceService, times(13))
          .startRunOwnedInitialLaunch(launchRequests.capture());
      assertThat(launchRequests.getAllValues())
          .allSatisfy(
              request -> {
                assertThat(request.tenantId()).isEqualTo(fixture.capability.tenantId());
                assertThat(request.gameTemplateId()).isEqualTo(fixture.capability.gameTemplateId());
                assertThat(request.ownerAccountId()).isEqualTo(fixture.capability.ownerAccountId());
                assertThat(request.controlPlaneRequestId()).isEqualTo(OPERATION_ID);
              });
      ArgumentCaptor<InitialAdmissionBindRequest> ownerRequests =
          ArgumentCaptor.forClass(InitialAdmissionBindRequest.class);
      verify(fixture.worldClient, times(13))
          .acquireInitialAdmissionBindHold(
              anyLong(),
              anyLong(),
              anyLong(),
              anyLong(),
              eq(OPERATION_ID),
              anyString(),
              eq(REALM_ID),
              eq(NAMESPACE_ID),
              eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
              eq(3L));
      verify(fixture.ownerService, times(13)).beginIntent(ownerRequests.capture());
      assertThat(ownerRequests.getAllValues())
          .extracting(InitialAdmissionBindRequest::initialAdmissionRequestId)
          .containsOnly(OPERATION_ID);
      assertThat(ownerRequests.getAllValues())
          .extracting(InitialAdmissionBindRequest::requestDigest)
          .doesNotContainNull()
          .containsOnly(ownerRequests.getValue().requestDigest());
      verify(fixture.ownerService).attachHold(any());
      verify(fixture.ownerService).commit(any());
      verify(fixture.ownerService, never()).abort(any());

      capabilityLoader.verify(
          () ->
              RunOwnedInitialAdmissionFixtureCapability.load(
                  eq(Path.of(CAPABILITY_PATH)),
                  eq(RUN_ID),
                  eq(PROJECT_NAME),
                  same(fixture.grpcProperties)),
          times(13));
      restarted.retryRunOwnedFixtureBootstrap();
      verify(fixture.ownerService, times(13)).beginIntent(any());
      verify(fixture.ownerService).commit(any());
    }
  }

  private static Fixture fixture(InitialAdmissionBindHoldStatus holdStatus) {
    GameInstanceService gameInstanceService = org.mockito.Mockito.mock(GameInstanceService.class);
    InitialAdmissionBindOwnerService ownerService =
        org.mockito.Mockito.mock(InitialAdmissionBindOwnerService.class);
    WorldManagementClient worldClient = org.mockito.Mockito.mock(WorldManagementClient.class);
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    RunOwnedInitialAdmissionFixtureCapability capability =
        new RunOwnedInitialAdmissionFixtureCapability(
            RUN_ID,
            PROJECT_NAME,
            OPERATION_ID,
            7L,
            17L,
            27L,
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "a".repeat(64),
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
            "b".repeat(64));
    GameInstanceDto target =
        new GameInstanceDto(
            42L,
            7L,
            "v1",
            null,
            null,
            null,
            null,
            17L,
            "launch-descriptor-1",
            11L,
            13L,
            3L,
            "config-1",
            null,
            27L,
            "RUNNING");
    when(gameInstanceService.startRunOwnedInitialLaunch(any()))
        .thenReturn(new RunOwnedInitialLaunchResult(target, 4L));

    InitialAdmissionBindCatalog catalog =
        new InitialAdmissionBindCatalog(
            REALM_ID,
            7L,
            17L,
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            3L,
            NAMESPACE_ID,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            Instant.now());
    when(ownerService.registerPublicSharedFixtureCatalog(
            any(InitialAdmissionBindCatalogDescriptor.class)))
        .thenReturn(catalog);

    AtomicReference<InitialAdmissionBindRequest> request = new AtomicReference<>();
    when(ownerService.beginIntent(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindRequest received = invocation.getArgument(0);
              request.set(received);
              return attempt(received, catalog, null, null, Status.PENDING);
            });
    when(worldClient.acquireInitialAdmissionBindHold(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(UUID.class),
            any(PlayableStateScope.class),
            anyLong()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHoldStatus selectedStatus = holdStatus;
              UUID namespaceId = invocation.getArgument(7);
              String requestDigest = invocation.getArgument(5);
              return AcquireInitialAdmissionBindHoldResponse.newBuilder()
                  .setHold(hold(selectedStatus, namespaceId, requestDigest))
                  .build();
            });
    when(ownerService.attachHold(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHoldBinding binding = invocation.getArgument(0);
              return attempt(
                  request.get(),
                  catalog,
                  UUID.fromString(binding.holdId()),
                  UUID.fromString(binding.holdFence()),
                  Status.PENDING);
            });
    when(ownerService.commit(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHoldBinding binding = invocation.getArgument(0);
              return proof(
                  binding, Outcome.COMMITTED, ATTEMPT_ID, "31", 1L, binding.requestDigest(), false);
            });
    RunOwnedInitialAdmissionFixtureCoordinator coordinator =
        new RunOwnedInitialAdmissionFixtureCoordinator(
            true,
            CAPABILITY_PATH,
            RUN_ID,
            PROJECT_NAME,
            grpc,
            gameInstanceService,
            ownerService,
            worldClient);
    return new Fixture(
        coordinator, capability, grpc, gameInstanceService, ownerService, worldClient, request);
  }

  private static RunOwnedInitialAdmissionFixtureCoordinator newCoordinator(Fixture fixture) {
    return new RunOwnedInitialAdmissionFixtureCoordinator(
        true,
        CAPABILITY_PATH,
        RUN_ID,
        PROJECT_NAME,
        fixture.grpcProperties,
        fixture.gameInstanceService,
        fixture.ownerService,
        fixture.worldClient);
  }

  private static InitialAdmissionBindAttempt attempt(
      InitialAdmissionBindRequest request,
      InitialAdmissionBindCatalog catalog,
      UUID holdId,
      UUID holdFence,
      Status status) {
    Instant now = Instant.now();
    return new InitialAdmissionBindAttempt(
        ATTEMPT_ID,
        request.tenantId(),
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        catalog.realmId(),
        catalog.playableStateNamespaceId(),
        "SHARED",
        true,
        catalog.catalogRevision(),
        request.gameInstanceId(),
        request.versionId(),
        request.activeLifecycleEpoch(),
        holdId,
        holdFence,
        status,
        status == Status.COMMITTED ? 1L : null,
        status == Status.COMMITTED ? 31L : null,
        now,
        now,
        status == Status.PENDING ? null : now);
  }

  private static InitialAdmissionBindHold hold(
      InitialAdmissionBindHoldStatus status, UUID namespaceId, String requestDigest) {
    return InitialAdmissionBindHold.newBuilder()
        .setHoldId(HOLD_ID.toString())
        .setHoldFence(HOLD_FENCE.toString())
        .setTenantId("7")
        .setRealmUuid(REALM_ID.toString())
        .setPlayableStateNamespaceUuid(namespaceId.toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setGameInstanceId("42")
        .setVersionId("11")
        .setActiveLifecycleEpoch(4L)
        .setInitialAdmissionRequestId(OPERATION_ID)
        .setRequestDigest(requestDigest)
        .setExpectedNoPriorPointer(true)
        .setExpectedCatalogRevision(3L)
        .setStatus(status)
        .build();
  }

  private static InitialAdmissionBindOwnerProof proof(
      InitialAdmissionBindHoldBinding binding,
      Outcome outcome,
      UUID attemptId,
      String auditId,
      long pointerVersion,
      String auditDigest,
      boolean futureCommitPrevented) {
    return new InitialAdmissionBindOwnerProof(
        outcome,
        binding.holdId(),
        binding.holdFence(),
        binding.tenantId(),
        binding.realmUuid(),
        binding.playableStateNamespaceUuid(),
        binding.playableStateScope(),
        binding.gameInstanceId(),
        binding.versionId(),
        binding.activeLifecycleEpoch(),
        binding.initialAdmissionRequestId(),
        binding.requestDigest(),
        binding.expectedNoPriorPointer(),
        binding.expectedCatalogRevision(),
        attemptId.toString(),
        auditId,
        pointerVersion,
        auditDigest,
        futureCommitPrevented);
  }

  private record Fixture(
      RunOwnedInitialAdmissionFixtureCoordinator coordinator,
      RunOwnedInitialAdmissionFixtureCapability capability,
      CommonGrpcClientProperties grpcProperties,
      GameInstanceService gameInstanceService,
      InitialAdmissionBindOwnerService ownerService,
      WorldManagementClient worldClient,
      AtomicReference<InitialAdmissionBindRequest> request) {}
}
