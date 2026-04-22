package net.firedevops.firemud.automationscripting.service;

public interface ScriptWorkItemService {
  long cancelPendingForPatch(CancelPendingForPatchCommand command);

  record CancelPendingForPatchCommand(
      String tenantId,
      String scriptPatchVersion,
      String gameInstanceId,
      String regionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}
}
