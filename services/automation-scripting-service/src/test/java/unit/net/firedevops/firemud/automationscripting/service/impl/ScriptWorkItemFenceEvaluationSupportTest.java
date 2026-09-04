package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import org.junit.jupiter.api.Test;

class ScriptWorkItemFenceEvaluationSupportTest {
  @Test
  void acceptsFirstPartyWorkItemWithoutPluginFence() {
    ScriptWorkItem workItem = runtimeWorkItem();

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem)).isNull();
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
}
