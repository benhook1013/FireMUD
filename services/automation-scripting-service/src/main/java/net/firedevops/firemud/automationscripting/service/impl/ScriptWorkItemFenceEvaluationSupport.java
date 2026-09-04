package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Objects;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;

/** Shared ordering and vocabulary for the two work-item fence evaluation paths. */
final class ScriptWorkItemFenceEvaluationSupport {
  private ScriptWorkItemFenceEvaluationSupport() {}

  static String validateRuntimeIdentity(ScriptWorkItem workItem) {
    if (workItem.getTenantId() == null
        || workItem.getTenantId().isBlank()
        || workItem.getGameInstanceId() == null
        || workItem.getGameInstanceId().isBlank()
        || workItem.getRegionId() == null
        || workItem.getRegionId().isBlank()
        || workItem.getRegionEpoch() == null
        || workItem.getRegionEpoch() <= 0) {
      return "runtime_scope_missing";
    }
    if (workItem.getScriptPinEpoch() <= 0) {
      return "script_pin_epoch_unavailable";
    }
    return null;
  }

  static String validateRuntimeState(
      ScriptWorkItem workItem, GetGameInstanceRuntimeStateResponse runtime) {
    if (runtime == null
        || (runtime.hasError() && !runtime.getError().getCode().isBlank())
        || !runtime.hasRuntimeState()) {
      return "script_pin_authority_unavailable";
    }
    var state = runtime.getRuntimeState();
    if (!Objects.equals(workItem.getTenantId(), state.getTenantId())
        || !Objects.equals(workItem.getGameInstanceId(), state.getGameInstanceId())) {
      return "runtime_scope_changed";
    }
    if (!Objects.equals(workItem.getScriptPatchVersion(), state.getPinnedScriptPatchVersion())) {
      return "script_patch_version_mismatch";
    }
    if (workItem.getScriptPinEpoch() != state.getScriptPinEpoch()) {
      return "script_pin_epoch_mismatch";
    }
    if (!Objects.equals(workItem.getRegionId(), state.getRegionId())
        || !workItem.getRegionEpoch().equals(state.getRegionEpoch())) {
      return "runtime_scope_changed";
    }
    return null;
  }

  /** Validates captured plugin evidence before consulting current plugin authority. */
  static String validateCapturedPluginFence(ScriptWorkItem workItem) {
    String pluginId = normalize(workItem.getPluginId());
    String pluginVersionId = normalize(workItem.getPluginVersionId());
    if (pluginId.isBlank() && pluginVersionId.isBlank()) {
      return null;
    }
    if (pluginId.isBlank() || pluginVersionId.isBlank()) {
      return "plugin_binding_mismatch";
    }
    if (workItem.getPluginActivationEpoch() <= 0 || workItem.getLifecycleRevision() <= 0) {
      return "plugin_lifecycle_evidence_unavailable";
    }
    return null;
  }

  /** Validates current plugin authority after captured evidence has passed. */
  static String validateCurrentPluginFence(
      ScriptWorkItem workItem,
      String activePluginVersionId,
      PluginState pluginState,
      long pluginActivationEpoch,
      long lifecycleRevision) {
    if (pluginState != PluginState.PLUGIN_STATE_ENABLED) {
      return "plugin_disabled";
    }
    if (!normalize(workItem.getPluginVersionId()).equals(normalize(activePluginVersionId))) {
      return "plugin_version_mismatch";
    }
    if (workItem.getPluginActivationEpoch() != pluginActivationEpoch) {
      return "plugin_activation_epoch_mismatch";
    }
    if (workItem.getLifecycleRevision() != lifecycleRevision) {
      return "plugin_binding_mismatch";
    }
    return null;
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
