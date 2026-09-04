package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class RemoteCommandCoordinator {
  private Long id;
  private String coordinatorId;
  private Long tenantId;
  private String commandId;
  private String followupId;
  private Long originGameInstanceId;
  private String originRegionId;
  private long originRegionEpoch;
  private Long targetGameInstanceId;
  private String targetRegionId;
  private long targetRegionEpoch;
  private long targetDueTickId;
  private long originDeadlineRegionEpoch;
  private long originDeadlineTickId;
  private String state;
  private String lateResultPolicy;
  private String executionOutcome;
  private String gameplayResult;
  private String playableStateScope;
  private String worldSlug;
  private String realmSlug;
  private Long pointerVersion;
  private String scriptPatchVersion;
  private String pluginId;
  private String pluginVersionId;
  private String automationDispatchId;
  private String automationWorkItemId;
  private String scriptId;
  private Instant updatedAt;
}
