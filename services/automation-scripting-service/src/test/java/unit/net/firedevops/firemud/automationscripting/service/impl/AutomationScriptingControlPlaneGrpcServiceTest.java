package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.AutomationAdmissionMode;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionResponse;
import net.firedevops.firemud.automationscripting.v1.DisablePluginRequest;
import net.firedevops.firemud.automationscripting.v1.DisablePluginResponse;
import net.firedevops.firemud.automationscripting.v1.DrainPluginRequest;
import net.firedevops.firemud.automationscripting.v1.DrainPluginResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptTimerAuditEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptTimerAuditEventsResponse;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsRequest;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeRequest;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeResponse;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionRequest;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamesession.v1.GameplayCommandStatus;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationScriptingControlPlaneGrpcServiceTest {
  private static AutomationAdmissionStateService admissionStateService() {
    return Mockito.mock(AutomationAdmissionStateService.class);
  }

  private static GameDesignControlPlaneClient gameDesignClient() {
    GameDesignControlPlaneClient client = Mockito.mock(GameDesignControlPlaneClient.class);
    Mockito.when(client.getPublishedScriptPatchVersion(Mockito.anyString(), Mockito.anyString()))
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
    return client;
  }

  private static AutomationScriptingControlPlaneGrpcService newService(
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService) {
    return newService(
        workItemService,
        pluginRuntimeStateService,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        new ScriptRuntimeProperties(),
        Mockito.mock(ScriptScheduleInstanceService.class),
        gameDesignClient(),
        Mockito.mock(GameSessionControlPlaneClient.class));
  }

  private static AutomationScriptingControlPlaneGrpcService newService(
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptRuntimeProperties runtimeProperties) {
    return newService(
        workItemService,
        pluginRuntimeStateService,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        runtimeProperties,
        Mockito.mock(ScriptScheduleInstanceService.class),
        gameDesignClient(),
        Mockito.mock(GameSessionControlPlaneClient.class));
  }

  private static AutomationScriptingControlPlaneGrpcService newService(
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptRuntimeProperties runtimeProperties,
      ScriptScheduleInstanceService scriptScheduleInstanceService) {
    return newService(
        workItemService,
        pluginRuntimeStateService,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        runtimeProperties,
        scriptScheduleInstanceService,
        gameDesignClient(),
        Mockito.mock(GameSessionControlPlaneClient.class));
  }

  private static AutomationScriptingControlPlaneGrpcService newService(
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptRuntimeProperties runtimeProperties,
      ScriptScheduleInstanceService scriptScheduleInstanceService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    AutomationEventControlPlaneService eventControlPlaneService =
        new AutomationEventControlPlaneService(new BuiltInScriptEventRegistryService());
    AutomationPatchControlPlaneService patchControlPlaneService =
        new AutomationPatchControlPlaneService(
            workItemService,
            automationAdmissionStateService,
            scriptPatchPinProjectionService,
            scriptScheduleInstanceService,
            gameDesignControlPlaneClient,
            gameSessionControlPlaneClient,
            runtimeProperties,
            new TemporalScriptPatchReadinessWorkflowMetadataResolver(
                java.util.Optional.empty(), java.util.Optional.empty()));
    AutomationPluginControlPlaneService pluginControlPlaneService =
        new AutomationPluginControlPlaneService(pluginRuntimeStateService, runtimeProperties);
    return new AutomationScriptingControlPlaneGrpcService(
        eventControlPlaneService, patchControlPlaneService, pluginControlPlaneService);
  }

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void getsBuiltInEventDefinition() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<GetScriptEventDefinitionResponse> ref = new AtomicReference<>();

    service.getScriptEventDefinition(
        GetScriptEventDefinitionRequest.newBuilder().setEventType("onCommand").build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDefinition().getEventType()).isEqualTo("onCommand");
    assertThat(ref.get().getDefinition().getSnapshotAuthority())
        .isEqualTo("PRODUCER_SUPPLIED_TOKEN");
    assertThat(ref.get().getDefinition().getPayloadSchemaRef()).contains("#oncommand-payload-v1");
  }

  @Test
  void listsBuiltInEventDefinitionsByOwner() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<ListScriptEventDefinitionsResponse> ref = new AtomicReference<>();

    service.listScriptEventDefinitions(
        ListScriptEventDefinitionsRequest.newBuilder()
            .setOwnerService("automation-scripting-service")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDefinitionsList())
        .extracting(definition -> definition.getEventType())
        .contains("onLoad", "onInterval", "onTimerExpire");
  }

  @Test
  void cancelsPendingWorkItemsForPatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.cancelPendingForPatch(Mockito.any())).thenReturn(3L);
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<CancelPendingWorkItemsForPatchResponse> ref = new AtomicReference<>();

    service.cancelPendingWorkItemsForPatch(
        CancelPendingWorkItemsForPatchRequest.newBuilder()
            .setTenantId("1")
            .setScriptPatchVersion("patch-1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .setReason("rollback_epoch_advanced")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getCanceledCount()).isEqualTo(3L);
    Mockito.verify(workItemService)
        .cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                "1", "patch-1", "game-1", "region-1", "", "", "rollback_epoch_advanced"));
  }

  @Test
  void cancelsPendingWorkItemsForPluginVersion() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.cancelPendingForPluginVersion(Mockito.any())).thenReturn(2L);
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<CancelPendingWorkItemsForPluginVersionResponse> ref = new AtomicReference<>();

    service.cancelPendingWorkItemsForPluginVersion(
        CancelPendingWorkItemsForPluginVersionRequest.newBuilder()
            .setTenantId("1")
            .setPluginId("plugin-1")
            .setPluginVersionId("plugin-v1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .setReason("plugin_disabled")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getCanceledCount()).isEqualTo(2L);
    Mockito.verify(workItemService)
        .cancelPendingForPluginVersion(
            new ScriptWorkItemService.CancelPendingForPluginVersionCommand(
                "1", "plugin-1", "plugin-v1", "game-1", "region-1", "", "", "plugin_disabled"));
  }

  @Test
  void getsScriptPatchStatusFromWorkItemReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.getPatchStatus("1", "patch-1"))
        .thenReturn(
            java.util.Optional.of(
                new ScriptWorkItemService.PatchStatusSummary(
                    "patch-1",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY,
                    "runtime_work_terminal",
                    "",
                    123L,
                    7L,
                    "ability-1",
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        7L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        120L,
                        "",
                        ""))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<GetScriptPatchStatusResponse> ref = new AtomicReference<>();

    service.getScriptPatchStatus(
        GetScriptPatchStatusRequest.newBuilder()
            .setTenantId("1")
            .setScriptPatchVersion("patch-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getStatus()).isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY);
    assertThat(ref.get().getStatusReason()).isEqualTo("runtime_work_terminal");
    assertThat(ref.get().getLastChangedAtMs()).isEqualTo(123L);
    assertThat(ref.get().getBaseVersionId()).isEqualTo(7L);
    assertThat(ref.get().getAbilitySchemaDigest()).isEqualTo("ability-1");
    assertThat(ref.get().getPublication().getVersionId()).isEqualTo(17L);
    assertThat(ref.get().getWorkflowId())
        .isEqualTo("script-patch-readiness:1:script-patch-version:patch-1");
    assertThat(ref.get().getWorkflowFamily()).isEqualTo("script-patch-readiness");
    assertThat(ref.get().getWorkflowStatus()).isEqualTo("TEMPORAL_DISABLED");
  }

  @Test
  void listsScriptPatchStatusesFromWorkItemReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listPatchStatuses(
                "1", ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED, 10L, 20L))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.PatchStatusSummary(
                    "patch-2",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED,
                    "terminal_work_failed",
                    "",
                    15L,
                    7L,
                    "ability-1",
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-2",
                        18L,
                        7L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        14L,
                        "",
                        ""))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<ListScriptPatchStatusesResponse> ref = new AtomicReference<>();

    service.listScriptPatchStatuses(
        ListScriptPatchStatusesRequest.newBuilder()
            .setTenantId("1")
            .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED)
            .setChangedAfterMs(10L)
            .setChangedBeforeMs(20L)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getPatchesList()).hasSize(1);
    assertThat(ref.get().getPatches(0).getScriptPatchVersion()).isEqualTo("patch-2");
    assertThat(ref.get().getPatches(0).getStatus())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED);
    assertThat(ref.get().getPatches(0).getBaseVersionId()).isEqualTo(7L);
    assertThat(ref.get().getPatches(0).getAbilitySchemaDigest()).isEqualTo("ability-1");
    assertThat(ref.get().getPatches(0).getPublication().getVersionId()).isEqualTo(18L);
    assertThat(ref.get().getPatches(0).getWorkflowId())
        .isEqualTo("script-patch-readiness:1:script-patch-version:patch-2");
    assertThat(ref.get().getPatches(0).getWorkflowFamily()).isEqualTo("script-patch-readiness");
    assertThat(ref.get().getPatches(0).getWorkflowStatus()).isEqualTo("TEMPORAL_DISABLED");
  }

  @Test
  void getsAutomationDrainStatusFromWorkItemReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    long observedAtMs = System.currentTimeMillis();
    Mockito.when(workItemService.getAutomationDrainStatus("1", "game-1", "region-1"))
        .thenReturn(
            new ScriptWorkItemService.AutomationDrainStatusSummary(
                "1", "game-1", "region-1", "NORMAL", 1L, 2L, 123L, 4L, observedAtMs));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<GetAutomationDrainStatusResponse> ref = new AtomicReference<>();

    service.getAutomationDrainStatus(
        GetAutomationDrainStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getTenantId()).isEqualTo("1");
    assertThat(ref.get().getGameInstanceId()).isEqualTo("game-1");
    assertThat(ref.get().getRegionId()).isEqualTo("region-1");
    assertThat(ref.get().getAdmissionMode().name()).isEqualTo("AUTOMATION_ADMISSION_MODE_NORMAL");
    assertThat(ref.get().getAdmissionEpoch()).isEqualTo(1L);
    assertThat(ref.get().getActiveExecutionCount()).isEqualTo(2L);
    assertThat(ref.get().getOldestActiveExecutionStartedAtMs()).isEqualTo(123L);
    assertThat(ref.get().getPendingCancelableWorkItemCount()).isEqualTo(4L);
    assertThat(ref.get().getObservedAtMs()).isEqualTo(observedAtMs);
    assertThat(ref.get().getIsStale()).isFalse();
  }

  @Test
  void marksAutomationDrainStatusStaleWhenObservedTimestampAgesPastThreshold() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    long observedAtMs = System.currentTimeMillis() - 10_000L;
    Mockito.when(workItemService.getAutomationDrainStatus("1", "game-1", "region-1"))
        .thenReturn(
            new ScriptWorkItemService.AutomationDrainStatusSummary(
                "1", "game-1", "region-1", "NORMAL", 1L, 2L, 123L, 4L, observedAtMs));
    ScriptRuntimeProperties runtimeProperties = new ScriptRuntimeProperties();
    runtimeProperties.setDrainStatusStaleThresholdMs(1L);
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            runtimeProperties);
    AtomicReference<GetAutomationDrainStatusResponse> ref = new AtomicReference<>();

    service.getAutomationDrainStatus(
        GetAutomationDrainStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getObservedAtMs()).isEqualTo(observedAtMs);
    assertThat(ref.get().getIsStale()).isTrue();
  }

  @Test
  void listsScriptScheduleInstancesFromReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    long pinObservedAtMs = System.currentTimeMillis();
    long runtimeProgressObservedAtMs = System.currentTimeMillis();
    Mockito.when(scheduleInstanceService.listInstances("1", "game-1", "patch-1", 25))
        .thenReturn(
            List.of(
                new ScriptScheduleInstanceService.ScheduleInstanceSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    "npc-guard",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "",
                    "",
                    "onTimerExpire",
                    "guard.alert.expire.v1",
                    "TIMER",
                    5000L,
                    "MILLISECONDS",
                    "normal",
                    "ENTITY",
                    "guard-1",
                    10,
                    false,
                    "READY",
                    5555L,
                    0L,
                    "runtime-v2",
                    "req-9",
                    pinObservedAtMs,
                    1235L,
                    1236L,
                    "region-1",
                    12L,
                    100L,
                    runtimeProgressObservedAtMs,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo")
                        .setRealmSlug("production")
                        .setPointerVersion(17L)
                        .build())
                .build());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            new ScriptRuntimeProperties(),
            scheduleInstanceService,
            gameDesignClient(),
            gameSessionClient);
    AtomicReference<ListScriptScheduleInstancesResponse> ref = new AtomicReference<>();

    service.listScriptScheduleInstances(
        ListScriptScheduleInstancesRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setLimit(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getSchedulesList()).hasSize(1);
    assertThat(ref.get().getSchedules(0).getScheduleDefinitionId())
        .isEqualTo("guard.alert.expire.v1");
    assertThat(ref.get().getSchedules(0).getTargetScopeType()).isEqualTo("ENTITY");
    assertThat(ref.get().getSchedules(0).getTargetScopeId()).isEqualTo("guard-1");
    assertThat(ref.get().getSchedules(0).getMaterializationStatus()).isEqualTo("READY");
    assertThat(ref.get().getSchedules(0).getNextDueAtMs()).isEqualTo(5555L);
    assertThat(ref.get().getSchedules(0).getWorldSlug()).isEqualTo("demo");
    assertThat(ref.get().getSchedules(0).getRealmSlug()).isEqualTo("production");
    assertThat(ref.get().getSchedules(0).getPointerVersion()).isEqualTo("17");
    assertThat(ref.get().getSchedules(0).getIsPinStale()).isFalse();
    assertThat(ref.get().getSchedules(0).getIsRuntimeProgressStale()).isFalse();
    assertThat(ref.get().getSchedules(0).getCurrentRuntimeGameInstanceId()).isEqualTo("game-1");
    assertThat(ref.get().getSchedules(0).getCurrentRuntimeRegionId()).isEqualTo("region-1");
    assertThat(ref.get().getSchedules(0).getCurrentRuntimeRegionEpoch()).isEqualTo(12L);
    assertThat(ref.get().getSchedules(0).getIsRuntimeScopeStale()).isFalse();
    assertThat(ref.get().getSchedules(0).getCurrentRuntimePlayableStateScope())
        .isEqualTo(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(ref.get().getSchedules(0).getCurrentRuntimeWorldSlug()).isEqualTo("demo");
    assertThat(ref.get().getSchedules(0).getCurrentRuntimeRealmSlug()).isEqualTo("production");
    assertThat(ref.get().getSchedules(0).getCurrentRuntimePointerVersion()).isEqualTo("17");
    assertThat(ref.get().getSchedules(0).getIsRoutingBundleStale()).isFalse();
    assertThat(ref.get().getSchedules(0).getPublication().getVersionId()).isEqualTo(17L);
  }

  @Test
  void marksScriptScheduleInstanceFreshnessFlagsWhenObservationsAgeOut() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    long stalePinObservedAtMs = System.currentTimeMillis() - 10_000L;
    long staleRuntimeProgressObservedAtMs = System.currentTimeMillis() - 10_000L;
    Mockito.when(scheduleInstanceService.listInstances("1", "game-1", "patch-1", 25))
        .thenReturn(
            List.of(
                new ScriptScheduleInstanceService.ScheduleInstanceSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    "npc-guard",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "",
                    "",
                    "onTimerExpire",
                    "guard.alert.expire.v1",
                    "TIMER",
                    5000L,
                    "MILLISECONDS",
                    "normal",
                    "ENTITY",
                    "guard-1",
                    10,
                    false,
                    "READY",
                    5555L,
                    0L,
                    "runtime-v2",
                    "req-9",
                    stalePinObservedAtMs,
                    1235L,
                    1236L,
                    "region-1",
                    12L,
                    100L,
                    staleRuntimeProgressObservedAtMs,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        18L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    ScriptRuntimeProperties runtimeProperties = new ScriptRuntimeProperties();
    runtimeProperties.setPinProjectionStaleThresholdMs(1L);
    runtimeProperties.setScheduleRuntimeProgressStaleThresholdMs(1L);
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            runtimeProperties,
            scheduleInstanceService);
    AtomicReference<ListScriptScheduleInstancesResponse> ref = new AtomicReference<>();

    service.listScriptScheduleInstances(
        ListScriptScheduleInstancesRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setLimit(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getSchedulesList()).hasSize(1);
    assertThat(ref.get().getSchedules(0).getIsPinStale()).isTrue();
    assertThat(ref.get().getSchedules(0).getIsRuntimeProgressStale()).isTrue();
    assertThat(ref.get().getSchedules(0).getPublication().getVersionId()).isEqualTo(18L);
  }

  @Test
  void listsScriptTimerAuditEventsFromReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(
            scheduleInstanceService.listTimerAuditEvents(
                "1",
                "game-1",
                "patch-1",
                "npc-guard",
                "onInterval",
                "catch_up_truncated",
                100L,
                200L,
                25))
        .thenReturn(
            List.of(
                new ScriptScheduleInstanceService.TimerAuditEventSummary(
                    "1",
                    "game-1",
                    "region-1",
                    12L,
                    "guard-1",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "npc-guard",
                    "plugin-1",
                    "plugin-v1",
                    "onInterval",
                    "patch-1",
                    "timer-1",
                    "TRIGGER_MODE_CATCH_UP",
                    "SCHEDULE_DROPPED",
                    130L,
                    130L,
                    0L,
                    0L,
                    "ADMISSION",
                    "canceled",
                    "catch_up_truncated",
                    1234L,
                    1235L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-1")
                        .setRegionId("region-live")
                        .setRegionEpoch(44L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED)
                        .setWorldSlug("demo-next")
                        .setRealmSlug("staging")
                        .setPointerVersion(99L)
                        .build())
                .build());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            new ScriptRuntimeProperties(),
            scheduleInstanceService,
            gameDesignClient(),
            gameSessionClient);
    AtomicReference<ListScriptTimerAuditEventsResponse> ref = new AtomicReference<>();

    service.listScriptTimerAuditEvents(
        ListScriptTimerAuditEventsRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setScriptId("npc-guard")
            .setEventType("onInterval")
            .setFinalReason("catch_up_truncated")
            .setChangedAfterMs(100L)
            .setChangedBeforeMs(200L)
            .setLimit(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getEventsCount()).isEqualTo(1);
    assertThat(ref.get().getEvents(0).getPluginId()).isEqualTo("plugin-1");
    assertThat(ref.get().getEvents(0).getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(ref.get().getEvents(0).getTriggerMode())
        .isEqualTo(TriggerMode.TRIGGER_MODE_CATCH_UP);
    assertThat(ref.get().getEvents(0).getFinalReason()).isEqualTo("catch_up_truncated");
    assertThat(ref.get().getEvents(0).getSourceDueTickId()).isEqualTo(130L);
    assertThat(ref.get().getEvents(0).getCurrentRuntimeGameInstanceId()).isEqualTo("game-1");
    assertThat(ref.get().getEvents(0).getCurrentRuntimeRegionId()).isEqualTo("region-live");
    assertThat(ref.get().getEvents(0).getCurrentRuntimeRegionEpoch()).isEqualTo(44L);
    assertThat(ref.get().getEvents(0).getIsRuntimeScopeStale()).isTrue();
    assertThat(ref.get().getEvents(0).getCurrentRuntimePlayableStateScope())
        .isEqualTo(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_ISOLATED);
    assertThat(ref.get().getEvents(0).getCurrentRuntimeWorldSlug()).isEqualTo("demo-next");
    assertThat(ref.get().getEvents(0).getCurrentRuntimeRealmSlug()).isEqualTo("staging");
    assertThat(ref.get().getEvents(0).getCurrentRuntimePointerVersion()).isEqualTo("99");
    assertThat(ref.get().getEvents(0).getIsRoutingBundleStale()).isTrue();
    assertThat(ref.get().getEvents(0).getPublication().getVersionId()).isEqualTo(17L);
  }

  @Test
  void setsAutomationAdmissionModeThroughAdmissionStateService() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    Mockito.when(admissionStateService.setMode(Mockito.any()))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1",
                "game-1",
                "region-1",
                "PAUSED_FOR_ROLLBACK",
                2L,
                "req-2",
                "admin",
                "rollback",
                300L));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<SetAutomationAdmissionModeResponse> ref = new AtomicReference<>();

    service.setAutomationAdmissionMode(
        SetAutomationAdmissionModeRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .setMode(AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK)
            .setControlPlaneRequestId("req-2")
            .setActorPrincipal("admin")
            .setReason("rollback")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getMode())
        .isEqualTo(AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK);
    assertThat(ref.get().getAdmissionEpoch()).isEqualTo(2L);
    assertThat(ref.get().getUpdatedAtMs()).isEqualTo(300L);
  }

  @Test
  void getsAutomationPinConvergenceFromRuntimeState() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    Mockito.when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1",
                        "game-1",
                        "patch-2",
                        "req-22",
                        222L,
                        230L,
                        4L,
                        false,
                        "region-7",
                        22L,
                        "demo",
                        "production",
                        "17")),
                "",
                ""));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            pinProjectionService);
    AtomicReference<GetAutomationPinConvergenceResponse> ref = new AtomicReference<>();

    service.getAutomationPinConvergence(
        GetAutomationPinConvergenceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getObservedPinnedScriptPatchVersion()).isEqualTo("patch-2");
    assertThat(ref.get().getLastObservedControlPlaneRequestId()).isEqualTo("req-22");
    assertThat(ref.get().getObservedAtMs()).isEqualTo(222L);
    assertThat(ref.get().getProjectionAsOfMs()).isEqualTo(230L);
    assertThat(ref.get().getProjectionLagMs()).isEqualTo(4L);
    assertThat(ref.get().getIsProjectionStale()).isFalse();
    assertThat(ref.get().getRegionId()).isEqualTo("region-7");
    assertThat(ref.get().getRegionEpoch()).isEqualTo(22L);
    assertThat(ref.get().getWorldSlug()).isEqualTo("demo");
    assertThat(ref.get().getRealmSlug()).isEqualTo("production");
    assertThat(ref.get().getPointerVersion()).isEqualTo("17");
    assertThat(ref.get().getPublication().getVersionId()).isEqualTo(17L);
  }

  @Test
  void getsScriptPatchInstanceRolloutStatusFromReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.getPatchInstanceRolloutStatus("1", "game-1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED,
                    "runtime_pin_matches_patch",
                    123L,
                    130L,
                    0L,
                    false,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<GetScriptPatchInstanceRolloutStatusResponse> ref = new AtomicReference<>();

    service.getScriptPatchInstanceRolloutStatus(
        GetScriptPatchInstanceRolloutStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getRolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED);
    assertThat(ref.get().getProjectionLagMs()).isZero();
    assertThat(ref.get().getPublication().getVersionId()).isEqualTo(17L);
  }

  @Test
  void listsScriptPatchInstanceRolloutsFromReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listPatchInstanceRollouts(
                "1",
                "game-1",
                "",
                ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED,
                10L,
                20L))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
                    "runtime_pin_differs_from_patch",
                    15L,
                    16L,
                    0L,
                    false,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        18L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<ListScriptPatchInstanceRolloutsResponse> ref = new AtomicReference<>();

    service.listScriptPatchInstanceRollouts(
        ListScriptPatchInstanceRolloutsRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setChangedAfterMs(10L)
            .setChangedBeforeMs(20L)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getRolloutsList()).hasSize(1);
    assertThat(ref.get().getRollouts(0).getRolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK);
    assertThat(ref.get().getRollouts(0).getPublication().getVersionId()).isEqualTo(18L);
  }

  @Test
  void listsScriptPatchInstanceRolloutEventsFromReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listPatchInstanceRolloutEvents(
                "1",
                "game-1",
                "patch-1",
                ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED,
                10L,
                20L,
                50))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.PatchInstanceRolloutEventSummary(
                    "event-1",
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED,
                    "runtime_pin_restored_after_rollback",
                    15L,
                    16L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        19L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<ListScriptPatchInstanceRolloutEventsResponse> ref = new AtomicReference<>();

    service.listScriptPatchInstanceRolloutEvents(
        ListScriptPatchInstanceRolloutEventsRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setRolloutStatus(
                ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED)
            .setChangedAfterMs(10L)
            .setChangedBeforeMs(20L)
            .setLimit(50)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getEventsList()).hasSize(1);
    assertThat(ref.get().getEvents(0).getEventId()).isEqualTo("event-1");
    assertThat(ref.get().getEvents(0).getRolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED);
    assertThat(ref.get().getEvents(0).getPublication().getVersionId()).isEqualTo(19L);
  }

  @Test
  void listsScriptHandoffEventsFromReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listHandoffEvents(
                "1",
                "game-1",
                "patch-1",
                "99",
                "enqueued",
                "game-2",
                "region-2",
                17L,
                "remote-coordinator:workItem:99#0",
                "remote-followup:workItem:99#0",
                "script-1",
                "plugin-1",
                "workItem:99#0",
                "command-1",
                "target-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "SCHEDULE_TIMER",
                "SCHEDULE_DUE_CLAIMED",
                10L,
                20L,
                50))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.HandoffEventSummary(
                    "event-1",
                    "1",
                    "game-1",
                    "patch-1",
                    "script-1",
                    "plugin-1",
                    "plugin-v1",
                    "99",
                    0,
                    "workItem:99#0",
                    "command-1",
                    "game-2",
                    "region-2",
                    17L,
                    "remote-coordinator:workItem:99#0",
                    "remote-followup:workItem:99#0",
                    "target-1",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "SCHEDULE_TIMER",
                    "SCHEDULE_DUE_CLAIMED",
                    5000L,
                    0L,
                    5000L,
                    "LOOK AT old chest",
                    "enqueued",
                    "game_session_accepted",
                    15L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-2"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-2")
                        .setRegionId("region-live")
                        .setRegionEpoch(22L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED)
                        .setWorldSlug("demo-next")
                        .setRealmSlug("staging")
                        .setPointerVersion(99L)
                        .build())
                .build());
    Mockito.when(gameSessionClient.getGameplayCommandStatus("1", "command-1"))
        .thenReturn(
            GetGameplayCommandStatusResponse.newBuilder()
                .setCommand(
                    GameplayCommandStatus.newBuilder()
                        .setCommandId("command-1")
                        .setExecutionOutcome("APPLIED")
                        .setGameplayResult("SUCCESS")
                        .setRemoteState("REMOTE_APPLIED")
                        .setRemoteTargetCommandExecutionOutcome("APPLIED")
                        .setRemoteTargetCommandGameplayResult("SUCCESS")
                        .build())
                .build());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            new ScriptRuntimeProperties(),
            Mockito.mock(ScriptScheduleInstanceService.class),
            gameDesignClient(),
            gameSessionClient);
    AtomicReference<ListScriptHandoffEventsResponse> ref = new AtomicReference<>();

    service.listScriptHandoffEvents(
        ListScriptHandoffEventsRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setWorkItemId("99")
            .setHandoffOutcome("enqueued")
            .setTargetGameInstanceId("game-2")
            .setTargetRegionId("region-2")
            .setTargetRegionEpoch(17L)
            .setRemoteCoordinatorId("remote-coordinator:workItem:99#0")
            .setRemoteFollowupId("remote-followup:workItem:99#0")
            .setScriptId("script-1")
            .setPluginId("plugin-1")
            .setAutomationDispatchId("workItem:99#0")
            .setGameSessionCommandId("command-1")
            .setTargetEntityId("target-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion("17")
            .setSourceKind("SCHEDULE_TIMER")
            .setSourceState("SCHEDULE_DUE_CLAIMED")
            .setChangedAfterMs(10L)
            .setChangedBeforeMs(20L)
            .setLimit(50)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getEventsList()).hasSize(1);
    assertThat(ref.get().getEvents(0).getAutomationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(ref.get().getEvents(0).getGameSessionCommandId()).isEqualTo("command-1");
    assertThat(ref.get().getEvents(0).getTargetGameInstanceId()).isEqualTo("game-2");
    assertThat(ref.get().getEvents(0).getTargetRegionId()).isEqualTo("region-2");
    assertThat(ref.get().getEvents(0).getTargetRegionEpoch()).isEqualTo(17L);
    assertThat(ref.get().getEvents(0).getRemoteCoordinatorId())
        .isEqualTo("remote-coordinator:workItem:99#0");
    assertThat(ref.get().getEvents(0).getRemoteFollowupId())
        .isEqualTo("remote-followup:workItem:99#0");
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeGameInstanceId()).isEqualTo("game-2");
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeRegionId()).isEqualTo("region-live");
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeRegionEpoch()).isEqualTo(22L);
    assertThat(ref.get().getEvents(0).getIsTargetRuntimeScopeStale()).isTrue();
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimePlayableStateScope())
        .isEqualTo(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_ISOLATED);
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeWorldSlug()).isEqualTo("demo-next");
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeRealmSlug()).isEqualTo("staging");
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimePointerVersion()).isEqualTo("99");
    assertThat(ref.get().getEvents(0).getIsTargetRoutingBundleStale()).isTrue();
    assertThat(ref.get().getEvents(0).getGameplayCommandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(ref.get().getEvents(0).getGameplayCommandGameplayResult()).isEqualTo("SUCCESS");
    assertThat(ref.get().getEvents(0).getGameplayRemoteState()).isEqualTo("REMOTE_APPLIED");
    assertThat(ref.get().getEvents(0).getGameplayRemoteTargetCommandExecutionOutcome())
        .isEqualTo("APPLIED");
    assertThat(ref.get().getEvents(0).getGameplayRemoteTargetCommandGameplayResult())
        .isEqualTo("SUCCESS");
    assertThat(ref.get().getEvents(0).getWorldSlug()).isEqualTo("demo");
    assertThat(ref.get().getEvents(0).getRealmSlug()).isEqualTo("production");
    assertThat(ref.get().getEvents(0).getPointerVersion()).isEqualTo("17");
    assertThat(ref.get().getEvents(0).getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(ref.get().getEvents(0).getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(ref.get().getEvents(0).getSourceOrdinal()).isEqualTo(5000L);
    assertThat(ref.get().getEvents(0).getSourceDueAtMs()).isEqualTo(5000L);
    assertThat(ref.get().getEvents(0).getEmittedCommandText()).isEqualTo("LOOK AT old chest");
    assertThat(ref.get().getEvents(0).getHandoffOutcome()).isEqualTo("enqueued");
    assertThat(ref.get().getEvents(0).getPublication().getVersionId()).isEqualTo(17L);
  }

  @Test
  void collapsesPartialRoutingBundleWhenProjectingScriptHandoffEvents() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listHandoffEvents(
                "1", "", "", "", "", "game-2", "", 0L, "", "", "", "", "", "", "", "", "", "", "",
                "", "", 0L, 0L, 50))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.HandoffEventSummary(
                    "event-1",
                    "1",
                    "game-1",
                    "patch-1",
                    "script-1",
                    "",
                    "",
                    "99",
                    0,
                    "",
                    "",
                    "game-2",
                    "region-2",
                    17L,
                    "",
                    "",
                    "target-1",
                    "SHARED",
                    "demo",
                    "",
                    "17",
                    "",
                    "",
                    0L,
                    0L,
                    0L,
                    "LOOK",
                    "enqueued",
                    "game_session_accepted",
                    15L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-2"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-2")
                        .setRegionId("region-live")
                        .setRegionEpoch(22L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo-next")
                        .build())
                .build());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            new ScriptRuntimeProperties(),
            Mockito.mock(ScriptScheduleInstanceService.class),
            gameDesignClient(),
            gameSessionClient);
    AtomicReference<ListScriptHandoffEventsResponse> ref = new AtomicReference<>();

    service.listScriptHandoffEvents(
        ListScriptHandoffEventsRequest.newBuilder()
            .setTenantId("1")
            .setTargetGameInstanceId("game-2")
            .setLimit(50)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getEventsList()).hasSize(1);
    assertThat(ref.get().getEvents(0).getWorldSlug()).isBlank();
    assertThat(ref.get().getEvents(0).getRealmSlug()).isBlank();
    assertThat(ref.get().getEvents(0).getPointerVersion()).isBlank();
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeWorldSlug()).isBlank();
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimeRealmSlug()).isBlank();
    assertThat(ref.get().getEvents(0).getCurrentTargetRuntimePointerVersion()).isBlank();
    assertThat(ref.get().getEvents(0).getIsTargetRoutingBundleStale()).isFalse();
  }

  @Test
  void listsScriptDeadLettersFromWorkItemReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.listDeadLetters("1", "game-1", "patch-1", 25))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.DeadLetterSummary(
                    "99",
                    "1",
                    "game-1",
                    "region-1",
                    12L,
                    "entity-1",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "GAMEPLAY_EVENT",
                    "WORK_ITEM_PERSISTED",
                    0L,
                    0L,
                    0L,
                    "script-1",
                    "plugin-1",
                    "plugin-v1",
                    "onCommand",
                    "patch-1",
                    "event-1",
                    "DEAD_LETTERED",
                    "STALE_TIMELINE",
                    100L,
                    200L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        18L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(99L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED)
                        .setWorldSlug("demo-next")
                        .setRealmSlug("staging")
                        .setPointerVersion(99L)
                        .build())
                .build());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            new ScriptRuntimeProperties(),
            Mockito.mock(ScriptScheduleInstanceService.class),
            gameDesignClient(),
            gameSessionClient);
    AtomicReference<ListScriptDeadLettersResponse> ref = new AtomicReference<>();

    service.listScriptDeadLetters(
        ListScriptDeadLettersRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setLimit(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDeadLettersList()).hasSize(1);
    assertThat(ref.get().getDeadLetters(0).getWorkItemId()).isEqualTo("99");
    assertThat(ref.get().getDeadLetters(0).getWorldSlug()).isEqualTo("demo");
    assertThat(ref.get().getDeadLetters(0).getRealmSlug()).isEqualTo("production");
    assertThat(ref.get().getDeadLetters(0).getPointerVersion()).isEqualTo("17");
    assertThat(ref.get().getDeadLetters(0).getPublication().getVersionId()).isEqualTo(18L);
    assertThat(ref.get().getDeadLetters(0).getSourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(ref.get().getDeadLetters(0).getSourceState()).isEqualTo("WORK_ITEM_PERSISTED");
    assertThat(ref.get().getDeadLetters(0).getPluginId()).isEqualTo("plugin-1");
    assertThat(ref.get().getDeadLetters(0).getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeGameInstanceId()).isEqualTo("game-1");
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeRegionId()).isEqualTo("region-1");
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeRegionEpoch()).isEqualTo(99L);
    assertThat(ref.get().getDeadLetters(0).getIsRuntimeScopeStale()).isTrue();
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimePlayableStateScope())
        .isEqualTo(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_ISOLATED);
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeWorldSlug()).isEqualTo("demo-next");
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeRealmSlug()).isEqualTo("staging");
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimePointerVersion()).isEqualTo("99");
    assertThat(ref.get().getDeadLetters(0).getIsRoutingBundleStale()).isTrue();
    assertThat(ref.get().getDeadLetters(0).getReason()).isEqualTo("STALE_TIMELINE");
  }

  @Test
  void collapsesPartialRoutingBundleWhenProjectingScriptDeadLetters() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.listDeadLetters("1", "game-1", "patch-1", 25))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.DeadLetterSummary(
                    "99",
                    "1",
                    "game-1",
                    "region-1",
                    12L,
                    "entity-1",
                    "SHARED",
                    "demo",
                    "",
                    "17",
                    "GAMEPLAY_EVENT",
                    "WORK_ITEM_PERSISTED",
                    0L,
                    0L,
                    0L,
                    "script-1",
                    "",
                    "",
                    "onCommand",
                    "patch-1",
                    "event-1",
                    "DEAD_LETTERED",
                    "STALE_TIMELINE",
                    100L,
                    200L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        18L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(99L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo-next")
                        .build())
                .build());
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            new ScriptRuntimeProperties(),
            Mockito.mock(ScriptScheduleInstanceService.class),
            gameDesignClient(),
            gameSessionClient);
    AtomicReference<ListScriptDeadLettersResponse> ref = new AtomicReference<>();

    service.listScriptDeadLetters(
        ListScriptDeadLettersRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setScriptPatchVersion("patch-1")
            .setLimit(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDeadLettersList()).hasSize(1);
    assertThat(ref.get().getDeadLetters(0).getWorldSlug()).isBlank();
    assertThat(ref.get().getDeadLetters(0).getRealmSlug()).isBlank();
    assertThat(ref.get().getDeadLetters(0).getPointerVersion()).isBlank();
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeWorldSlug()).isBlank();
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimeRealmSlug()).isBlank();
    assertThat(ref.get().getDeadLetters(0).getCurrentRuntimePointerVersion()).isBlank();
    assertThat(ref.get().getDeadLetters(0).getIsRoutingBundleStale()).isFalse();
  }

  @Test
  void replaysDeadLettersThroughWorkItemService() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.replayDeadLetters(Mockito.any()))
        .thenReturn(new ScriptWorkItemService.ReplayResult(2L, 1L));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<ReplayDeadLetteredWorkItemsResponse> ref = new AtomicReference<>();

    service.replayDeadLetteredWorkItems(
        ReplayDeadLetteredWorkItemsRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .addWorkItemIds("77")
            .setLimit(10)
            .setReason("retry")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getReplayedCount()).isEqualTo(2L);
    assertThat(ref.get().getRejectedCount()).isEqualTo(1L);
  }

  @Test
  void getsPluginStatusFromRuntimeRegistry() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    Mockito.when(pluginRuntimeStateService.getStatus("1", "game-1", "plugin-1"))
        .thenReturn(
            Optional.of(
                new PluginRuntimeStateService.PluginRuntimeStatus(
                    "plugin-v1",
                    "",
                    "region-7",
                    12L,
                    PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    55L,
                    "req-1",
                    "operator-1",
                    System.currentTimeMillis(),
                    new PluginRuntimeStateService.PluginPublicationLink(
                        "plugin-v1",
                        17L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        "ready_for_activation",
                        44L,
                        "",
                        ""),
                    null)));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<GetPluginStatusResponse> ref = new AtomicReference<>();

    service.getPluginStatus(
        GetPluginStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPluginId("plugin-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getActivePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(ref.get().getRuntimeRegionId()).isEqualTo("region-7");
    assertThat(ref.get().getRuntimeRegionEpoch()).isEqualTo(12L);
    assertThat(ref.get().getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED);
    assertThat(ref.get().getLastChangedAtMs()).isEqualTo(55L);
    assertThat(ref.get().getControlPlaneRequestId()).isEqualTo("req-1");
    assertThat(ref.get().getActorPrincipal()).isEqualTo("operator-1");
    assertThat(ref.get().getLastPolicyCheckedAtMs()).isPositive();
    assertThat(ref.get().getPolicyCheckStale()).isFalse();
    assertThat(ref.get().getActivePublication().getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(ref.get().getActivePublication().getPublicationId()).isEqualTo(17L);
    assertThat(ref.get().getActivePublication().getStatusReason())
        .isEqualTo("ready_for_activation");
  }

  @Test
  void listsPluginRuntimeEventsFromRuntimeReadModel() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    Mockito.when(
            pluginRuntimeStateService.listEvents(
                "1",
                "game-1",
                "plugin-1",
                PluginState.PLUGIN_STATE_ENABLED,
                "plugin-v1",
                10L,
                20L,
                50))
        .thenReturn(
            List.of(
                new PluginRuntimeStateService.PluginRuntimeEventSummary(
                    "event-1",
                    "1",
                    "game-1",
                    "region-7",
                    12L,
                    "plugin-1",
                    "plugin-v0",
                    "plugin-v1",
                    PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    "req-1",
                    "operator-1",
                    15L,
                    new PluginRuntimeStateService.PluginPublicationLink(
                        "plugin-v0",
                        16L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_SUPERSEDED,
                        "superseded",
                        14L,
                        "",
                        ""),
                    new PluginRuntimeStateService.PluginPublicationLink(
                        "plugin-v1",
                        17L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        "ready_for_activation",
                        15L,
                        "",
                        ""))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<ListPluginRuntimeEventsResponse> ref = new AtomicReference<>();

    service.listPluginRuntimeEvents(
        ListPluginRuntimeEventsRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPluginId("plugin-1")
            .setPluginState(PluginState.PLUGIN_STATE_ENABLED)
            .setActivePluginVersionId("plugin-v1")
            .setChangedAfterMs(10L)
            .setChangedBeforeMs(20L)
            .setLimit(50)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getEventsList()).hasSize(1);
    assertThat(ref.get().getEvents(0).getEventId()).isEqualTo("event-1");
    assertThat(ref.get().getEvents(0).getRuntimeRegionId()).isEqualTo("region-7");
    assertThat(ref.get().getEvents(0).getRuntimeRegionEpoch()).isEqualTo(12L);
    assertThat(ref.get().getEvents(0).getPreviousPluginVersionId()).isEqualTo("plugin-v0");
    assertThat(ref.get().getEvents(0).getActivePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(ref.get().getEvents(0).getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED);
    assertThat(ref.get().getEvents(0).getPreviousPublication().getPublicationId()).isEqualTo(16L);
    assertThat(ref.get().getEvents(0).getActivePublication().getPublicationId()).isEqualTo(17L);
  }

  @Test
  void getsFreshPluginPolicyConvergenceFromRuntimeRegistry() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    long evaluatedAtMs = System.currentTimeMillis();
    Mockito.when(pluginRuntimeStateService.getPluginPolicyConvergence("1", "game-1", 25))
        .thenReturn(
            new PluginRuntimeStateService.PluginPolicyConvergence(
                3,
                1,
                false,
                evaluatedAtMs,
                List.of(
                    new PluginRuntimeStateService.PluginPolicyViolation(
                        "game-1",
                        "region-7",
                        12L,
                        "plugin-1",
                        "plugin-v1",
                        "signer_revoked",
                        1234L,
                        new PluginRuntimeStateService.PluginPublicationLink(
                            "plugin-v1",
                            17L,
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED,
                            "signer_revoked",
                            1230L,
                            "",
                            "")))));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<GetPluginPolicyConvergenceResponse> ref = new AtomicReference<>();

    service.getPluginPolicyConvergence(
        GetPluginPolicyConvergenceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setMaxResults(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getInspectedCount()).isEqualTo(3);
    assertThat(ref.get().getFailClosedCount()).isEqualTo(1);
    assertThat(ref.get().getConverged()).isFalse();
    assertThat(ref.get().getEvaluatedAtMs()).isEqualTo(evaluatedAtMs);
    assertThat(ref.get().getIsStale()).isFalse();
    assertThat(ref.get().getViolationsList()).hasSize(1);
    assertThat(ref.get().getViolations(0).getRuntimeRegionId()).isEqualTo("region-7");
    assertThat(ref.get().getViolations(0).getRuntimeRegionEpoch()).isEqualTo(12L);
    assertThat(ref.get().getViolations(0).getPluginId()).isEqualTo("plugin-1");
    assertThat(ref.get().getViolations(0).getActivePublication().getPublicationId()).isEqualTo(17L);
  }

  @Test
  void marksPluginPolicyConvergenceStaleWhenEvaluationAgesOut() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    long evaluatedAtMs = System.currentTimeMillis() - 10_000L;
    Mockito.when(pluginRuntimeStateService.getPluginPolicyConvergence("1", "game-1", 25))
        .thenReturn(
            new PluginRuntimeStateService.PluginPolicyConvergence(
                2, 0, true, evaluatedAtMs, List.of()));
    ScriptRuntimeProperties runtimeProperties = new ScriptRuntimeProperties();
    runtimeProperties.setPluginPolicyStaleThresholdSeconds(1L);
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            runtimeProperties);
    AtomicReference<GetPluginPolicyConvergenceResponse> ref = new AtomicReference<>();

    service.getPluginPolicyConvergence(
        GetPluginPolicyConvergenceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setMaxResults(25)
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getEvaluatedAtMs()).isEqualTo(evaluatedAtMs);
    assertThat(ref.get().getIsStale()).isTrue();
  }

  @Test
  void setsPluginActiveVersionThroughRuntimeRegistry() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    Mockito.when(pluginRuntimeStateService.setActiveVersion(Mockito.any()))
        .thenReturn(
            new PluginRuntimeStateService.ActivationResult("plugin-v1", "plugin-v2", "req-1"));
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<SetPluginActiveVersionResponse> ref = new AtomicReference<>();

    service.setPluginActiveVersion(
        SetPluginActiveVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPluginId("plugin-1")
            .setTargetPluginVersionId("plugin-v2")
            .setControlPlaneRequestId("req-1")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getPreviousPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(ref.get().getActivePluginVersionId()).isEqualTo("plugin-v2");
    assertThat(ref.get().getControlPlaneRequestId()).isEqualTo("req-1");
  }

  @Test
  void disablesAndDrainsPluginThroughRuntimeRegistry() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    Mockito.when(pluginRuntimeStateService.disable(Mockito.any())).thenReturn(true);
    Mockito.when(pluginRuntimeStateService.drain(Mockito.any())).thenReturn(true);
    AutomationScriptingControlPlaneGrpcService service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class));
    AtomicReference<DisablePluginResponse> disableRef = new AtomicReference<>();
    AtomicReference<DrainPluginResponse> drainRef = new AtomicReference<>();

    service.disablePlugin(
        DisablePluginRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPluginId("plugin-1")
            .build(),
        observer(disableRef));
    service.drainPlugin(
        DrainPluginRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPluginId("plugin-1")
            .build(),
        observer(drainRef));

    assertThat(disableRef.get().hasError()).isFalse();
    assertThat(disableRef.get().getSuccess()).isTrue();
    assertThat(drainRef.get().hasError()).isFalse();
    assertThat(drainRef.get().getSuccess()).isTrue();
  }

  private static <T> StreamObserver<T> observer(AtomicReference<T> ref) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        ref.set(value);
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {}
    };
  }
}
