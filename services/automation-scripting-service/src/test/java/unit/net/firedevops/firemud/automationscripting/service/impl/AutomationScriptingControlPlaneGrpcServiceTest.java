package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.DisablePluginRequest;
import net.firedevops.firemud.automationscripting.v1.DisablePluginResponse;
import net.firedevops.firemud.automationscripting.v1.DrainPluginRequest;
import net.firedevops.firemud.automationscripting.v1.DrainPluginResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsRequest;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionRequest;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationScriptingControlPlaneGrpcServiceTest {
  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void getsBuiltInEventDefinition() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class));
    AtomicReference<GetScriptEventDefinitionResponse> ref = new AtomicReference<>();

    service.getScriptEventDefinition(
        GetScriptEventDefinitionRequest.newBuilder().setEventType("onCommand").build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDefinition().getEventType()).isEqualTo("onCommand");
    assertThat(ref.get().getDefinition().getSnapshotAuthority())
        .isEqualTo("PRODUCER_SUPPLIED_TOKEN");
  }

  @Test
  void listsBuiltInEventDefinitionsByOwner() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(PluginRuntimeStateService.class));
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
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class));
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
                    123L)));
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class));
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
                    15L)));
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class));
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
                    "script-1",
                    "onCommand",
                    "patch-1",
                    "event-1",
                    "DEAD_LETTERED",
                    "STALE_TIMELINE",
                    100L,
                    200L)));
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class));
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
    assertThat(ref.get().getDeadLetters(0).getReason()).isEqualTo("STALE_TIMELINE");
  }

  @Test
  void replaysDeadLettersThroughWorkItemService() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.replayDeadLetters(Mockito.any()))
        .thenReturn(new ScriptWorkItemService.ReplayResult(2L, 1L));
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            workItemService,
            Mockito.mock(PluginRuntimeStateService.class));
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
                    PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    55L)));
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService);
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
    assertThat(ref.get().getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED);
    assertThat(ref.get().getLastChangedAtMs()).isEqualTo(55L);
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
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService);
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
        new AutomationScriptingControlPlaneGrpcService(
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(ScriptWorkItemService.class),
            pluginRuntimeStateService);
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
