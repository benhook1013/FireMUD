package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class AutomationAdmissionState {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String regionId;
  private String mode = "NORMAL";
  private long admissionEpoch = 1L;
  private String controlPlaneRequestId;
  private String controlPlaneRequestFingerprint = "";
  private String actorPrincipal;
  private String reason;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private int rowVersion;
}
