package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.Test;

class ScriptWorkItemFenceEvaluationSupportTest {
  @Test
  void acceptsRuntimeStateWhenCapturedPinOwnerRequestMatches() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-1")))
        .isNull();
  }

  @Test
  void rejectsRuntimeStateWhenCapturedPinOwnerRequestIsMissing() {
    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                runtimeWorkItem(), runtimeState("pin-request-1")))
        .isEqualTo("script_pin_owner_request_unavailable");
  }

  @Test
  void rejectsRuntimeStateWhenAuthoritativePinOwnerRequestIsMissing() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(workItem, runtimeState(" ")))
        .isEqualTo("script_pin_owner_request_unavailable");
  }

  @Test
  void rejectsRuntimeStateWhenCapturedPinOwnerRequestDiffers() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-2")))
        .isEqualTo("script_pin_owner_request_mismatch");
  }

  @Test
  void acceptsFirstPartyWorkItemWithoutPluginFence() {
    ScriptWorkItem workItem = runtimeWorkItem();

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem)).isNull();
  }

  @Test
  void rejectsUnboundWorkItemWithCapturedPluginLifecycleEvidence() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginActivationEpoch(1L);
    workItem.setLifecycleRevision(1L);

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem))
        .isEqualTo("plugin_binding_mismatch");
  }

  @Test
  void rejectsPartialPluginBindingBeforeCurrentAuthorityLookup() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem))
        .isEqualTo("plugin_binding_mismatch");
  }

  @Test
  void rejectsMissingCapturedLifecycleEvidenceBeforeCurrentPluginStatus() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem))
        .isEqualTo("plugin_lifecycle_evidence_unavailable");
  }

  @Test
  void usesBindingMismatchForCurrentLifecycleRevisionMismatch() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_ENABLED, 4L, 9L))
        .isEqualTo("plugin_binding_mismatch");
  }

  @Test
  void usesActivationEpochMismatchForCurrentActivationEpochMismatch() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_ENABLED, 5L, 8L))
        .isEqualTo("plugin_activation_epoch_mismatch");
  }

  @Test
  void acceptsCurrentEnabledPluginFenceWhenAllEvidenceMatches() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, " plugin-v1 ", PluginState.PLUGIN_STATE_ENABLED, 4L, 8L))
        .isNull();
  }

  @Test
  void rejectsCapturedPluginVersionWhenAnotherVersionIsCurrentlyEnabled() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v2", PluginState.PLUGIN_STATE_ENABLED, 4L, 8L))
        .isEqualTo("plugin_version_mismatch");
  }

  @Test
  void rejectsCurrentDisabledPluginFence() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_DISABLED, 4L, 8L))
        .isEqualTo("plugin_disabled");
  }

  @Test
  void rejectsCurrentDrainingPluginFenceAsNonExecutable() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_DRAINING, 4L, 8L))
        .isEqualTo("plugin_disabled");
  }

  @Test
  void rejectsPredecessorEnabledRevisionWhenCurrentPluginIsDraining() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_DRAINING, 4L, 9L))
        .isEqualTo("plugin_disabled");
  }

  @Test
  void rejectsUnspecifiedCurrentPluginStateWithoutPluginVersion() {
    ScriptWorkItem workItem = runtimeWorkItem();

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "", PluginState.PLUGIN_STATE_UNSPECIFIED, 0L, 0L))
        .isEqualTo("plugin_disabled");
  }

  private static ScriptWorkItem runtimeWorkItem() {
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setTenantId("tenant-1");
    workItem.setGameInstanceId("game-1");
    workItem.setRegionId("region-1");
    workItem.setRegionEpoch(3L);
    workItem.setScriptPatchVersion("patch-1");
    workItem.setScriptPinEpoch(2L);
    return workItem;
  }

  private static GetGameInstanceRuntimeStateResponse runtimeState(String pinRequestId) {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setRuntimeState(
            GameInstanceRuntimeState.newBuilder()
                .setTenantId("tenant-1")
                .setGameInstanceId("game-1")
                .setPinnedScriptPatchVersion("patch-1")
                .setScriptPinEpoch(2L)
                .setScriptPatchPinnedControlPlaneRequestId(pinRequestId)
                .setRegionId("region-1")
                .setRegionEpoch(3L)
                .build())
        .build();
  }
}
