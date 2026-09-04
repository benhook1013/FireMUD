package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptHandoffEvent {
  private Long id;
  private String eventId;
  private String tenantId;
  private String gameInstanceId;
  private String scriptPatchVersion;
  private long scriptPinEpoch;
  private String scriptId;
  private String pluginId;
  private String pluginVersionId;
  private Long workItemId;
  private int commandOrdinal;
  private String automationDispatchId;
  private String gameSessionCommandId;
  private String targetGameInstanceId = "";
  private String targetRegionId = "";
  private long targetRegionEpoch;
  private String remoteCoordinatorId;
  private String remoteFollowupId;
  private String targetEntityId;
  private String playableStateScope = "";
  private String worldSlug = "";
  private String realmSlug = "";
  private String pointerVersion = "";
  private String sourceKind = "";
  private String sourceState = "";

  private Long sourceOrdinal;

  private Long sourceDueTickId;

  private Long sourceDueAtMs;
  private String emittedCommandText;
  private String handoffOutcome;
  private String handoffReason;
  private Instant observedAt = Instant.EPOCH;
  private int rowVersion;
}
