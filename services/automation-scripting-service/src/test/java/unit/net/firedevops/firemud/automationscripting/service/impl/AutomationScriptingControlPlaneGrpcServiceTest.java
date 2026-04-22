package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
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
            new BuiltInScriptEventRegistryService(), Mockito.mock(ScriptWorkItemService.class));
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
            new BuiltInScriptEventRegistryService(), Mockito.mock(ScriptWorkItemService.class));
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
            new BuiltInScriptEventRegistryService(), workItemService);
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
            new BuiltInScriptEventRegistryService(), workItemService);
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
            new BuiltInScriptEventRegistryService(), workItemService);
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
