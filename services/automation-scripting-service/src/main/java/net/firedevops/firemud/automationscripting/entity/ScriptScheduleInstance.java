package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptScheduleInstance {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String scriptPatchVersion;
  private String scriptId;
  private String playableStateScope = "";
  private String worldSlug = "";
  private String realmSlug = "";
  private String pointerVersion = "";
  private String pluginId = "";
  private String pluginVersionId = "";
  private String eventType;
  private String scheduleDefinitionId;
  private String scheduleKind;
  private long cadenceValue;
  private String cadenceUnit;
  private String priorityTag = "normal";
  private String targetScopeType = "";
  private String targetScopeId = "";
  private int bindingPriority;
  private boolean requiresExclusiveEvent;
  private String materializationStatus;

  private Instant nextDueAt;

  private Long nextDueTickId;
  private String runtimeRegionId = "";

  private Long runtimeRegionEpoch;

  private Long lastObservedTickId;

  private Instant lastRuntimeProgressObservedAt;
  private String observedRuntimeVersionId = "";
  private String lastObservedControlPlaneRequestId = "";
  private String scheduleMetadataJson;
  private String scheduleSemanticsHash;
  private Instant pinObservedAt = Instant.EPOCH;
  private Instant materializedAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private int rowVersion;
}
