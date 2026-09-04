package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;

@Data
public class ScriptWorkItem {
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
  private String bindingId;
  private long pluginActivationEpoch;
  private long lifecycleRevision;
  private long failureGeneration = 1L;

  /** First time this item observed a transient authority-unavailable fence result. */
  private Instant authorityUnavailableSince;

  /** Number of transient authority-unavailable outcomes in the current execution attempt. */
  private int authorityUnavailableCount;

  /** Earliest instant at which the durable scanner may claim this item again. */
  private Instant nextEligibleAt;

  private String eventType;
  private String eventSchemaVersion;
  private String quotaClass = ScriptQuotaClasses.STANDARD_RUNTIME;
  private String scriptPatchVersion;
  private long scriptPinEpoch;
  private String scriptEventId;
  private boolean dryRun;
  private String sourceService;
  private String triggerMode;
  private String sourceKind = "";
  private String sourceState = "";

  private Long sourceOrdinal;

  private Long sourceDueTickId;

  private Long sourceDueAtMs;
  private String priorityTag = "normal";
  private String readSnapshotToken;
  private String payloadJson;
  private long admissionEpoch = 1L;
  private String status = "PENDING_EVALUATION";
  private String cancelReason;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private int rowVersion;
}
