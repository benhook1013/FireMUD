package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.PluginState;

/** Shared ordering and vocabulary for the two work-item fence evaluation paths. */
final class ScriptWorkItemFenceEvaluationSupport {
  private ScriptWorkItemFenceEvaluationSupport() {}

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
