package net.firedevops.firemud.automationscripting.service;

import java.util.Optional;

public interface AutomationAdmissionStateService {
  String OUTCOME_APPLIED = "APPLIED";
  String OUTCOME_ALREADY_APPLIED = "ALREADY_APPLIED";
  String OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE = "ACKNOWLEDGEMENT_UNAVAILABLE";
  String OUTCOME_NOT_FOUND = "NOT_FOUND";

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
    /**
     * Builds a diagnostic summary when the durable acknowledgement cannot be verified.
     *
     * <p>This constructor intentionally has no request identity: callers must use the canonical
     * constructor above when presenting a verified control-plane acknowledgement.
     */
    public AdmissionStateSummary(
        String tenantId,
        String gameInstanceId,
        String regionId,
        String mode,
        long admissionEpoch,
        String actorPrincipal,
        String reason,
        long updatedAtMs) {
      this(
          tenantId,
          gameInstanceId,
          regionId,
          mode,
          admissionEpoch,
          "",
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
