package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class ScriptPatchReadinessProjection {
  private Long id;
  private String tenantId;
  private String scriptPatchVersion;
  private String readinessStatus = "PENDING_VALIDATION";
  private String statusReason = "pending_validation";
  private String supersededByScriptPatchVersion = "";
  private List<String> scriptSetManifest;
  private Long readinessGeneration;
  private boolean databaseDownstreamReconciled;
  private Instant lastChangedAt = Instant.EPOCH;
  private int rowVersion;
}
