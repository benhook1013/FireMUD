package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptPatchReadinessProjection {
  private Long id;
  private String tenantId;
  private String scriptPatchVersion;
  private String readinessStatus = "PENDING_VALIDATION";
  private String statusReason = "pending_validation";
  private String supersededByScriptPatchVersion = "";
  private Instant lastChangedAt = Instant.EPOCH;
  private int rowVersion;
}
