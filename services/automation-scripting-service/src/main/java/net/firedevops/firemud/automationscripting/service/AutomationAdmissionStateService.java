package net.firedevops.firemud.automationscripting.service;

public interface AutomationAdmissionStateService {
  AdmissionStateSummary getState(String tenantId, String gameInstanceId, String regionId);

  AdmissionStateSummary setMode(SetAdmissionModeCommand command);

  record AdmissionStateSummary(
      String tenantId,
      String gameInstanceId,
      String regionId,
      String mode,
      long admissionEpoch,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason,
      long updatedAtMs) {}

  record SetAdmissionModeCommand(
      String tenantId,
      String gameInstanceId,
      String regionId,
      String mode,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}
}
