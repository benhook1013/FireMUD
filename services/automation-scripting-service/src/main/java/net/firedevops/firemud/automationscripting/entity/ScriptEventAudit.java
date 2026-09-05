package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptEventAudit {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String regionId;
  private Long regionEpoch;
  private String entityId;
  private String playableStateScope = "";
  private String worldSlug = "";
  private String realmSlug = "";
  private String pointerVersion = "";
  private String scriptId;
  private String bindingId = "";
  private String pluginId = "";
  private String pluginVersionId = "";
  private String targetScopeType = "";
  private String targetScopeId = "";
  private String eventType;
  private String eventSchemaVersion;
  private String scriptPatchVersion;

  /** Null for tenant-readiness onLoad audits, positive for instance-scoped execution. */
  private Long scriptPinEpoch;

  private String scriptPinControlPlaneRequestId;

  private String scriptEventId;
  private boolean dryRun;
  private String sourceService;
  private String triggerMode;
  private String sourceKind = "";
  private String sourceState = "";

  private Long sourceOrdinal;

  private Long sourceDueTickId;

  private Long sourceDueAtMs;
  private Long workItemId;
  private String finalStage;
  private String finalOutcome;
  private String finalReason;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private int rowVersion;
}
