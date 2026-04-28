package net.firedevops.firemud.automationscripting.service;

public interface PluginActivationPreflightService {
  void validateActivation(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId);
}
