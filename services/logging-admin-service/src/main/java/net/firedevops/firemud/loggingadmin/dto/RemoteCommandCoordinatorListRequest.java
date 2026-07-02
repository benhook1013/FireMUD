package net.firedevops.firemud.loggingadmin.dto;

public class RemoteCommandCoordinatorListRequest {
  private String originGameInstanceId;
  private String originRegionId;
  private String targetGameInstanceId;
  private String targetRegionId;
  private String state;
  private String followupId;
  private String commandId;
  private Integer limit;

  public String getOriginGameInstanceId() {
    return originGameInstanceId;
  }

  public void setOriginGameInstanceId(String originGameInstanceId) {
    this.originGameInstanceId = originGameInstanceId;
  }

  public String getOriginRegionId() {
    return originRegionId;
  }

  public void setOriginRegionId(String originRegionId) {
    this.originRegionId = originRegionId;
  }

  public String getTargetGameInstanceId() {
    return targetGameInstanceId;
  }

  public void setTargetGameInstanceId(String targetGameInstanceId) {
    this.targetGameInstanceId = targetGameInstanceId;
  }

  public String getTargetRegionId() {
    return targetRegionId;
  }

  public void setTargetRegionId(String targetRegionId) {
    this.targetRegionId = targetRegionId;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getFollowupId() {
    return followupId;
  }

  public void setFollowupId(String followupId) {
    this.followupId = followupId;
  }

  public String getCommandId() {
    return commandId;
  }

  public void setCommandId(String commandId) {
    this.commandId = commandId;
  }

  public Integer getLimit() {
    return limit;
  }

  public void setLimit(Integer limit) {
    this.limit = limit;
  }
}
