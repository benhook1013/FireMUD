package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandAliasResolver;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupResultRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.InstanceCutoverCompatibilityService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.service.VersionUpgradePreparationService;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateRequest;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceRequest;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorRequest;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorResponse;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchResponse;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasRequest;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityRequest;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionControlPlaneGrpcServiceTest {
  private static GameDesignClient gameDesignClient() {
    GameDesignClient client = Mockito.mock(GameDesignClient.class);
    Mockito.when(client.getPublishedScriptPatchVersion(Mockito.anyLong(), Mockito.anyString()))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setScriptPatchVersion("patch-2")
                        .setVersionId(17L)
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(150L)
                        .build())
                .build());
    Mockito.when(
            client.getPublishedPluginVersion(
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(
            invocation ->
                GetPublishedPluginVersionResponse.newBuilder()
                    .setPluginVersion(
                        PublishedPluginVersion.newBuilder()
                            .setPluginId(invocation.getArgument(1))
                            .setPluginVersionId(invocation.getArgument(2))
                            .setPublicationId(31L)
                            .setPublicationState(
                                net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                    .VERSION_LIFECYCLE_STATE_PUBLISHED)
                            .setStatusReason("signature_verified")
                            .setLastChangedAtMs(275L)
                            .build())
                    .build());
    return client;
  }

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void setPinnedScriptPatchVersionRejectsNonAdminCaller() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionControlPlaneGrpcService service =
        newService(Mockito.mock(GameInstanceRepository.class), meterRegistry);

    AtomicReference<SetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.setPinnedScriptPatchVersion(
        SetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setTargetScriptPatchVersion("patch-2")
            .setActorPrincipal("tester")
            .setReason("test")
            .setControlPlaneRequestId("req-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("PERMISSION_DENIED", responseRef.get().getError().getCode());
    assertEquals("Admin role required", responseRef.get().getError().getMessage());
    assertEquals(
        1.0,
        meterRegistry.get("grpc.app_error").tag("code", "PERMISSION_DENIED").counter().count());
  }

  @Test
  void setPinnedScriptPatchVersionAllowsAdminCaller() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptPatchPinnedAt(Instant.parse("2026-01-01T00:00:00Z"));
    instance.setScriptPatchPinnedBy("old-user");
    instance.setScriptPatchPinnedReason("old-reason");
    instance.setScriptPatchPinnedControlPlaneRequestId("req-0");
    instance.setOwnerAccountId(99L);
    instance.setStatus("RUNNING");
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service = newService(repository);

    AtomicReference<SetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.setPinnedScriptPatchVersion(
        SetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setTargetScriptPatchVersion("patch-2")
            .setActorPrincipal("tester")
            .setReason("test")
            .setControlPlaneRequestId("req-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("patch-1", responseRef.get().getPreviousScriptPatchVersion());
    assertEquals("patch-2", responseRef.get().getPinnedScriptPatchVersion());
    assertEquals("req-1", instance.getScriptPatchPinnedControlPlaneRequestId());
    Mockito.verify(repository).save(Mockito.any(GameInstance.class));
  }

  @Test
  void getPinnedScriptPatchVersionRequiresAuthenticatedCaller() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionControlPlaneGrpcService service =
        newService(Mockito.mock(GameInstanceRepository.class), meterRegistry);

    AtomicReference<GetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.getPinnedScriptPatchVersion(
        GetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("", responseRef.get().getPinnedScriptPatchVersion());
    assertEquals("PERMISSION_DENIED", responseRef.get().getError().getCode());
    assertEquals("Admin role required", responseRef.get().getError().getMessage());
    assertEquals(
        1.0,
        meterRegistry.get("grpc.app_error").tag("code", "PERMISSION_DENIED").counter().count());
  }

  @Test
  void getPinnedScriptPatchVersionReturnsPersistedRequestId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-9");
    instance.setScriptPatchPinnedAt(Instant.parse("2026-04-22T00:00:00Z"));
    instance.setScriptPatchPinnedBy("operator-1");
    instance.setScriptPatchPinnedControlPlaneRequestId("req-99");
    instance.setOwnerAccountId(99L);
    instance.setStatus("RUNNING");
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service = newService(repository);

    AtomicReference<GetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.getPinnedScriptPatchVersion(
        GetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("patch-9", responseRef.get().getPinnedScriptPatchVersion());
    assertEquals("req-99", responseRef.get().getControlPlaneRequestId());
    assertEquals(17L, responseRef.get().getPublication().getVersionId());
  }

  @Test
  void getGameInstanceRuntimeStateReturnsCanonicalVersionAndPinMetadata() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("runtime-v7");
    instance.setScriptPatchVersion("patch-2");
    instance.setLaunchDescriptorId("ld-9");
    instance.setStatus("RUNNING");
    instance.setVersionId(11L);
    instance.setReleaseBundleId(19L);
    instance.setVersionStateEpoch(77L);
    instance.setScriptPatchPinnedAt(Instant.parse("2026-04-22T00:00:00Z"));
    instance.setScriptPatchPinnedBy("operator-1");
    instance.setScriptPatchPinnedReason("roll-forward");
    instance.setScriptPatchPinnedControlPlaneRequestId("req-77");
    RuntimeRegionStatus runtimeStatus = new RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(7L);
    runtimeStatus.setRegionId("region-7");
    runtimeStatus.setRegionEpoch(22L);
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));
    Mockito.when(authorityService.findByRuntimeTarget(1L, 7L))
        .thenReturn(
            Optional.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Production",
                    1L,
                    7L,
                    11L,
                    true,
                    true,
                    true,
                    "SHARED",
                    "CREATE_ALLOWED")));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            repository,
            Mockito.mock(GameplayCommandRepository.class),
            runtimeRepository(runtimeStatus),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            gameDesignClient(),
            BuiltInTextCommandAliasResolver.unsupported(),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<GetGameInstanceRuntimeStateResponse> responseRef = new AtomicReference<>();
    service.getGameInstanceRuntimeState(
        GetGameInstanceRuntimeStateRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameInstanceRuntimeStateResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("runtime-v7", responseRef.get().getRuntimeState().getRuntimeVersionId());
    assertEquals("11", responseRef.get().getRuntimeState().getVersionId());
    assertEquals("19", responseRef.get().getRuntimeState().getReleaseBundleId());
    assertEquals(77L, responseRef.get().getRuntimeState().getVersionStateEpoch());
    assertEquals(
        Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(),
        responseRef.get().getRuntimeState().getScriptPatchPinnedAtMs());
    assertEquals("operator-1", responseRef.get().getRuntimeState().getScriptPatchPinnedBy());
    assertEquals("roll-forward", responseRef.get().getRuntimeState().getScriptPatchPinnedReason());
    assertEquals(
        "req-77", responseRef.get().getRuntimeState().getScriptPatchPinnedControlPlaneRequestId());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        responseRef.get().getRuntimeState().getPlayableStateScope());
    assertEquals("demo", responseRef.get().getRuntimeState().getWorldSlug());
    assertEquals("production", responseRef.get().getRuntimeState().getRealmSlug());
    assertEquals(11L, responseRef.get().getRuntimeState().getPointerVersion());
    assertEquals("region-7", responseRef.get().getRuntimeState().getRegionId());
    assertEquals(22L, responseRef.get().getRuntimeState().getRegionEpoch());
    assertEquals(17L, responseRef.get().getRuntimeState().getPublication().getVersionId());
  }

  @Test
  void validateBuiltInCommandAliasReturnsUnsupportedWhenAliasIsUnknown() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            BuiltInTextCommandAliasResolver.unsupported(),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ValidateBuiltInCommandAliasResponse> responseRef = new AtomicReference<>();
    service.validateBuiltInCommandAlias(
        ValidateBuiltInCommandAliasRequest.newBuilder().setAlias("LoGoFf").build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ValidateBuiltInCommandAliasResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals(false, responseRef.get().getSupported());
    assertEquals("", responseRef.get().getNormalizedAlias());
  }

  @Test
  void getGameSessionPinConvergenceReturnsPersistedPinObservation() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setScriptPatchVersion("patch-2");
    instance.setScriptPatchPinnedAt(Instant.parse("2026-04-22T00:00:00Z"));
    instance.setScriptPatchPinnedControlPlaneRequestId("req-77");
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service = newService(repository);

    AtomicReference<GetGameSessionPinConvergenceResponse> responseRef = new AtomicReference<>();
    service.getGameSessionPinConvergence(
        GetGameSessionPinConvergenceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameSessionPinConvergenceResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("1", responseRef.get().getTenantId());
    assertEquals("7", responseRef.get().getGameInstanceId());
    assertEquals("patch-2", responseRef.get().getObservedPinnedScriptPatchVersion());
    assertEquals("req-77", responseRef.get().getLastObservedControlPlaneRequestId());
    assertEquals(
        Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), responseRef.get().getObservedAtMs());
    assertEquals(true, responseRef.get().getIsStale());
    assertEquals(17L, responseRef.get().getPublication().getVersionId());
  }

  @Test
  void getGameSessionPinConvergenceCanReportFreshObservation() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setScriptPatchVersion("patch-2");
    instance.setScriptPatchPinnedAt(Instant.now());
    instance.setScriptPatchPinnedControlPlaneRequestId("req-77");
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionProperties properties = new GameSessionProperties();
    properties.setPinConvergenceStaleThresholdMs(60_000L);
    GameSessionControlPlaneGrpcService service =
        newService(repository, new SimpleMeterRegistry(), properties);

    AtomicReference<GetGameSessionPinConvergenceResponse> responseRef = new AtomicReference<>();
    service.getGameSessionPinConvergence(
        GetGameSessionPinConvergenceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameSessionPinConvergenceResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals(false, responseRef.get().getIsStale());
  }

  @Test
  void setAdmissionPointerAllowsAdminCaller() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance targetInstance = new GameInstance();
    targetInstance.setId(7L);
    targetInstance.setTenantId(1L);
    targetInstance.setVersionId(9L);
    targetInstance.setLaunchDescriptorId("ld-9");
    targetInstance.setRemapSetId("remap-1");
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(targetInstance));
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.findPointer("demo", "production"))
        .thenReturn(
            Optional.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    5L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    Mockito.when(authorityService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    "pvu-1",
                    Instant.parse("2026-04-15T00:00:00Z"))));
    VersionUpgradePreparationService versionUpgradePreparationService =
        Mockito.mock(VersionUpgradePreparationService.class);
    Mockito.when(versionUpgradePreparationService.getPreparedVersionUpgrade(1L, "pvu-1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto(
                "pvu-1",
                "prep-req-1",
                1L,
                5L,
                7L,
                9L,
                "ld-9",
                "remap-1",
                "COMPATIBLE",
                List.of(),
                List.of("WORLD", "ENTITY"),
                Instant.parse("2026-04-16T00:00:00Z"),
                List.of(),
                null,
                null,
                null,
                null));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            versionUpgradePreparationService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<SetAdmissionPointerResponse> responseRef = new AtomicReference<>();
    service.setAdmissionPointer(
        SetAdmissionPointerRequest.newBuilder()
            .setWorldSlug("demo")
            .setWorldDisplayName("Demo World")
            .setRealmSlug("production")
            .setRealmDisplayName("Live Realm")
            .setTenantId("1")
            .setGameInstanceId("7")
            .setVisible(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-1")
            .setExpectedPointerVersion(2L)
            .setPreparedVersionUpgradeId("pvu-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetAdmissionPointerResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("demo", responseRef.get().getPointer().getWorldSlug());
    assertEquals(3L, responseRef.get().getPointer().getPointerVersion());
    assertEquals("pvu-1", responseRef.get().getPointer().getPreparedVersionUpgradeId());
    Mockito.verify(authorityService)
        .upsertPointer(
            Mockito.argThat(
                mutation ->
                    Objects.equals(mutation.expectedPointerVersion(), 2L)
                        && Objects.equals(mutation.preparedVersionUpgradeId(), "pvu-1")));
  }

  @Test
  void setAdmissionPointerRejectsTargetChangeWithoutPreparedUpgrade() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance targetInstance = new GameInstance();
    targetInstance.setId(7L);
    targetInstance.setTenantId(1L);
    targetInstance.setVersionId(9L);
    targetInstance.setLaunchDescriptorId("ld-9");
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(targetInstance));
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.findPointer("demo", "production"))
        .thenReturn(
            Optional.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    5L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<SetAdmissionPointerResponse> responseRef = new AtomicReference<>();
    service.setAdmissionPointer(
        SetAdmissionPointerRequest.newBuilder()
            .setWorldSlug("demo")
            .setWorldDisplayName("Demo World")
            .setRealmSlug("production")
            .setRealmDisplayName("Live Realm")
            .setTenantId("1")
            .setGameInstanceId("7")
            .setVisible(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-1")
            .setExpectedPointerVersion(2L)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetAdmissionPointerResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("CUTOVER_PREPARATION_INVALID", responseRef.get().getError().getCode());
    Mockito.verify(authorityService, Mockito.never()).upsertPointer(Mockito.any());
  }

  @Test
  void setAdmissionPointerRejectsStaleExpectedVersion() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance targetInstance = new GameInstance();
    targetInstance.setId(7L);
    targetInstance.setTenantId(1L);
    targetInstance.setVersionId(9L);
    targetInstance.setLaunchDescriptorId("ld-9");
    targetInstance.setRemapSetId("remap-1");
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(targetInstance));
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.findPointer("demo", "production"))
        .thenReturn(
            Optional.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    5L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    Mockito.when(authorityService.upsertPointer(Mockito.any()))
        .thenThrow(
            new AdmissionPointerVersionMismatchException(
                "expected_pointer_version does not match current pointer version"));
    VersionUpgradePreparationService versionUpgradePreparationService =
        Mockito.mock(VersionUpgradePreparationService.class);
    Mockito.when(versionUpgradePreparationService.getPreparedVersionUpgrade(1L, "pvu-1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto(
                "pvu-1",
                "prep-req-1",
                1L,
                5L,
                7L,
                9L,
                "ld-9",
                "remap-1",
                "COMPATIBLE",
                List.of(),
                List.of("WORLD", "ENTITY"),
                Instant.parse("2026-04-16T00:00:00Z"),
                List.of(),
                null,
                null,
                null,
                null));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            versionUpgradePreparationService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<SetAdmissionPointerResponse> responseRef = new AtomicReference<>();
    service.setAdmissionPointer(
        SetAdmissionPointerRequest.newBuilder()
            .setWorldSlug("demo")
            .setWorldDisplayName("Demo World")
            .setRealmSlug("production")
            .setRealmDisplayName("Live Realm")
            .setTenantId("1")
            .setGameInstanceId("7")
            .setVisible(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-2")
            .setExpectedPointerVersion(2L)
            .setPreparedVersionUpgradeId("pvu-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetAdmissionPointerResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("POINTER_VERSION_MISMATCH", responseRef.get().getError().getCode());
  }

  @Test
  void executePreparedVersionCutoverSwapsPointerUsingPreparedProof() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance targetInstance = new GameInstance();
    targetInstance.setId(7L);
    targetInstance.setTenantId(1L);
    targetInstance.setVersionId(9L);
    targetInstance.setLaunchDescriptorId("ld-9");
    targetInstance.setRemapSetId("remap-1");
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(targetInstance));
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.findPointer("demo", "production"))
        .thenReturn(
            Optional.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    5L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    Mockito.when(authorityService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    "pvu-1",
                    Instant.parse("2026-04-16T00:00:00Z"))));
    VersionUpgradePreparationService versionUpgradePreparationService =
        Mockito.mock(VersionUpgradePreparationService.class);
    Mockito.when(versionUpgradePreparationService.getPreparedVersionUpgrade(1L, "pvu-1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto(
                "pvu-1",
                "prep-req-1",
                1L,
                5L,
                7L,
                9L,
                "ld-9",
                "remap-1",
                "COMPATIBLE",
                List.of(),
                List.of("WORLD", "ENTITY"),
                Instant.parse("2026-04-16T00:00:00Z"),
                List.of(),
                null,
                null,
                null,
                null));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            versionUpgradePreparationService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ExecutePreparedVersionCutoverResponse> responseRef = new AtomicReference<>();
    service.executePreparedVersionCutover(
        ExecutePreparedVersionCutoverRequest.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setTenantId("1")
            .setTargetGameInstanceId("7")
            .setPreparedVersionUpgradeId("pvu-1")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-1")
            .setExpectedPointerVersion(2L)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ExecutePreparedVersionCutoverResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(3L, responseRef.get().getPointer().getPointerVersion());
    assertEquals("pvu-1", responseRef.get().getPointer().getPreparedVersionUpgradeId());
    Mockito.verify(versionUpgradePreparationService)
        .markPreparedVersionUpgradeExecuted(1L, "pvu-1", 7L, 3L, "req-1");
    Mockito.verify(authorityService)
        .upsertPointer(
            Mockito.argThat(
                mutation ->
                    Objects.equals(mutation.preparedVersionUpgradeId(), "pvu-1")
                        && Objects.equals(mutation.expectedPointerVersion(), 2L)
                        && mutation.gameInstanceId() == 7L));
  }

  @Test
  void executePreparedVersionCutoverIsIdempotentAfterSameRequestAlreadyMovedPointer() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.findPointer("demo", "production"))
        .thenReturn(
            Optional.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    Mockito.when(authorityService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    "pvu-1",
                    Instant.parse("2026-04-16T00:00:00Z"))));
    VersionUpgradePreparationService versionUpgradePreparationService =
        Mockito.mock(VersionUpgradePreparationService.class);
    Mockito.when(versionUpgradePreparationService.getPreparedVersionUpgrade(1L, "pvu-1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto(
                "pvu-1",
                "prep-req-1",
                1L,
                5L,
                7L,
                9L,
                "ld-9",
                "remap-1",
                "COMPATIBLE",
                List.of(),
                List.of("WORLD", "ENTITY"),
                Instant.parse("2026-04-16T00:00:00Z"),
                List.of(),
                7L,
                3L,
                Instant.parse("2026-04-16T00:00:01Z"),
                "req-1"));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            versionUpgradePreparationService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ExecutePreparedVersionCutoverResponse> responseRef = new AtomicReference<>();
    service.executePreparedVersionCutover(
        ExecutePreparedVersionCutoverRequest.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setTenantId("1")
            .setTargetGameInstanceId("7")
            .setPreparedVersionUpgradeId("pvu-1")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-1")
            .setExpectedPointerVersion(2L)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ExecutePreparedVersionCutoverResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(3L, responseRef.get().getPointer().getPointerVersion());
    assertEquals("pvu-1", responseRef.get().getPointer().getPreparedVersionUpgradeId());
    Mockito.verify(authorityService, Mockito.never()).upsertPointer(Mockito.any());
    Mockito.verify(versionUpgradePreparationService, Mockito.never())
        .markPreparedVersionUpgradeExecuted(
            Mockito.anyLong(),
            Mockito.anyString(),
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyString());
  }

  @Test
  void listAdmissionPointersRequiresAdminCaller() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ListAdmissionPointersResponse> responseRef = new AtomicReference<>();
    service.listAdmissionPointers(
        ListAdmissionPointersRequest.getDefaultInstance(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListAdmissionPointersResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("PERMISSION_DENIED", responseRef.get().getError().getCode());
  }

  @Test
  void listAdmissionPointerAuditReturnsEntriesForAdminCaller() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    "pvu-1",
                    Instant.parse("2026-04-15T00:00:00Z")),
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    6L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "previous",
                    "req-0",
                    null,
                    Instant.parse("2026-04-14T00:00:00Z"))));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ListAdmissionPointerAuditResponse> responseRef = new AtomicReference<>();
    service.listAdmissionPointerAudit(
        ListAdmissionPointerAuditRequest.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListAdmissionPointerAuditResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals(2, responseRef.get().getAuditCount());
    assertEquals("demo", responseRef.get().getAudit(0).getWorldSlug());
    assertEquals(3L, responseRef.get().getAudit(0).getPointerVersion());
  }

  @Test
  void getGameplayCommandStatusReturnsLedgerRecordForAdminCaller() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-123");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(41L);
    command.setAccountId(9L);
    command.setCharacterId(44L);
    command.setCommandName("LOOK");
    command.setSanitizedCommandText("LOOK");
    command.setRequiresSoloTick(false);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    command.setStagedAt(Instant.parse("2026-04-15T00:00:01Z"));
    command.setLastAttemptAt(Instant.parse("2026-04-15T00:00:01Z"));
    command.setAttemptCount(1);
    command.setEnqueueSeq(33L);
    command.setPlayableStateScope("SHARED");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(17L);
    command.setScriptPatchVersion("patch-1");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    command.setOriginSourceKind("GAMEPLAY_EVENT");
    command.setOriginSourceState("WORK_ITEM_PERSISTED");
    command.setOriginSourceOrdinal(41L);
    command.setOriginSourceDueTickId(14L);
    command.setQueueSourceKind("GAMEPLAY_COMMAND");
    command.setQueueSourceState("REDIS_PENDING_CLAIMED");
    command.setQueueSourceOrdinal(33L);
    command.setQueueSourceDueTickId(14L);
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    RemoteFollowupResultRepository remoteFollowupResultRepository =
        Mockito.mock(RemoteFollowupResultRepository.class);
    Mockito.when(commandRepository.findByCommandId("cmd-123")).thenReturn(Optional.of(command));
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setTenantId(1L);
    coordinator.setCommandId("cmd-123");
    coordinator.setCoordinatorId("coord-1");
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("origin-region");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setTargetGameInstanceId(9L);
    coordinator.setTargetRegionId("target-region");
    coordinator.setTargetRegionEpoch(8L);
    coordinator.setState("PENDING_REMOTE");
    Mockito.when(remoteCommandCoordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-123"))
        .thenReturn(Optional.of(coordinator));
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setTenantId(1L);
    result.setCoordinatorId("coord-1");
    result.setOutcome("ABANDONED");
    result.setResultPayloadJson(
        "{\"failureCode\":\"REMOTE_FOLLOWUP_KIND_UNSUPPORTED\",\"message\":\"Target region rejected the remote payload kind\"}");
    result.setResultMessage("Target region rejected the remote payload kind");
    result.setObservedAt(Instant.parse("2026-04-15T00:00:03Z"));
    Mockito.when(
            remoteFollowupResultRepository.findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
                1L, "coord-1"))
        .thenReturn(List.of(result));
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    Mockito.when(gameDesignClient.getPublishedScriptPatchVersion(1L, "patch-1"))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setScriptPatchVersion("patch-1")
                        .setVersionId(17L)
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(150L)
                        .build())
                .build());
    Mockito.when(gameDesignClient.getPublishedPluginVersion(1L, "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setPublicationId(51L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setStatusReason("signature_verified")
                        .setLastChangedAtMs(175L)
                        .build())
                .build());
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            commandRepository,
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(RemoteFollowupRepository.class),
            remoteCommandCoordinatorRepository,
            remoteFollowupResultRepository,
            null,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            gameDesignClient,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry(),
            new GameSessionProperties());

    AtomicReference<GetGameplayCommandStatusResponse> responseRef = new AtomicReference<>();
    service.getGameplayCommandStatus(
        GetGameplayCommandStatusRequest.newBuilder().setCommandId("cmd-123").build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameplayCommandStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("cmd-123", responseRef.get().getCommand().getCommandId());
    assertEquals("STAGED", responseRef.get().getCommand().getExecutionOutcome());
    assertEquals("LOOK", responseRef.get().getCommand().getSanitizedCommandText());
    assertEquals(33L, responseRef.get().getCommand().getEnqueueSeq());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        responseRef.get().getCommand().getPlayableStateScope());
    assertEquals("demo", responseRef.get().getCommand().getWorldSlug());
    assertEquals("production", responseRef.get().getCommand().getRealmSlug());
    assertEquals(17L, responseRef.get().getCommand().getPointerVersion());
    assertEquals("plugin-1", responseRef.get().getCommand().getPluginId());
    assertEquals("plugin-v1", responseRef.get().getCommand().getPluginVersionId());
    assertEquals(
        "plugin-v1", responseRef.get().getCommand().getPluginPublication().getPluginVersionId());
    assertEquals(51L, responseRef.get().getCommand().getPluginPublication().getPublicationId());
    assertEquals(
        "patch-1", responseRef.get().getCommand().getPublication().getScriptPatchVersion());
    assertEquals(17L, responseRef.get().getCommand().getPublication().getVersionId());
    assertEquals("GAMEPLAY_EVENT", responseRef.get().getCommand().getOriginSourceKind());
    assertEquals("WORK_ITEM_PERSISTED", responseRef.get().getCommand().getOriginSourceState());
    assertEquals(41L, responseRef.get().getCommand().getOriginSourceOrdinal());
    assertEquals(14L, responseRef.get().getCommand().getOriginSourceDueTickId());
    assertEquals("GAMEPLAY_COMMAND", responseRef.get().getCommand().getQueueSourceKind());
    assertEquals("REDIS_PENDING_CLAIMED", responseRef.get().getCommand().getQueueSourceState());
    assertEquals(33L, responseRef.get().getCommand().getQueueSourceOrdinal());
    assertEquals(14L, responseRef.get().getCommand().getQueueSourceDueTickId());
    assertEquals("coord-1", responseRef.get().getCommand().getRemoteCoordinatorId());
    assertEquals("followup-1", responseRef.get().getCommand().getRemoteFollowupId());
    assertEquals("PENDING_REMOTE", responseRef.get().getCommand().getRemoteState());
    assertEquals("7", responseRef.get().getCommand().getRemoteOriginGameInstanceId());
    assertEquals("origin-region", responseRef.get().getCommand().getRemoteOriginRegionId());
    assertEquals(4L, responseRef.get().getCommand().getRemoteOriginRegionEpoch());
    assertEquals("9", responseRef.get().getCommand().getRemoteTargetGameInstanceId());
    assertEquals("target-region", responseRef.get().getCommand().getRemoteTargetRegionId());
    assertEquals(8L, responseRef.get().getCommand().getRemoteTargetRegionEpoch());
    assertEquals("ABANDONED", responseRef.get().getCommand().getRemoteResultOutcome());
    assertEquals(
        "{\"failureCode\":\"REMOTE_FOLLOWUP_KIND_UNSUPPORTED\",\"message\":\"Target region rejected the remote payload kind\"}",
        responseRef.get().getCommand().getRemoteResultPayloadJson());
    assertEquals(
        "REMOTE_FOLLOWUP_KIND_UNSUPPORTED",
        responseRef.get().getCommand().getRemoteResultErrorCode());
    assertEquals(
        "Target region rejected the remote payload kind",
        responseRef.get().getCommand().getRemoteResultMessage());
    assertEquals(
        Instant.parse("2026-04-15T00:00:03Z").toEpochMilli(),
        responseRef.get().getCommand().getRemoteResultObservedAtMs());
    assertEquals(
        Instant.parse("2026-04-15T00:00:01Z").toEpochMilli(),
        responseRef.get().getCommand().getStagedAtMs());
  }

  @Test
  void getGameplayCommandStatusCanResolveAutomationDispatchIdentity() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("auto-123");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(0L);
    command.setCommandName("SAY");
    command.setSanitizedCommandText("say hello");
    command.setRequiresSoloTick(false);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    command.setLastAttemptAt(Instant.parse("2026-04-15T00:00:01Z"));
    command.setAttemptCount(1);
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId("dispatch-1");
    command.setAutomationWorkItemId("work-1");
    command.setRegionId("region-1");
    command.setRegionEpoch(12L);
    command.setEnqueueSeq(44L);
    command.setPlayableStateScope("ISOLATED");
    command.setWorldSlug("ops");
    command.setRealmSlug("preview");
    command.setPointerVersion(29L);
    command.setScriptPatchVersion("patch-2");
    command.setPluginId("plugin-2");
    command.setPluginVersionId("plugin-v2");
    command.setOriginSourceKind("SCHEDULE_TIMER");
    command.setOriginSourceState("SCHEDULE_DUE_CLAIMED");
    command.setOriginSourceOrdinal(5000L);
    command.setOriginSourceDueAtMs(5000L);
    command.setQueueSourceKind("GAMEPLAY_RETRY");
    command.setQueueSourceState("REDIS_RETRY_CLAIMED");
    command.setQueueSourceOrdinal(44L);
    command.setQueueSourceDueTickId(21L);
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    RemoteFollowupResultRepository remoteFollowupResultRepository =
        Mockito.mock(RemoteFollowupResultRepository.class);
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.of(command));
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setTenantId(1L);
    coordinator.setCommandId("auto-123");
    coordinator.setCoordinatorId("coord-2");
    coordinator.setFollowupId("followup-2");
    coordinator.setState("REMOTE_APPLIED");
    Mockito.when(remoteCommandCoordinatorRepository.findByTenantIdAndCommandId(1L, "auto-123"))
        .thenReturn(Optional.of(coordinator));
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setTenantId(1L);
    result.setCoordinatorId("coord-2");
    result.setOutcome("APPLIED");
    result.setResultPayloadJson("{\"commandId\":\"auto-1\",\"applied\":true}");
    result.setObservedAt(Instant.parse("2026-04-15T00:00:05Z"));
    Mockito.when(
            remoteFollowupResultRepository.findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
                1L, "coord-2"))
        .thenReturn(List.of(result));
    GameplayCommand remoteTargetCommand = new GameplayCommand();
    remoteTargetCommand.setCommandId("rfcmd-followup-2");
    remoteTargetCommand.setTenantId(1L);
    remoteTargetCommand.setRemoteFollowupId("followup-2");
    Mockito.when(commandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-2"))
        .thenReturn(Optional.of(remoteTargetCommand));
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    Mockito.when(gameDesignClient.getPublishedScriptPatchVersion(1L, "patch-2"))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setScriptPatchVersion("patch-2")
                        .setVersionId(23L)
                        .setBaseVersionId(9L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(250L)
                        .build())
                .build());
    Mockito.when(gameDesignClient.getPublishedPluginVersion(1L, "plugin-2", "plugin-v2"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setPluginId("plugin-2")
                        .setPluginVersionId("plugin-v2")
                        .setPublicationId(62L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setStatusReason("signature_verified")
                        .setLastChangedAtMs(275L)
                        .build())
                .build());
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            commandRepository,
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(RemoteFollowupRepository.class),
            remoteCommandCoordinatorRepository,
            remoteFollowupResultRepository,
            null,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            gameDesignClient,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry(),
            new GameSessionProperties());

    AtomicReference<GetGameplayCommandStatusResponse> responseRef = new AtomicReference<>();
    service.getGameplayCommandStatus(
        GetGameplayCommandStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setAutomationDispatchId("dispatch-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameplayCommandStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("auto-123", responseRef.get().getCommand().getCommandId());
    assertEquals("dispatch-1", responseRef.get().getCommand().getAutomationDispatchId());
    assertEquals("work-1", responseRef.get().getCommand().getAutomationWorkItemId());
    assertEquals(44L, responseRef.get().getCommand().getEnqueueSeq());
    assertEquals("coord-2", responseRef.get().getCommand().getRemoteCoordinatorId());
    assertEquals("followup-2", responseRef.get().getCommand().getRemoteFollowupId());
    assertEquals("REMOTE_APPLIED", responseRef.get().getCommand().getRemoteState());
    assertEquals("APPLIED", responseRef.get().getCommand().getRemoteResultOutcome());
    assertEquals(
        "{\"commandId\":\"auto-1\",\"applied\":true}",
        responseRef.get().getCommand().getRemoteResultPayloadJson());
    assertEquals("rfcmd-followup-2", responseRef.get().getCommand().getRemoteResultCommandId());
    assertEquals(
        Instant.parse("2026-04-15T00:00:05Z").toEpochMilli(),
        responseRef.get().getCommand().getRemoteResultObservedAtMs());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
        responseRef.get().getCommand().getPlayableStateScope());
    assertEquals("ops", responseRef.get().getCommand().getWorldSlug());
    assertEquals("preview", responseRef.get().getCommand().getRealmSlug());
    assertEquals(29L, responseRef.get().getCommand().getPointerVersion());
    assertEquals("plugin-2", responseRef.get().getCommand().getPluginId());
    assertEquals("plugin-v2", responseRef.get().getCommand().getPluginVersionId());
    assertEquals(
        "plugin-v2", responseRef.get().getCommand().getPluginPublication().getPluginVersionId());
    assertEquals(62L, responseRef.get().getCommand().getPluginPublication().getPublicationId());
    assertEquals(
        "patch-2", responseRef.get().getCommand().getPublication().getScriptPatchVersion());
    assertEquals(23L, responseRef.get().getCommand().getPublication().getVersionId());
    assertEquals("SCHEDULE_TIMER", responseRef.get().getCommand().getOriginSourceKind());
    assertEquals("SCHEDULE_DUE_CLAIMED", responseRef.get().getCommand().getOriginSourceState());
    assertEquals(5000L, responseRef.get().getCommand().getOriginSourceOrdinal());
    assertEquals(5000L, responseRef.get().getCommand().getOriginSourceDueAtMs());
    assertEquals("GAMEPLAY_RETRY", responseRef.get().getCommand().getQueueSourceKind());
    assertEquals("REDIS_RETRY_CLAIMED", responseRef.get().getCommand().getQueueSourceState());
    assertEquals(44L, responseRef.get().getCommand().getQueueSourceOrdinal());
    assertEquals(21L, responseRef.get().getCommand().getQueueSourceDueTickId());
  }

  @Test
  void getGameplayCommandStatusUsesPersistedRemoteFollowupIdentityForTargetCommand() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("rfcmd-followup-2");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(0L);
    command.setCharacterId(321L);
    command.setCommandName("LOOK");
    command.setSanitizedCommandText("LOOK");
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    command.setLastAttemptAt(Instant.parse("2026-04-15T00:00:01Z"));
    command.setAttemptCount(1);
    command.setSourceType("REMOTE_FOLLOWUP");
    command.setTargetEntityId("321");
    command.setRegionId("region-1");
    command.setRegionEpoch(12L);
    command.setRemoteCoordinatorId("coord-2");
    command.setRemoteFollowupId("followup-2");
    command.setScriptPatchVersion("patch-2");
    command.setExecutionOutcome("APPLIED");
    command.setGameplayResult("APPLIED");
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    RemoteFollowupResultRepository remoteFollowupResultRepository =
        Mockito.mock(RemoteFollowupResultRepository.class);
    Mockito.when(commandRepository.findByCommandId("rfcmd-followup-2"))
        .thenReturn(Optional.of(command));
    GameplayCommand targetCommand = new GameplayCommand();
    targetCommand.setCommandId("target-cmd-2");
    targetCommand.setTenantId(1L);
    targetCommand.setGameInstanceId(9L);
    targetCommand.setExecutionOutcome("APPLIED");
    targetCommand.setGameplayResult("PARTIAL");
    Mockito.when(commandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-2"))
        .thenReturn(Optional.of(targetCommand));
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setTenantId(1L);
    coordinator.setCommandId("cmd-origin-2");
    coordinator.setCoordinatorId("coord-2");
    coordinator.setFollowupId("followup-2");
    coordinator.setOriginRegionId("region-origin");
    coordinator.setOriginRegionEpoch(7L);
    coordinator.setTargetRegionId("region-target");
    coordinator.setTargetRegionEpoch(12L);
    coordinator.setOriginDeadlineRegionEpoch(8L);
    coordinator.setOriginDeadlineTickId(144L);
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    coordinator.setState("REMOTE_APPLIED");
    Mockito.when(remoteCommandCoordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-2"))
        .thenReturn(Optional.of(coordinator));
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setTenantId(1L);
    result.setCoordinatorId("coord-2");
    result.setOutcome("APPLIED");
    result.setResultPayloadJson("{\"commandId\":\"rfcmd-followup-2\",\"applied\":true}");
    result.setObservedAt(Instant.parse("2026-04-15T00:00:05Z"));
    Mockito.when(
            remoteFollowupResultRepository.findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
                1L, "coord-2"))
        .thenReturn(List.of(result));
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    Mockito.when(gameDesignClient.getPublishedScriptPatchVersion(1L, "patch-2"))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setScriptPatchVersion("patch-2")
                        .setVersionId(23L)
                        .setBaseVersionId(9L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(250L)
                        .build())
                .build());
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            commandRepository,
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(RemoteFollowupRepository.class),
            remoteCommandCoordinatorRepository,
            remoteFollowupResultRepository,
            null,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            gameDesignClient,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry(),
            new GameSessionProperties());

    AtomicReference<GetGameplayCommandStatusResponse> responseRef = new AtomicReference<>();
    service.getGameplayCommandStatus(
        GetGameplayCommandStatusRequest.newBuilder().setCommandId("rfcmd-followup-2").build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameplayCommandStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("rfcmd-followup-2", responseRef.get().getCommand().getCommandId());
    assertEquals("coord-2", responseRef.get().getCommand().getRemoteCoordinatorId());
    assertEquals("followup-2", responseRef.get().getCommand().getRemoteFollowupId());
    assertEquals("REMOTE_APPLIED", responseRef.get().getCommand().getRemoteState());
    assertEquals("region-origin", responseRef.get().getCommand().getRemoteOriginRegionId());
    assertEquals(7L, responseRef.get().getCommand().getRemoteOriginRegionEpoch());
    assertEquals("region-target", responseRef.get().getCommand().getRemoteTargetRegionId());
    assertEquals(12L, responseRef.get().getCommand().getRemoteTargetRegionEpoch());
    assertEquals(8L, responseRef.get().getCommand().getRemoteOriginDeadlineRegionEpoch());
    assertEquals(144L, responseRef.get().getCommand().getRemoteOriginDeadlineTickId());
    assertEquals(
        "late_result_safe_to_ignore", responseRef.get().getCommand().getRemoteLateResultPolicy());
    assertEquals("APPLIED", responseRef.get().getCommand().getRemoteResultOutcome());
    assertEquals(
        "{\"commandId\":\"rfcmd-followup-2\",\"applied\":true}",
        responseRef.get().getCommand().getRemoteResultPayloadJson());
    assertEquals("target-cmd-2", responseRef.get().getCommand().getRemoteResultCommandId());
    assertEquals(
        "APPLIED", responseRef.get().getCommand().getRemoteTargetCommandExecutionOutcome());
    assertEquals("PARTIAL", responseRef.get().getCommand().getRemoteTargetCommandGameplayResult());
  }

  @Test
  void enqueueAutomationCommandPersistsDispatchAndStagesTickCommand() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatusRepository runtimeRepository = runtimeRepository(runtimeStatus(false, 12L));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(true, responseRef.get().getAccepted());
    assertEquals("ENQUEUED", responseRef.get().getAdmissionOutcome());
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    Mockito.verify(commandRepository, Mockito.times(2)).save(commandCaptor.capture());
    GameplayCommand staged = commandCaptor.getAllValues().get(1);
    assertEquals(responseRef.get().getCommandId(), staged.getCommandId());
    assertEquals("AUTOMATION", staged.getSourceType());
    assertEquals("dispatch-1", staged.getAutomationDispatchId());
    assertEquals("work-1", staged.getAutomationWorkItemId());
    assertEquals("script-1", staged.getScriptId());
    assertEquals("patch-1", staged.getScriptPatchVersion());
    assertEquals("plugin-1", staged.getPluginId());
    assertEquals("plugin-v1", staged.getPluginVersionId());
    assertEquals("SHARED", staged.getPlayableStateScope());
    assertEquals("demo", staged.getWorldSlug());
    assertEquals("production", staged.getRealmSlug());
    assertEquals(17L, staged.getPointerVersion());
    assertEquals("SCHEDULE_TIMER", staged.getOriginSourceKind());
    assertEquals("SCHEDULE_DUE_CLAIMED", staged.getOriginSourceState());
    assertEquals(5000L, staged.getOriginSourceOrdinal());
    assertEquals(5000L, staged.getOriginSourceDueAtMs());
    assertEquals("entity-1", staged.getTargetEntityId());
    assertEquals("region-1", staged.getRegionId());
    assertEquals(12L, staged.getRegionEpoch());
    assertEquals(34L, staged.getDueTickId());
    assertEquals(1L, staged.getEnqueueSeq());
    assertEquals("STAGED", staged.getExecutionOutcome());
    Mockito.verify(tickService)
        .enqueueCommand(1L, 7L, responseRef.get().getCommandId(), "say hello", false);
    Mockito.verify(tickService).processTick(1L, 7L);
  }

  @Test
  void enqueueAutomationCommandAllowsImmediateHandoffWithoutDueTick() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatusRepository runtimeRepository = runtimeRepository(runtimeStatus(false, 12L));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest().toBuilder().clearDueTickId().build(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(true, responseRef.get().getAccepted());
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    Mockito.verify(commandRepository, Mockito.times(2)).save(commandCaptor.capture());
    GameplayCommand staged = commandCaptor.getAllValues().get(1);
    assertEquals(null, staged.getDueTickId());
  }

  @Test
  void enqueueAutomationCommandDerivesCharacterIdFromNumericTargetEntity() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatusRepository runtimeRepository = runtimeRepository(runtimeStatus(false, 12L));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest().toBuilder().setTargetEntityId("44").build(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(true, responseRef.get().getAccepted());
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    Mockito.verify(commandRepository, Mockito.times(2)).save(commandCaptor.capture());
    GameplayCommand staged = commandCaptor.getAllValues().get(1);
    assertEquals(44L, staged.getCharacterId());
  }

  @Test
  void enqueueAutomationCommandRejectsStaleRuntimeEpochBeforeTickQueue() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatusRepository runtimeRepository = runtimeRepository(runtimeStatus(false, 13L));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(false, responseRef.get().getAccepted());
    assertEquals("STALE_TIMELINE", responseRef.get().getAdmissionOutcome());
    assertEquals("stale_region_epoch", responseRef.get().getError().getCode());
    Mockito.verify(commandRepository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(tickService);
  }

  @Test
  void enqueueAutomationCommandRejectsMismatchedRuntimeRegionBeforeTickQueue() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatus status = runtimeStatus(false, 12L);
    status.setRegionId("region-2");
    RuntimeRegionStatusRepository runtimeRepository = runtimeRepository(status);
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(false, responseRef.get().getAccepted());
    assertEquals("STALE_TIMELINE", responseRef.get().getAdmissionOutcome());
    assertEquals("stale_region_id", responseRef.get().getError().getCode());
    Mockito.verify(commandRepository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(tickService);
  }

  @Test
  void enqueueAutomationCommandRejectsPausedRuntimeOwnershipBeforeTickQueue() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatusRepository runtimeRepository = runtimeRepository(runtimeStatus(true, 12L));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(false, responseRef.get().getAccepted());
    assertEquals("RUNTIME_PAUSED", responseRef.get().getAdmissionOutcome());
    assertEquals("runtime_paused", responseRef.get().getError().getCode());
    Mockito.verify(commandRepository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(tickService);
  }

  @Test
  void enqueueAutomationCommandPrefersRegionScopedOwnershipAuthority() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatus staleInstanceStatus = runtimeStatus(false, 13L);
    staleInstanceStatus.setRegionId("region-2");
    RuntimeRegionStatus regionScopedStatus = runtimeStatus(false, 12L);
    regionScopedStatus.setRegionId("region-1");
    RuntimeRegionStatusRepository runtimeRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    Mockito.when(runtimeRepository.findByTenantIdAndRegionId(1L, "region-1"))
        .thenReturn(Optional.of(regionScopedStatus));
    Mockito.when(runtimeRepository.findByTenantIdAndGameInstanceId(1L, 7L))
        .thenReturn(Optional.of(staleInstanceStatus));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            runtimeRepository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(true, responseRef.get().getAccepted());
    assertEquals("ENQUEUED", responseRef.get().getAdmissionOutcome());
    Mockito.verify(runtimeRepository).findByTenantIdAndRegionId(1L, "region-1");
    Mockito.verify(runtimeRepository, Mockito.never()).findByTenantIdAndGameInstanceId(1L, 7L);
    Mockito.verify(tickService)
        .enqueueCommand(1L, 7L, responseRef.get().getCommandId(), "say hello", false);
  }

  @Test
  void purgeQueuedTickCommandsForScriptPatchDelegatesToTickService() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    TickService tickService = Mockito.mock(TickService.class);
    Mockito.when(
            tickService.purgeQueuedAutomationCommandsForScriptPatch(
                1L, 7L, "region-1", "patch-1", "rollback"))
        .thenReturn(3L);
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<PurgeQueuedTickCommandsForScriptPatchResponse> responseRef =
        new AtomicReference<>();
    service.purgeQueuedTickCommandsForScriptPatch(
        PurgeQueuedTickCommandsForScriptPatchRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setRegionId("region-1")
            .setScriptPatchVersion("patch-1")
            .setControlPlaneRequestId("req-1")
            .setActorPrincipal("admin")
            .setReason("rollback")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(PurgeQueuedTickCommandsForScriptPatchResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(3L, responseRef.get().getPurgedCount());
  }

  @Test
  void purgeQueuedTickCommandsForPluginVersionDelegatesToTickService() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    TickService tickService = Mockito.mock(TickService.class);
    Mockito.when(
            tickService.purgeQueuedAutomationCommandsForPluginVersion(
                1L, 7L, "region-1", "plugin-1", "plugin-v1", "rollback"))
        .thenReturn(2L);
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<PurgeQueuedTickCommandsForPluginVersionResponse> responseRef =
        new AtomicReference<>();
    service.purgeQueuedTickCommandsForPluginVersion(
        PurgeQueuedTickCommandsForPluginVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setRegionId("region-1")
            .setPluginId("plugin-1")
            .setPluginVersionId("plugin-v1")
            .setControlPlaneRequestId("req-1")
            .setActorPrincipal("admin")
            .setReason("rollback")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(PurgeQueuedTickCommandsForPluginVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(2L, responseRef.get().getPurgedCount());
  }

  @Test
  void enqueueAutomationCommandReturnsDuplicateNoopForExistingDispatch() {
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameplayCommand existing = new GameplayCommand();
    existing.setCommandId("auto-existing");
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    Mockito.when(
            commandRepository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                    1L, 7L, "region-1", 12L, "dispatch-1"))
        .thenReturn(Optional.of(existing));
    TickService tickService = Mockito.mock(TickService.class);
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            gameInstanceRepository,
            commandRepository,
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            tickService,
            new SimpleMeterRegistry());

    AtomicReference<EnqueueAutomationCommandIfAbsentResponse> responseRef = new AtomicReference<>();
    service.enqueueAutomationCommandIfAbsent(
        automationRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(EnqueueAutomationCommandIfAbsentResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(true, responseRef.get().getAccepted());
    assertEquals("DUPLICATE_NOOP", responseRef.get().getAdmissionOutcome());
    assertEquals("auto-existing", responseRef.get().getCommandId());
    Mockito.verify(commandRepository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(tickService);
  }

  @Test
  void getRuntimeOwnershipStatusReturnsDurableOwnerRecordForAdminCaller() {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(1L);
    status.setGameInstanceId(7L);
    status.setRegionId("region-7");
    status.setRegionEpoch(3L);
    status.setExecutorFence("fence-3");
    status.setOwnerService("game-session-service");
    status.setOwnerInstanceId("gs-1");
    status.setPaused(false);
    status.setLastCommittedTickBatchId("tb-9");
    status.setLastCommittedTickId(14L);
    status.setUpdatedAt(Instant.parse("2026-04-20T00:00:00Z"));
    RuntimeRegionStatusRepository repository = Mockito.mock(RuntimeRegionStatusRepository.class);
    GameplayCommandRepository gameplayCommandRepository =
        Mockito.mock(GameplayCommandRepository.class);
    RemoteFollowupRepository remoteFollowupRepository =
        Mockito.mock(RemoteFollowupRepository.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId(1L, 7L))
        .thenReturn(Optional.of(status));
    Mockito.when(
            gameplayCommandRepository
                .countByTenantIdAndGameInstanceIdAndCompletedAtIsNullAndExecutionOutcomeIn(
                    Mockito.eq(1L), Mockito.eq(7L), Mockito.anyCollection()))
        .thenReturn(3L);
    Mockito.when(
            remoteFollowupRepository
                .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                    1L, "region-7", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 15L))
        .thenReturn(2L);
    RemoteFollowup oldestFollowup = new RemoteFollowup();
    oldestFollowup.setDueTickId(13L);
    Mockito.when(
            remoteFollowupRepository
                .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                    1L, "region-7", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 15L))
        .thenReturn(Optional.of(oldestFollowup));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            gameplayCommandRepository,
            repository,
            remoteFollowupRepository,
            Mockito.mock(RemoteCommandCoordinatorRepository.class),
            Mockito.mock(RemoteFollowupResultRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry(),
            new GameSessionProperties());

    AtomicReference<GetRuntimeOwnershipStatusResponse> responseRef = new AtomicReference<>();
    service.getRuntimeOwnershipStatus(
        GetRuntimeOwnershipStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetRuntimeOwnershipStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(3L, responseRef.get().getOwnership().getRegionEpoch());
    assertEquals("region-7", responseRef.get().getOwnership().getRegionId());
    assertEquals("fence-3", responseRef.get().getOwnership().getExecutorFence());
    assertEquals("tb-9", responseRef.get().getOwnership().getLastCommittedTickBatchId());
    assertEquals(14L, responseRef.get().getOwnership().getLastCommittedTickId());
    assertEquals(3L, responseRef.get().getOwnership().getPendingGameplayCommandCount());
    assertEquals(2L, responseRef.get().getOwnership().getDueRemoteFollowupCount());
    assertEquals(13L, responseRef.get().getOwnership().getOldestDueRemoteFollowupTickId());
    assertEquals(2000L, responseRef.get().getOwnership().getRemoteFollowupDrainLagMs());
  }

  @Test
  void getRuntimeOwnershipStatusCanResolveByRegionId() {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(1L);
    status.setGameInstanceId(7L);
    status.setRegionId("region-7");
    status.setRegionEpoch(3L);
    status.setExecutorFence("fence-3");
    status.setOwnerService("game-session-service");
    status.setOwnerInstanceId("gs-1");
    status.setPaused(false);
    status.setLastCommittedTickBatchId("tb-9");
    status.setLastCommittedTickId(14L);
    status.setUpdatedAt(Instant.parse("2026-04-20T00:00:00Z"));
    RuntimeRegionStatusRepository repository = Mockito.mock(RuntimeRegionStatusRepository.class);
    GameplayCommandRepository gameplayCommandRepository =
        Mockito.mock(GameplayCommandRepository.class);
    RemoteFollowupRepository remoteFollowupRepository =
        Mockito.mock(RemoteFollowupRepository.class);
    Mockito.when(repository.findByTenantIdAndRegionId(1L, "region-7"))
        .thenReturn(Optional.of(status));
    Mockito.when(
            gameplayCommandRepository
                .countByTenantIdAndGameInstanceIdAndCompletedAtIsNullAndExecutionOutcomeIn(
                    Mockito.eq(1L), Mockito.eq(7L), Mockito.anyCollection()))
        .thenReturn(0L);
    Mockito.when(
            remoteFollowupRepository
                .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                    1L, "region-7", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 15L))
        .thenReturn(0L);
    Mockito.when(
            remoteFollowupRepository
                .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                    1L, "region-7", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 15L))
        .thenReturn(Optional.empty());
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            gameplayCommandRepository,
            repository,
            remoteFollowupRepository,
            Mockito.mock(RemoteCommandCoordinatorRepository.class),
            Mockito.mock(RemoteFollowupResultRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            Mockito.mock(VersionUpgradePreparationService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry(),
            new GameSessionProperties());

    AtomicReference<GetRuntimeOwnershipStatusResponse> responseRef = new AtomicReference<>();
    service.getRuntimeOwnershipStatus(
        GetRuntimeOwnershipStatusRequest.newBuilder()
            .setTenantId("1")
            .setRegionId("region-7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetRuntimeOwnershipStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("region-7", responseRef.get().getOwnership().getRegionId());
    assertEquals(0L, responseRef.get().getOwnership().getPendingGameplayCommandCount());
    assertEquals(0L, responseRef.get().getOwnership().getDueRemoteFollowupCount());
    assertEquals(0L, responseRef.get().getOwnership().getOldestDueRemoteFollowupTickId());
    assertEquals(0L, responseRef.get().getOwnership().getRemoteFollowupDrainLagMs());
    Mockito.verify(repository).findByTenantIdAndRegionId(1L, "region-7");
    Mockito.verify(repository, Mockito.never())
        .findByTenantIdAndGameInstanceId(Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void getRemoteCommandCoordinatorReturnsCoordinatorRowForAdminCaller() {
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setCommandId("cmd-1");
    coordinator.setFollowupId("rf-1");
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(3L);
    coordinator.setTargetGameInstanceId(9L);
    coordinator.setTargetRegionId("region-b");
    coordinator.setTargetRegionEpoch(4L);
    coordinator.setTargetDueTickId(55L);
    coordinator.setOriginDeadlineRegionEpoch(3L);
    coordinator.setOriginDeadlineTickId(88L);
    coordinator.setState("PENDING_REMOTE");
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    coordinator.setPlayableStateScope("SHARED");
    coordinator.setWorldSlug("demo");
    coordinator.setRealmSlug("production");
    coordinator.setPointerVersion(17L);
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    coordinator.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("rf-1");
    followup.setTenantId(1L);
    followup.setStatus("CLAIMED");
    followup.setClaimedTickBatchId("tb-1");
    followup.setClaimOrdinal(3L);
    followup.setTargetEntityId("entity-9");
    followup.setEffectKey("effect-9");
    followup.setPayloadJson("{\"kind\":\"payload_kind\",\"command\":\"payload LOOK\"}");
    followup.setPayloadKind("enqueue_automation_command");
    followup.setRequestedCommand("LOOK");
    followup.setRequiresSoloTick(true);
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setOriginSourceState("TARGET_REGION_EXECUTED");
    followup.setOriginSourceOrdinal(44L);
    followup.setOriginSourceDueTickId(55L);
    followup.setOriginSourceDueAtMs(1700L);
    followup.setFailureCode("REMOTE_REJECTED");
    followup.setFailureMessage("Target runtime rejected the remote followup");
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setCoordinatorId("coord-1");
    result.setOutcome("APPLIED");
    result.setResultPayloadJson(
        "{\"commandId\":\"payload-cmd\",\"errorCode\":\"payload-error\",\"message\":\"payload message\"}");
    result.setResultCommandId("auto-1");
    result.setResultErrorCode("RATE_LIMIT");
    result.setResultMessage("Target region rejected the remote gameplay command");
    result.setObservedAt(Instant.parse("2026-05-01T00:00:05Z"));
    RemoteCommandCoordinatorRepository repository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    RemoteFollowupRepository remoteFollowupRepository =
        Mockito.mock(RemoteFollowupRepository.class);
    RemoteFollowupResultRepository remoteFollowupResultRepository =
        Mockito.mock(RemoteFollowupResultRepository.class);
    GameplayCommandRepository gameplayCommandRepository =
        Mockito.mock(GameplayCommandRepository.class);
    Mockito.when(repository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    Mockito.when(remoteFollowupRepository.findByTenantIdAndFollowupId(1L, "rf-1"))
        .thenReturn(Optional.of(followup));
    Mockito.when(
            remoteFollowupResultRepository.findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
                1L, "coord-1"))
        .thenReturn(List.of(result));
    GameplayCommand targetCommand = new GameplayCommand();
    targetCommand.setCommandId("rfcmd-rf-1");
    targetCommand.setExecutionOutcome("STAGED");
    targetCommand.setGameplayResult("PENDING");
    Mockito.when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "rf-1"))
        .thenReturn(Optional.of(targetCommand));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(
            remoteFollowupRepository,
            repository,
            remoteFollowupResultRepository,
            gameplayCommandRepository,
            null,
            gameDesignClient());

    AtomicReference<GetRemoteCommandCoordinatorResponse> responseRef = new AtomicReference<>();
    service.getRemoteCommandCoordinator(
        GetRemoteCommandCoordinatorRequest.newBuilder()
            .setTenantId("1")
            .setCoordinatorId("coord-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetRemoteCommandCoordinatorResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("coord-1", responseRef.get().getCoordinator().getCoordinatorId());
    assertEquals("rf-1", responseRef.get().getCoordinator().getFollowupId());
    assertEquals("region-a", responseRef.get().getCoordinator().getOriginRegionId());
    assertEquals("region-b", responseRef.get().getCoordinator().getTargetRegionId());
    assertEquals(55L, responseRef.get().getCoordinator().getTargetDueTickId());
    assertEquals("PENDING_REMOTE", responseRef.get().getCoordinator().getState());
    assertEquals("dispatch-1", responseRef.get().getCoordinator().getAutomationDispatchId());
    assertEquals("work-1", responseRef.get().getCoordinator().getAutomationWorkItemId());
    assertEquals("script-1", responseRef.get().getCoordinator().getScriptId());
    assertEquals("patch-1", responseRef.get().getCoordinator().getScriptPatchVersion());
    assertEquals("plugin-1", responseRef.get().getCoordinator().getPluginId());
    assertEquals("plugin-v1", responseRef.get().getCoordinator().getPluginVersionId());
    assertEquals(17L, responseRef.get().getCoordinator().getPublication().getVersionId());
    assertEquals(
        "plugin-v1",
        responseRef.get().getCoordinator().getPluginPublication().getPluginVersionId());
    assertEquals(31L, responseRef.get().getCoordinator().getPluginPublication().getPublicationId());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        responseRef.get().getCoordinator().getPlayableStateScope());
    assertEquals("demo", responseRef.get().getCoordinator().getWorldSlug());
    assertEquals("production", responseRef.get().getCoordinator().getRealmSlug());
    assertEquals(17L, responseRef.get().getCoordinator().getPointerVersion());
    assertEquals("CLAIMED", responseRef.get().getCoordinator().getFollowupStatus());
    assertEquals("tb-1", responseRef.get().getCoordinator().getFollowupClaimedTickBatchId());
    assertEquals(3L, responseRef.get().getCoordinator().getFollowupClaimOrdinal());
    assertEquals("entity-9", responseRef.get().getCoordinator().getTargetEntityId());
    assertEquals("effect-9", responseRef.get().getCoordinator().getFollowupEffectKey());
    assertEquals("REMOTE_REJECTED", responseRef.get().getCoordinator().getFollowupFailureCode());
    assertEquals(
        "Target runtime rejected the remote followup",
        responseRef.get().getCoordinator().getFollowupFailureMessage());
    assertEquals(
        "REMOTE_FOLLOWUP", responseRef.get().getCoordinator().getFollowupOriginSourceKind());
    assertEquals(
        "TARGET_REGION_EXECUTED",
        responseRef.get().getCoordinator().getFollowupOriginSourceState());
    assertEquals(44L, responseRef.get().getCoordinator().getFollowupOriginSourceOrdinal());
    assertEquals(55L, responseRef.get().getCoordinator().getFollowupOriginSourceDueTickId());
    assertEquals(1700L, responseRef.get().getCoordinator().getFollowupOriginSourceDueAtMs());
    assertEquals(
        "enqueue_automation_command", responseRef.get().getCoordinator().getFollowupPayloadKind());
    assertEquals("LOOK", responseRef.get().getCoordinator().getFollowupRequestedCommand());
    assertEquals(true, responseRef.get().getCoordinator().getFollowupRequiresSoloTick());
    assertEquals("APPLIED", responseRef.get().getCoordinator().getLatestResultOutcome());
    assertEquals(
        "{\"commandId\":\"payload-cmd\",\"errorCode\":\"payload-error\",\"message\":\"payload message\"}",
        responseRef.get().getCoordinator().getLatestResultPayloadJson());
    assertEquals("rfcmd-rf-1", responseRef.get().getCoordinator().getLatestResultCommandId());
    assertEquals("RATE_LIMIT", responseRef.get().getCoordinator().getLatestResultErrorCode());
    assertEquals(
        "Target region rejected the remote gameplay command",
        responseRef.get().getCoordinator().getLatestResultMessage());
    assertEquals("rfcmd-rf-1", responseRef.get().getCoordinator().getTargetCommandId());
    assertEquals("STAGED", responseRef.get().getCoordinator().getTargetCommandExecutionOutcome());
    assertEquals("PENDING", responseRef.get().getCoordinator().getTargetCommandGameplayResult());
    assertEquals(
        Instant.parse("2026-05-01T00:00:05Z").toEpochMilli(),
        responseRef.get().getCoordinator().getLatestResultObservedAtMs());
  }

  @Test
  void listRemoteCommandCoordinatorsReturnsFilteredRowsForAdminCaller() {
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setCommandId("cmd-1");
    coordinator.setFollowupId("rf-1");
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(3L);
    coordinator.setTargetGameInstanceId(9L);
    coordinator.setTargetRegionId("region-b");
    coordinator.setTargetRegionEpoch(4L);
    coordinator.setTargetDueTickId(55L);
    coordinator.setOriginDeadlineRegionEpoch(3L);
    coordinator.setOriginDeadlineTickId(88L);
    coordinator.setState("PENDING_REMOTE");
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    coordinator.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
    RemoteFollowup followup = new RemoteFollowup();
    followup.setTenantId(1L);
    followup.setFollowupId("rf-1");
    followup.setStatus("SCHEDULED");
    followup.setTargetEntityId("entity-9");
    followup.setEffectKey("effect-9");
    followup.setPayloadKind("enqueue_automation_command");
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("evt-1");
    followup.setTriggerMode("TRIGGER_MODE_NORMAL");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:evt-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}");
    RemoteCommandCoordinatorRepository repository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    RemoteFollowupRepository remoteFollowupRepository =
        Mockito.mock(RemoteFollowupRepository.class);
    Mockito.when(
            repository.findForControlPlane(
                Mockito.anyLong(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyLong(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any()))
        .thenReturn(List.of(coordinator));
    Mockito.when(remoteFollowupRepository.findByTenantIdAndFollowupId(1L, "rf-1"))
        .thenReturn(Optional.of(followup));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(
            remoteFollowupRepository,
            repository,
            Mockito.mock(RemoteFollowupResultRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            null,
            gameDesignClient());

    AtomicReference<ListRemoteCommandCoordinatorsResponse> responseRef = new AtomicReference<>();
    service.listRemoteCommandCoordinators(
        ListRemoteCommandCoordinatorsRequest.newBuilder()
            .setTenantId("1")
            .setOriginGameInstanceId("7")
            .setOriginRegionId("region-a")
            .setOriginRegionEpoch(3L)
            .setTargetGameInstanceId("9")
            .setTargetRegionId("region-b")
            .setTargetRegionEpoch(4L)
            .setState("PENDING_REMOTE")
            .setFollowupId("rf-1")
            .setScriptId("script-1")
            .setPluginId("plugin-1")
            .setScriptPatchVersion("patch-1")
            .setPluginVersionId("plugin-v1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(17L)
            .setTargetEntityId("entity-9")
            .setEffectKey("effect-9")
            .setPayloadKind("enqueue_automation_command")
            .setOriginSourceKind("REMOTE_FOLLOWUP")
            .setAutomationWorkItemId("work-1")
            .setEventType("onEnterRegion")
            .setScriptEventId("evt-1")
            .setAutomationDispatchId("dispatch-1")
            .setCommandId("cmd-1")
            .setLimit(25)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListRemoteCommandCoordinatorsResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(1, responseRef.get().getCoordinatorsCount());
    assertEquals("coord-1", responseRef.get().getCoordinators(0).getCoordinatorId());
    assertEquals("rf-1", responseRef.get().getCoordinators(0).getFollowupId());
    assertEquals("7", responseRef.get().getCoordinators(0).getOriginGameInstanceId());
    assertEquals(3L, responseRef.get().getCoordinators(0).getOriginRegionEpoch());
    assertEquals("9", responseRef.get().getCoordinators(0).getTargetGameInstanceId());
    assertEquals(4L, responseRef.get().getCoordinators(0).getTargetRegionEpoch());
    assertEquals("dispatch-1", responseRef.get().getCoordinators(0).getAutomationDispatchId());
    assertEquals("script-1", responseRef.get().getCoordinators(0).getScriptId());
    assertEquals("plugin-1", responseRef.get().getCoordinators(0).getPluginId());
    assertEquals("patch-1", responseRef.get().getCoordinators(0).getScriptPatchVersion());
    assertEquals("entity-9", responseRef.get().getCoordinators(0).getTargetEntityId());
    assertEquals("effect-9", responseRef.get().getCoordinators(0).getFollowupEffectKey());
    assertEquals("onEnterRegion", responseRef.get().getCoordinators(0).getFollowupEventType());
    assertEquals("evt-1", responseRef.get().getCoordinators(0).getFollowupScriptEventId());
    assertEquals(17L, responseRef.get().getCoordinators(0).getPublication().getVersionId());
    Mockito.verify(repository)
        .findForControlPlane(
            1L,
            7L,
            "region-a",
            3L,
            9L,
            "region-b",
            4L,
            "PENDING_REMOTE",
            "rf-1",
            "script-1",
            "plugin-1",
            "patch-1",
            "plugin-v1",
            "SHARED",
            "demo",
            "production",
            17L,
            "entity-9",
            "effect-9",
            "enqueue_automation_command",
            "REMOTE_FOLLOWUP",
            "work-1",
            "onEnterRegion",
            "evt-1",
            "dispatch-1",
            "cmd-1",
            org.springframework.data.domain.PageRequest.of(0, 25));
  }

  @Test
  void listRemoteFollowupsReturnsRegionScopedRowsForAdminCaller() {
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("rf-1");
    followup.setTenantId(1L);
    followup.setOriginGameInstanceId(7L);
    followup.setOriginRegionId("region-a");
    followup.setOriginRegionEpoch(3L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(4L);
    followup.setDueTickId(55L);
    followup.setClaimOrdinal(2L);
    followup.setPlayableStateScope("ISOLATED");
    followup.setWorldSlug("ops");
    followup.setRealmSlug("preview");
    followup.setPointerVersion(29L);
    followup.setCommandId("cmd-1");
    followup.setAutomationDispatchId("dispatch-1");
    followup.setAutomationWorkItemId("work-1");
    followup.setScriptId("script-1");
    followup.setScriptPatchVersion("patch-1");
    followup.setPluginId("plugin-1");
    followup.setPluginVersionId("plugin-v1");
    followup.setEffectKey("damage:1");
    followup.setPayloadJson("{\"kind\":\"payload_kind\",\"command\":\"payload LOOK\"}");
    followup.setPayloadKind("enqueue_automation_command");
    followup.setRequestedCommand("LOOK");
    followup.setRequiresSoloTick(true);
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setOriginSourceState("TARGET_REGION_EXECUTED");
    followup.setOriginSourceOrdinal(44L);
    followup.setOriginSourceDueTickId(55L);
    followup.setOriginSourceDueAtMs(1700L);
    followup.setTargetEntityId("entity-9");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("evt-1");
    followup.setTriggerMode("TRIGGER_MODE_CATCH_UP");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:evt-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}");
    followup.setStatus("SCHEDULED");
    followup.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
    followup.setUpdatedAt(Instant.parse("2026-05-01T00:00:01Z"));
    RemoteFollowupRepository repository = Mockito.mock(RemoteFollowupRepository.class);
    RemoteCommandCoordinatorRepository coordinatorRepository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    GameplayCommandRepository gameplayCommandRepository =
        Mockito.mock(GameplayCommandRepository.class);
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("rf-1");
    coordinator.setOriginDeadlineRegionEpoch(3L);
    coordinator.setOriginDeadlineTickId(88L);
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    Mockito.when(
            repository.findForControlPlane(
                1L,
                "region-b",
                "SCHEDULED",
                7L,
                "region-a",
                3L,
                9L,
                4L,
                "rf-1",
                "script-1",
                "plugin-1",
                "patch-1",
                "plugin-v1",
                "ISOLATED",
                "ops",
                "preview",
                29L,
                "enqueue_automation_command",
                "REMOTE_FOLLOWUP",
                "work-1",
                "entity-9",
                "damage:1",
                "",
                true,
                "onEnterRegion",
                "evt-1",
                "dispatch-1",
                "cmd-1",
                org.springframework.data.domain.PageRequest.of(0, 25)))
        .thenReturn(List.of(followup));
    Mockito.when(coordinatorRepository.findByTenantIdAndFollowupId(1L, "rf-1"))
        .thenReturn(Optional.of(coordinator));
    GameplayCommand targetCommand = new GameplayCommand();
    targetCommand.setCommandId("rfcmd-rf-1");
    targetCommand.setExecutionOutcome("APPLIED");
    targetCommand.setGameplayResult("SUCCESS");
    Mockito.when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "rf-1"))
        .thenReturn(Optional.of(targetCommand));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(
            repository,
            coordinatorRepository,
            null,
            gameplayCommandRepository,
            null,
            gameDesignClient());

    AtomicReference<ListRemoteFollowupsResponse> responseRef = new AtomicReference<>();
    service.listRemoteFollowups(
        ListRemoteFollowupsRequest.newBuilder()
            .setTenantId("1")
            .setTargetRegionId("region-b")
            .setStatus("SCHEDULED")
            .setOriginGameInstanceId("7")
            .setOriginRegionId("region-a")
            .setOriginRegionEpoch(3L)
            .setTargetGameInstanceId("9")
            .setTargetRegionEpoch(4L)
            .setFollowupId("rf-1")
            .setScriptId("script-1")
            .setPluginId("plugin-1")
            .setScriptPatchVersion("patch-1")
            .setPluginVersionId("plugin-v1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
            .setWorldSlug("ops")
            .setRealmSlug("preview")
            .setPointerVersion(29L)
            .setPayloadKind("enqueue_automation_command")
            .setOriginSourceKind("REMOTE_FOLLOWUP")
            .setAutomationWorkItemId("work-1")
            .setTargetEntityId("entity-9")
            .setEffectKey("damage:1")
            .setRequiresSoloTick(true)
            .setEventType("onEnterRegion")
            .setScriptEventId("evt-1")
            .setAutomationDispatchId("dispatch-1")
            .setCommandId("cmd-1")
            .setLimit(25)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListRemoteFollowupsResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(1, responseRef.get().getFollowupsCount());
    assertEquals("rf-1", responseRef.get().getFollowups(0).getFollowupId());
    assertEquals("cmd-1", responseRef.get().getFollowups(0).getCommandId());
    assertEquals("dispatch-1", responseRef.get().getFollowups(0).getAutomationDispatchId());
    assertEquals("work-1", responseRef.get().getFollowups(0).getAutomationWorkItemId());
    assertEquals("script-1", responseRef.get().getFollowups(0).getScriptId());
    assertEquals("enqueue_automation_command", responseRef.get().getFollowups(0).getPayloadKind());
    assertEquals("LOOK", responseRef.get().getFollowups(0).getRequestedCommand());
    assertEquals(true, responseRef.get().getFollowups(0).getRequiresSoloTick());
    assertEquals("REMOTE_FOLLOWUP", responseRef.get().getFollowups(0).getOriginSourceKind());
    assertEquals(
        "TARGET_REGION_EXECUTED", responseRef.get().getFollowups(0).getOriginSourceState());
    assertEquals(44L, responseRef.get().getFollowups(0).getOriginSourceOrdinal());
    assertEquals(55L, responseRef.get().getFollowups(0).getOriginSourceDueTickId());
    assertEquals(1700L, responseRef.get().getFollowups(0).getOriginSourceDueAtMs());
    assertEquals("onEnterRegion", responseRef.get().getFollowups(0).getEventType());
    assertEquals("v1", responseRef.get().getFollowups(0).getEventSchemaVersion());
    assertEquals("evt-1", responseRef.get().getFollowups(0).getScriptEventId());
    assertEquals("TRIGGER_MODE_CATCH_UP", responseRef.get().getFollowups(0).getTriggerMode());
    assertEquals(
        "game-session:onEnterRegion:9:8:evt-1",
        responseRef.get().getFollowups(0).getReadSnapshotToken());
    assertEquals(
        "{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}",
        responseRef.get().getFollowups(0).getEventPayloadJson());
    assertEquals("region-b", responseRef.get().getFollowups(0).getTargetRegionId());
    assertEquals(55L, responseRef.get().getFollowups(0).getDueTickId());
    assertEquals(2L, responseRef.get().getFollowups(0).getClaimOrdinal());
    assertEquals("patch-1", responseRef.get().getFollowups(0).getScriptPatchVersion());
    assertEquals("plugin-1", responseRef.get().getFollowups(0).getPluginId());
    assertEquals("plugin-v1", responseRef.get().getFollowups(0).getPluginVersionId());
    assertEquals(17L, responseRef.get().getFollowups(0).getPublication().getVersionId());
    assertEquals(
        "plugin-v1", responseRef.get().getFollowups(0).getPluginPublication().getPluginVersionId());
    assertEquals(31L, responseRef.get().getFollowups(0).getPluginPublication().getPublicationId());
    assertEquals("rfcmd-rf-1", responseRef.get().getFollowups(0).getTargetCommandId());
    assertEquals("APPLIED", responseRef.get().getFollowups(0).getTargetCommandExecutionOutcome());
    assertEquals("SUCCESS", responseRef.get().getFollowups(0).getTargetCommandGameplayResult());
    assertEquals(3L, responseRef.get().getFollowups(0).getOriginDeadlineRegionEpoch());
    assertEquals(88L, responseRef.get().getFollowups(0).getOriginDeadlineTickId());
    assertEquals(
        "late_result_safe_to_ignore", responseRef.get().getFollowups(0).getLateResultPolicy());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
        responseRef.get().getFollowups(0).getPlayableStateScope());
    assertEquals("ops", responseRef.get().getFollowups(0).getWorldSlug());
    assertEquals("preview", responseRef.get().getFollowups(0).getRealmSlug());
    assertEquals(29L, responseRef.get().getFollowups(0).getPointerVersion());
  }

  @Test
  void scheduleRemoteFollowupDelegatesToRuntimeService() {
    RemoteFollowupRuntimeService runtimeService = Mockito.mock(RemoteFollowupRuntimeService.class);
    Mockito.when(runtimeService.scheduleFollowup(Mockito.any()))
        .thenReturn(
            new RemoteFollowupRuntimeService.ScheduleOutcome("coord-1", "followup-1", true, true));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(null, null, null, null, runtimeService, gameDesignClient());

    AtomicReference<ScheduleRemoteFollowupResponse> responseRef = new AtomicReference<>();
    service.scheduleRemoteFollowup(
        scheduleRemoteFollowupRequest(),
        new NoopObserver<>() {
          @Override
          public void onNext(ScheduleRemoteFollowupResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("coord-1", responseRef.get().getCoordinatorId());
    assertEquals("followup-1", responseRef.get().getFollowupId());
    assertEquals(true, responseRef.get().getCoordinatorCreated());
    assertEquals(true, responseRef.get().getFollowupCreated());
    Mockito.verify(runtimeService)
        .scheduleFollowup(
            Mockito.argThat(
                request ->
                    request.tenantId() == 1L
                        && "cmd-1".equals(request.commandId())
                        && "coord-1".equals(request.coordinatorId())
                        && request.originGameInstanceId() == 7L
                        && "region-a".equals(request.originRegionId())
                        && request.originRegionEpoch() == 4L
                        && request.targetGameInstanceId() == 9L
                        && "region-b".equals(request.targetRegionId())
                        && request.targetRegionEpoch() == 8L
                        && request.targetDueTickId() == 55L
                        && request.originDeadlineRegionEpoch() == 4L
                        && request.originDeadlineTickId() == 60L
                        && "late_result_safe_to_ignore".equals(request.lateResultPolicy())
                        && "followup-1".equals(request.followupId())
                        && "effect-1".equals(request.effectKey())
                        && "entity-9".equals(request.targetEntityId())
                        && request.payloadJson().contains("\"kind\":\"enqueue_automation_command\"")
                        && "enqueue_automation_command".equals(request.payloadKind())
                        && "LOOK".equals(request.requestedCommand())
                        && request.requiresSoloTick()
                        && "SHARED".equals(request.playableStateScope())
                        && "demo".equals(request.worldSlug())
                        && "production".equals(request.realmSlug())
                        && Objects.equals(Long.valueOf(17L), request.pointerVersion())
                        && "patch-1".equals(request.scriptPatchVersion())
                        && "plugin-1".equals(request.pluginId())
                        && "plugin-v1".equals(request.pluginVersionId())
                        && "dispatch-1".equals(request.automationDispatchId())
                        && "work-1".equals(request.automationWorkItemId())
                        && "script-1".equals(request.scriptId())
                        && "REMOTE_FOLLOWUP".equals(request.originSourceKind())
                        && "TARGET_REGION_EXECUTED".equals(request.originSourceState())
                        && Objects.equals(Long.valueOf(44L), request.originSourceOrdinal())
                        && Objects.equals(Long.valueOf(55L), request.originSourceDueTickId())
                        && Objects.equals(Long.valueOf(1700L), request.originSourceDueAtMs())));
  }

  @Test
  void scheduleRemoteFollowupReturnsInvalidArgumentOnRejectedPayload() {
    RemoteFollowupRuntimeService runtimeService = Mockito.mock(RemoteFollowupRuntimeService.class);
    Mockito.when(runtimeService.scheduleFollowup(Mockito.any()))
        .thenThrow(new IllegalArgumentException("payload kind is required"));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(null, null, null, null, runtimeService, gameDesignClient());

    AtomicReference<ScheduleRemoteFollowupResponse> responseRef = new AtomicReference<>();
    service.scheduleRemoteFollowup(
        scheduleRemoteFollowupRequest().toBuilder().setPayloadJson("{}").build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ScheduleRemoteFollowupResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("INVALID_ARGUMENT", responseRef.get().getError().getCode());
    assertEquals("payload kind is required", responseRef.get().getError().getMessage());
  }

  @Test
  void listRemoteFollowupResultsReturnsOriginAddressedRowsForAdminCaller() {
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setResultId("rr-1");
    result.setTenantId(1L);
    result.setCoordinatorId("coord-1");
    result.setFollowupId("rf-1");
    result.setOriginGameInstanceId(7L);
    result.setOriginRegionId("region-a");
    result.setOriginRegionEpoch(3L);
    result.setTargetGameInstanceId(9L);
    result.setTargetRegionId("region-b");
    result.setTargetRegionEpoch(4L);
    result.setOutcome("REMOTE_APPLIED");
    result.setResultPayloadJson(
        "{\"commandId\":\"payload-cmd\",\"errorCode\":\"payload-error\",\"message\":\"payload message\"}");
    result.setResultCommandId("auto-1");
    result.setResultErrorCode("RATE_LIMIT");
    result.setResultMessage("Target region rejected the remote gameplay command");
    result.setPlayableStateScope("SHARED");
    result.setWorldSlug("demo");
    result.setRealmSlug("production");
    result.setPointerVersion(17L);
    result.setCommandId("cmd-1");
    result.setAutomationDispatchId("dispatch-1");
    result.setAutomationWorkItemId("work-1");
    result.setScriptId("script-1");
    result.setScriptPatchVersion("patch-1");
    result.setPluginId("plugin-1");
    result.setPluginVersionId("plugin-v1");
    result.setObservedAt(Instant.parse("2026-05-01T00:00:02Z"));
    RemoteFollowupResultRepository repository = Mockito.mock(RemoteFollowupResultRepository.class);
    RemoteCommandCoordinatorRepository coordinatorRepository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    GameplayCommandRepository gameplayCommandRepository =
        Mockito.mock(GameplayCommandRepository.class);
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setTenantId(1L);
    coordinator.setCoordinatorId("coord-1");
    coordinator.setOriginDeadlineRegionEpoch(3L);
    coordinator.setOriginDeadlineTickId(88L);
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    GameplayCommand targetCommand = new GameplayCommand();
    targetCommand.setCommandId("auto-1");
    targetCommand.setExecutionOutcome("APPLIED");
    targetCommand.setGameplayResult("SUCCESS");
    Mockito.when(
            repository.findForControlPlane(
                1L,
                "coord-1",
                "rf-1",
                7L,
                "region-a",
                3L,
                9L,
                "region-b",
                4L,
                "REMOTE_APPLIED",
                "script-1",
                "plugin-1",
                "patch-1",
                "plugin-v1",
                "SHARED",
                "demo",
                "production",
                17L,
                "RATE_LIMIT",
                "work-1",
                "auto-1",
                "dispatch-1",
                "cmd-1",
                org.springframework.data.domain.PageRequest.of(0, 25)))
        .thenReturn(List.of(result));
    Mockito.when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    Mockito.when(gameplayCommandRepository.findByCommandId("auto-1"))
        .thenReturn(Optional.of(targetCommand));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(
            null,
            coordinatorRepository,
            repository,
            gameplayCommandRepository,
            null,
            gameDesignClient());

    AtomicReference<ListRemoteFollowupResultsResponse> responseRef = new AtomicReference<>();
    service.listRemoteFollowupResults(
        ListRemoteFollowupResultsRequest.newBuilder()
            .setTenantId("1")
            .setCoordinatorId("coord-1")
            .setFollowupId("rf-1")
            .setOriginGameInstanceId("7")
            .setOriginRegionId("region-a")
            .setOriginRegionEpoch(3L)
            .setTargetGameInstanceId("9")
            .setTargetRegionId("region-b")
            .setTargetRegionEpoch(4L)
            .setOutcome("REMOTE_APPLIED")
            .setScriptId("script-1")
            .setPluginId("plugin-1")
            .setScriptPatchVersion("patch-1")
            .setPluginVersionId("plugin-v1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(17L)
            .setResultErrorCode("RATE_LIMIT")
            .setAutomationWorkItemId("work-1")
            .setResultCommandId("auto-1")
            .setAutomationDispatchId("dispatch-1")
            .setCommandId("cmd-1")
            .setLimit(25)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListRemoteFollowupResultsResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(1, responseRef.get().getResultsCount());
    assertEquals("rr-1", responseRef.get().getResults(0).getResultId());
    assertEquals("7", responseRef.get().getResults(0).getOriginGameInstanceId());
    assertEquals("9", responseRef.get().getResults(0).getTargetGameInstanceId());
    assertEquals("cmd-1", responseRef.get().getResults(0).getCommandId());
    assertEquals("dispatch-1", responseRef.get().getResults(0).getAutomationDispatchId());
    assertEquals("work-1", responseRef.get().getResults(0).getAutomationWorkItemId());
    assertEquals("script-1", responseRef.get().getResults(0).getScriptId());
    assertEquals("REMOTE_APPLIED", responseRef.get().getResults(0).getOutcome());
    assertEquals(
        "{\"commandId\":\"payload-cmd\",\"errorCode\":\"payload-error\",\"message\":\"payload message\"}",
        responseRef.get().getResults(0).getResultPayloadJson());
    assertEquals("auto-1", responseRef.get().getResults(0).getResultCommandId());
    assertEquals("RATE_LIMIT", responseRef.get().getResults(0).getResultErrorCode());
    assertEquals(
        "Target region rejected the remote gameplay command",
        responseRef.get().getResults(0).getResultMessage());
    assertEquals("APPLIED", responseRef.get().getResults(0).getResultCommandExecutionOutcome());
    assertEquals("SUCCESS", responseRef.get().getResults(0).getResultCommandGameplayResult());
    assertEquals(3L, responseRef.get().getResults(0).getOriginDeadlineRegionEpoch());
    assertEquals(88L, responseRef.get().getResults(0).getOriginDeadlineTickId());
    assertEquals(
        "late_result_safe_to_ignore", responseRef.get().getResults(0).getLateResultPolicy());
    assertEquals("patch-1", responseRef.get().getResults(0).getScriptPatchVersion());
    assertEquals("plugin-1", responseRef.get().getResults(0).getPluginId());
    assertEquals("plugin-v1", responseRef.get().getResults(0).getPluginVersionId());
    assertEquals(17L, responseRef.get().getResults(0).getPublication().getVersionId());
    assertEquals(
        "plugin-v1", responseRef.get().getResults(0).getPluginPublication().getPluginVersionId());
    assertEquals(31L, responseRef.get().getResults(0).getPluginPublication().getPublicationId());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        responseRef.get().getResults(0).getPlayableStateScope());
    assertEquals("demo", responseRef.get().getResults(0).getWorldSlug());
    assertEquals("production", responseRef.get().getResults(0).getRealmSlug());
    assertEquals(17L, responseRef.get().getResults(0).getPointerVersion());
  }

  @Test
  void listRemoteFollowupResultsPrefersDurableFollowupLinkForTargetCommandStatus() {
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setResultId("rr-1");
    result.setTenantId(1L);
    result.setCoordinatorId("coord-1");
    result.setFollowupId("followup-1");
    result.setOriginGameInstanceId(7L);
    result.setOriginRegionId("region-a");
    result.setOriginRegionEpoch(2L);
    result.setTargetGameInstanceId(9L);
    result.setTargetRegionId("region-b");
    result.setTargetRegionEpoch(5L);
    result.setOutcome("REMOTE_APPLIED");
    result.setResultPayloadJson(
        "{\"commandId\":\"payload-cmd\",\"errorCode\":\"RATE_LIMIT\",\"message\":\"payload message\"}");
    result.setResultCommandId("durable-cmd");
    result.setResultErrorCode("TIMEOUT");
    result.setResultMessage("Target region timed out the remote gameplay command");
    result.setObservedAt(Instant.parse("2026-05-01T00:00:02Z"));
    RemoteFollowupResultRepository repository = Mockito.mock(RemoteFollowupResultRepository.class);
    RemoteCommandCoordinatorRepository coordinatorRepository =
        Mockito.mock(RemoteCommandCoordinatorRepository.class);
    GameplayCommandRepository gameplayCommandRepository =
        Mockito.mock(GameplayCommandRepository.class);
    GameplayCommand targetCommand = new GameplayCommand();
    targetCommand.setCommandId("linked-cmd");
    targetCommand.setTenantId(1L);
    targetCommand.setRemoteFollowupId("followup-1");
    targetCommand.setExecutionOutcome("NOT_APPLIED");
    targetCommand.setGameplayResult("FAILURE");
    Mockito.when(
            repository.findForControlPlane(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyLong(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any()))
        .thenReturn(List.of(result));
    Mockito.when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(targetCommand));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        remoteControlPlaneService(
            null,
            coordinatorRepository,
            repository,
            gameplayCommandRepository,
            null,
            gameDesignClient());

    AtomicReference<ListRemoteFollowupResultsResponse> responseRef = new AtomicReference<>();
    service.listRemoteFollowupResults(
        ListRemoteFollowupResultsRequest.newBuilder()
            .setTenantId("1")
            .setCoordinatorId("coord-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListRemoteFollowupResultsResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(1, responseRef.get().getResultsCount());
    assertEquals("linked-cmd", responseRef.get().getResults(0).getResultCommandId());
    assertEquals("TIMEOUT", responseRef.get().getResults(0).getResultErrorCode());
    assertEquals(
        "Target region timed out the remote gameplay command",
        responseRef.get().getResults(0).getResultMessage());
    assertEquals("NOT_APPLIED", responseRef.get().getResults(0).getResultCommandExecutionOutcome());
    assertEquals("FAILURE", responseRef.get().getResults(0).getResultCommandGameplayResult());
    Mockito.verify(gameplayCommandRepository, Mockito.never()).findByCommandId("payload-cmd");
    Mockito.verify(gameplayCommandRepository, Mockito.never()).findByCommandId("durable-cmd");
  }

  @Test
  void validateInstanceCutoverCompatibilityReturnsCompatibilityReportForAdminCaller() {
    InstanceCutoverCompatibilityService compatibilityService =
        Mockito.mock(InstanceCutoverCompatibilityService.class);
    Mockito.when(compatibilityService.validateInstanceCutoverCompatibility(1L, 7L, 9L))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.InstanceCutoverCompatibilityDto(
                7L,
                9L,
                "ld-9",
                "COMPATIBLE",
                List.of(),
                List.of("GAME_DESIGN", "WORLD", "ENTITY"),
                Instant.parse("2026-04-20T10:00:00Z"),
                "remap-1",
                List.of(
                    new net.firedevops.firemud.gamesession.dto.CutoverParticipantCompatibilityDto(
                        "WORLD",
                        List.of("S3"),
                        List.of("world_instance"),
                        false,
                        "COMPATIBLE",
                        List.of()),
                    new net.firedevops.firemud.gamesession.dto.CutoverParticipantCompatibilityDto(
                        "ENTITY",
                        List.of("S3"),
                        List.of("room_ground_inventory"),
                        false,
                        "COMPATIBLE",
                        List.of()))));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            compatibilityService,
            Mockito.mock(VersionUpgradePreparationService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ValidateInstanceCutoverCompatibilityResponse> responseRef =
        new AtomicReference<>();
    service.validateInstanceCutoverCompatibility(
        ValidateInstanceCutoverCompatibilityRequest.newBuilder()
            .setTenantId("1")
            .setSourceGameInstanceId("7")
            .setTargetVersionId("9")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ValidateInstanceCutoverCompatibilityResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(
        net.firedevops.firemud.gamesession.v1.CutoverCompatibilityResult
            .CUTOVER_COMPATIBILITY_RESULT_COMPATIBLE,
        responseRef.get().getResult());
    assertEquals("remap-1", responseRef.get().getRemapSetId());
    assertEquals(2, responseRef.get().getParticipantResultsCount());
  }

  @Test
  void prepareVersionUpgradeReturnsPersistedPreparationForAdminCaller() {
    VersionUpgradePreparationService preparationService =
        Mockito.mock(VersionUpgradePreparationService.class);
    Mockito.when(preparationService.prepareVersionUpgrade(1L, 7L, 9L, "pvu-req-1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto(
                "pvu-1",
                "pvu-req-1",
                1L,
                7L,
                7L,
                9L,
                "ld-9",
                "remap-1",
                "COMPATIBLE",
                List.of(),
                List.of("GAME_DESIGN", "WORLD", "ENTITY"),
                Instant.parse("2026-04-20T10:05:00Z"),
                List.of(),
                null,
                null,
                null,
                null));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            preparationService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<PrepareVersionUpgradeResponse> responseRef = new AtomicReference<>();
    service.prepareVersionUpgrade(
        PrepareVersionUpgradeRequest.newBuilder()
            .setTenantId("1")
            .setSourceGameInstanceId("7")
            .setTargetVersionId("9")
            .setControlPlaneRequestId("pvu-req-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(PrepareVersionUpgradeResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("pvu-1", responseRef.get().getPreparation().getPreparationId());
    assertEquals("pvu-req-1", responseRef.get().getPreparation().getControlPlaneRequestId());
    assertEquals("ld-9", responseRef.get().getPreparation().getTargetLaunchDescriptorId());
    assertEquals("remap-1", responseRef.get().getPreparation().getRemapSetId());
  }

  @Test
  void getPreparedVersionUpgradeReturnsPreparationForAdminCaller() {
    VersionUpgradePreparationService preparationService =
        Mockito.mock(VersionUpgradePreparationService.class);
    Mockito.when(preparationService.getPreparedVersionUpgrade(1L, "pvu-1"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto(
                "pvu-1",
                "pvu-req-1",
                1L,
                7L,
                7L,
                9L,
                "ld-9",
                "remap-1",
                "COMPATIBLE",
                List.of(),
                List.of("GAME_DESIGN", "WORLD", "ENTITY"),
                Instant.parse("2026-04-20T10:05:00Z"),
                List.of(),
                55L,
                4L,
                Instant.parse("2026-04-20T10:06:00Z"),
                "cutover-req-1"));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(InstanceCutoverCompatibilityService.class),
            preparationService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<GetPreparedVersionUpgradeResponse> responseRef = new AtomicReference<>();
    service.getPreparedVersionUpgrade(
        GetPreparedVersionUpgradeRequest.newBuilder()
            .setTenantId("1")
            .setPreparationId("pvu-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetPreparedVersionUpgradeResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("pvu-1", responseRef.get().getPreparation().getPreparationId());
    assertEquals("pvu-req-1", responseRef.get().getPreparation().getControlPlaneRequestId());
    assertEquals("55", responseRef.get().getPreparation().getExecutedTargetGameInstanceId());
    assertEquals(4L, responseRef.get().getPreparation().getExecutedPointerVersion());
  }

  private static GameSessionControlPlaneGrpcService newService(GameInstanceRepository repository) {
    return newService(repository, new SimpleMeterRegistry());
  }

  private static GameSessionControlPlaneGrpcService newService(
      GameInstanceRepository repository, SimpleMeterRegistry meterRegistry) {
    return newService(repository, meterRegistry, new GameSessionProperties());
  }

  private static GameSessionControlPlaneGrpcService newService(
      GameInstanceRepository repository,
      SimpleMeterRegistry meterRegistry,
      GameSessionProperties gameSessionProperties) {
    return new GameSessionControlPlaneGrpcService(
        repository,
        Mockito.mock(GameplayCommandRepository.class),
        Mockito.mock(RuntimeRegionStatusRepository.class),
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
        Mockito.mock(InstanceCutoverCompatibilityService.class),
        Mockito.mock(VersionUpgradePreparationService.class),
        gameDesignClient(),
        BuiltInTextCommandAliasResolver.unsupported(),
        Mockito.mock(TickService.class),
        meterRegistry,
        gameSessionProperties);
  }

  private static EnqueueAutomationCommandIfAbsentRequest automationRequest() {
    return EnqueueAutomationCommandIfAbsentRequest.newBuilder()
        .setTenantId("1")
        .setGameInstanceId("7")
        .setRegionId("region-1")
        .setRegionEpoch(12L)
        .setDueTickId(34L)
        .setAutomationDispatchId("dispatch-1")
        .setAutomationWorkItemId("work-1")
        .setScriptId("script-1")
        .setScriptPatchVersion("patch-1")
        .setPluginId("plugin-1")
        .setPluginVersionId("plugin-v1")
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion("17")
        .setOriginSourceKind("SCHEDULE_TIMER")
        .setOriginSourceState("SCHEDULE_DUE_CLAIMED")
        .setOriginSourceOrdinal(5000L)
        .setOriginSourceDueAtMs(5000L)
        .setTargetEntityId("entity-1")
        .setCommand("say hello")
        .build();
  }

  private static ScheduleRemoteFollowupRequest scheduleRemoteFollowupRequest() {
    return ScheduleRemoteFollowupRequest.newBuilder()
        .setTenantId("1")
        .setCommandId("cmd-1")
        .setCoordinatorId("coord-1")
        .setOriginGameInstanceId("7")
        .setOriginRegionId("region-a")
        .setOriginRegionEpoch(4L)
        .setTargetGameInstanceId("9")
        .setTargetRegionId("region-b")
        .setTargetRegionEpoch(8L)
        .setTargetDueTickId(55L)
        .setOriginDeadlineRegionEpoch(4L)
        .setOriginDeadlineTickId(60L)
        .setLateResultPolicy("late_result_safe_to_ignore")
        .setFollowupId("followup-1")
        .setEffectKey("effect-1")
        .setTargetEntityId("entity-9")
        .setPayloadJson("{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}")
        .setPayloadKind("enqueue_automation_command")
        .setRequestedCommand("LOOK")
        .setRequiresSoloTick(true)
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion(17L)
        .setScriptPatchVersion("patch-1")
        .setPluginId("plugin-1")
        .setPluginVersionId("plugin-v1")
        .setAutomationDispatchId("dispatch-1")
        .setAutomationWorkItemId("work-1")
        .setScriptId("script-1")
        .setOriginSourceKind("REMOTE_FOLLOWUP")
        .setOriginSourceState("TARGET_REGION_EXECUTED")
        .setOriginSourceOrdinal(44L)
        .setOriginSourceDueTickId(55L)
        .setOriginSourceDueAtMs(1700L)
        .build();
  }

  private static GameplayCommandRepository commandRepositorySavingArgument() {
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    AtomicLong idSequence = new AtomicLong();
    Mockito.when(repository.save(Mockito.any(GameplayCommand.class)))
        .thenAnswer(
            invocation -> {
              GameplayCommand command = invocation.getArgument(0);
              if (command.getId() == null) {
                long id = idSequence.incrementAndGet();
                command.setId(id);
                command.setEnqueueSeq(id);
              }
              return command;
            });
    return repository;
  }

  private static RuntimeRegionStatus runtimeStatus(boolean paused, long regionEpoch) {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(1L);
    status.setGameInstanceId(7L);
    status.setRegionId("region-1");
    status.setRegionEpoch(regionEpoch);
    status.setExecutorFence("fence-1");
    status.setOwnerService("game-session-service");
    status.setOwnerInstanceId("instance-1");
    status.setPaused(paused);
    status.setUpdatedAt(Instant.EPOCH);
    return status;
  }

  private static RuntimeRegionStatusRepository runtimeRepository(RuntimeRegionStatus status) {
    RuntimeRegionStatusRepository repository = Mockito.mock(RuntimeRegionStatusRepository.class);
    Mockito.when(repository.findByTenantIdAndRegionId(1L, status.getRegionId()))
        .thenReturn(Optional.of(status));
    Mockito.when(repository.findByTenantIdAndGameInstanceId(1L, 7L))
        .thenReturn(Optional.of(status));
    return repository;
  }

  private static GameSessionControlPlaneGrpcService remoteControlPlaneService(
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService,
      GameDesignClient gameDesignClient) {
    return new GameSessionControlPlaneGrpcService(
        Mockito.mock(GameInstanceRepository.class),
        gameplayCommandRepository == null
            ? Mockito.mock(GameplayCommandRepository.class)
            : gameplayCommandRepository,
        Mockito.mock(RuntimeRegionStatusRepository.class),
        remoteFollowupRepository == null
            ? Mockito.mock(RemoteFollowupRepository.class)
            : remoteFollowupRepository,
        remoteCommandCoordinatorRepository == null
            ? Mockito.mock(RemoteCommandCoordinatorRepository.class)
            : remoteCommandCoordinatorRepository,
        remoteFollowupResultRepository == null
            ? Mockito.mock(RemoteFollowupResultRepository.class)
            : remoteFollowupResultRepository,
        remoteFollowupRuntimeService,
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
        Mockito.mock(InstanceCutoverCompatibilityService.class),
        Mockito.mock(VersionUpgradePreparationService.class),
        gameDesignClient,
        BuiltInTextCommandAliasResolver.unsupported(),
        Mockito.mock(TickService.class),
        new SimpleMeterRegistry(),
        new GameSessionProperties());
  }

  private static class NoopObserver<T> implements StreamObserver<T> {
    @Override
    public void onNext(T value) {}

    @Override
    public void onError(Throwable t) {
      fail(t);
    }

    @Override
    public void onCompleted() {}
  }
}
