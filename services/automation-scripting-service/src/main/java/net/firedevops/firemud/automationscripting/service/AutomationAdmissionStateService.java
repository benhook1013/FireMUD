package net.firedevops.firemud.automationscripting.service;

import java.util.Optional;

public interface AutomationAdmissionStateService {
  String OUTCOME_APPLIED = "APPLIED";
  String OUTCOME_ALREADY_APPLIED = "ALREADY_APPLIED";
  String OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE = "ACKNOWLEDGEMENT_UNAVAILABLE";

  AdmissionStateSummary getState(String tenantId, String gameInstanceId, String regionId);

  Optional<AdmissionStateSummary> findState(
      String tenantId, String gameInstanceId, String regionId);

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
      long updatedAtMs,
      String targetMode,
      String outcome,
      String requestFingerprint,
      long acknowledgedAtMs) {
    public AdmissionStateSummary(
        String tenantId,
        String gameInstanceId,
        String regionId,
        String mode,
        long admissionEpoch,
        String controlPlaneRequestId,
        String actorPrincipal,
        String reason,
        long updatedAtMs) {
      this(
          tenantId,
          gameInstanceId,
          regionId,
          mode,
          admissionEpoch,
          controlPlaneRequestId,
          actorPrincipal,
          reason,
          updatedAtMs,
          "",
          OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE,
          "",
          0L);
    }
  }

  record SetAdmissionModeCommand(
      String tenantId,
      String gameInstanceId,
      String regionId,
      String mode,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}
}
