package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;

@Data
public class ScriptEventIngressAudit {
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
  private String pluginId;
  private String pluginVersionId;
  private String eventType;
  private String eventSchemaVersion;
  private String quotaClass = ScriptQuotaClasses.STANDARD_RUNTIME;
  private String scriptPatchVersion;
  private String scriptEventId;
  private String sourceService;
  private String triggerMode;
  private String sourceKind = "";
  private String sourceState = "";

  private Long sourceOrdinal;

  private Long sourceDueTickId;

  private Long sourceDueAtMs;
  private boolean dryRun;
  private String readSnapshotToken;
  private String payloadJson;
  private boolean admitted;
  private String admissionOutcome;
  private String admissionReason;
  private int resolvedHandlerCount;
  private Instant createdAt = Instant.now();
  private int rowVersion;
}
