package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

/** Immutable result of one Automation admission-mode request. */
@Data
public class AutomationAdmissionRequestHistory {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String regionId;
  private String mode;
  private String controlPlaneRequestId;
  private String requestFingerprint;
  private long admissionEpoch;
  private String outcome;
  private String actorPrincipal;
  private String reason;
  private Instant createdAt;
}
