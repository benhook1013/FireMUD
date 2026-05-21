package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class RemoteFollowupResult {
  private Long id;
  private String resultId;
  private Long tenantId;
  private String coordinatorId;
  private String followupId;
  private Long originGameInstanceId;
  private String originRegionId;
  private long originRegionEpoch;
  private Long targetGameInstanceId;
  private String targetRegionId;
  private long targetRegionEpoch;
  private String outcome;
  private String resultPayloadJson;
  private String resultCommandId;
  private String resultErrorCode;
  private String resultMessage;
  private String playableStateScope;
  private String worldSlug;
  private String realmSlug;
  private Long pointerVersion;
  private String scriptPatchVersion;
  private String pluginId;
  private String pluginVersionId;
  private String commandId;
  private String automationDispatchId;
  private String automationWorkItemId;
  private String scriptId;
  private Instant observedAt;
}
