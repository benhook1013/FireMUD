package net.firedevops.firemud.automationscripting.service;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.PluginState;

public interface PluginRuntimeStateService {
  Optional<PluginRuntimeStatus> getStatus(String tenantId, String gameInstanceId, String pluginId);

  ActivationResult setActiveVersion(ActivationCommand command);

  boolean disable(PluginStateCommand command);

  boolean drain(PluginStateCommand command);

  record ActivationCommand(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      String targetPluginVersionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}

  record PluginStateCommand(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}

  record ActivationResult(
      String previousPluginVersionId, String activePluginVersionId, String controlPlaneRequestId) {}

  record PluginRuntimeStatus(
      String activePluginVersionId,
      String pendingPluginVersionId,
      PluginState pluginState,
      String statusReason,
      long lastChangedAtMs,
      String controlPlaneRequestId,
      String actorPrincipal) {}
}
