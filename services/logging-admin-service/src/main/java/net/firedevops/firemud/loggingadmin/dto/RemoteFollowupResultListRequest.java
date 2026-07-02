package net.firedevops.firemud.loggingadmin.dto;

public class RemoteFollowupResultListRequest {
  private String coordinatorId;
  private String followupId;
  private String originRegionId;
  private String targetRegionId;
  private String outcome;
  private String scriptId;
  private String pluginId;
  private String automationDispatchId;
  private String commandId;
  private Integer limit;
  private String originGameInstanceId;
  private String targetGameInstanceId;
  private Long originRegionEpoch;
  private Long targetRegionEpoch;
  private String scriptPatchVersion;
  private String pluginVersionId;
  private String playableStateScope;
  private String worldSlug;
  private String realmSlug;
  private Long pointerVersion;
  private String resultErrorCode;
  private String automationWorkItemId;
  private String resultCommandId;
  private String resultCommandExecutionOutcome;
  private String resultCommandGameplayResult;
  private String targetEntityId;
  private String effectKey;
  private String failureCode;
  private String payloadKind;
  private String originSourceKind;
  private String eventType;
  private String scriptEventId;
  private String resultMessage;
  private Boolean requiresSoloTick;
  private String originSourceState;
  private String lateResultPolicy;
  private String claimedTickBatchId;
  private String claimTargetAggregate;
  private String currentOriginRuntimeRegionId;
  private Long currentOriginRuntimeRegionEpoch;
  private String currentTargetRuntimeRegionId;
  private Long currentTargetRuntimeRegionEpoch;
  private String queueSourceKind;
  private String queueSourceState;
  private Long queueSourceOrdinal;
  private Long queueSourceDueTickId;
  private Long queueSourceDueAtMs;
  private String currentOriginRuntimeGameInstanceId;
  private String currentTargetRuntimeGameInstanceId;

  public String getCoordinatorId() {
    return coordinatorId;
  }

  public void setCoordinatorId(String coordinatorId) {
    this.coordinatorId = coordinatorId;
  }

  public String getFollowupId() {
    return followupId;
  }

  public void setFollowupId(String followupId) {
    this.followupId = followupId;
  }

  public String getOriginRegionId() {
    return originRegionId;
  }

  public void setOriginRegionId(String originRegionId) {
    this.originRegionId = originRegionId;
  }

  public String getTargetRegionId() {
    return targetRegionId;
  }

  public void setTargetRegionId(String targetRegionId) {
    this.targetRegionId = targetRegionId;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getScriptId() {
    return scriptId;
  }

  public void setScriptId(String scriptId) {
    this.scriptId = scriptId;
  }

  public String getPluginId() {
    return pluginId;
  }

  public void setPluginId(String pluginId) {
    this.pluginId = pluginId;
  }

  public String getAutomationDispatchId() {
    return automationDispatchId;
  }

  public void setAutomationDispatchId(String automationDispatchId) {
    this.automationDispatchId = automationDispatchId;
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

  public String getOriginGameInstanceId() {
    return originGameInstanceId;
  }

  public void setOriginGameInstanceId(String originGameInstanceId) {
    this.originGameInstanceId = originGameInstanceId;
  }

  public String getTargetGameInstanceId() {
    return targetGameInstanceId;
  }

  public void setTargetGameInstanceId(String targetGameInstanceId) {
    this.targetGameInstanceId = targetGameInstanceId;
  }

  public Long getOriginRegionEpoch() {
    return originRegionEpoch;
  }

  public void setOriginRegionEpoch(Long originRegionEpoch) {
    this.originRegionEpoch = originRegionEpoch;
  }

  public Long getTargetRegionEpoch() {
    return targetRegionEpoch;
  }

  public void setTargetRegionEpoch(Long targetRegionEpoch) {
    this.targetRegionEpoch = targetRegionEpoch;
  }

  public String getScriptPatchVersion() {
    return scriptPatchVersion;
  }

  public void setScriptPatchVersion(String scriptPatchVersion) {
    this.scriptPatchVersion = scriptPatchVersion;
  }

  public String getPluginVersionId() {
    return pluginVersionId;
  }

  public void setPluginVersionId(String pluginVersionId) {
    this.pluginVersionId = pluginVersionId;
  }

  public String getPlayableStateScope() {
    return playableStateScope;
  }

  public void setPlayableStateScope(String playableStateScope) {
    this.playableStateScope = playableStateScope;
  }

  public String getWorldSlug() {
    return worldSlug;
  }

  public void setWorldSlug(String worldSlug) {
    this.worldSlug = worldSlug;
  }

  public String getRealmSlug() {
    return realmSlug;
  }

  public void setRealmSlug(String realmSlug) {
    this.realmSlug = realmSlug;
  }

  public Long getPointerVersion() {
    return pointerVersion;
  }

  public void setPointerVersion(Long pointerVersion) {
    this.pointerVersion = pointerVersion;
  }

  public String getResultErrorCode() {
    return resultErrorCode;
  }

  public void setResultErrorCode(String resultErrorCode) {
    this.resultErrorCode = resultErrorCode;
  }

  public String getAutomationWorkItemId() {
    return automationWorkItemId;
  }

  public void setAutomationWorkItemId(String automationWorkItemId) {
    this.automationWorkItemId = automationWorkItemId;
  }

  public String getResultCommandId() {
    return resultCommandId;
  }

  public void setResultCommandId(String resultCommandId) {
    this.resultCommandId = resultCommandId;
  }

  public String getResultCommandExecutionOutcome() {
    return resultCommandExecutionOutcome;
  }

  public void setResultCommandExecutionOutcome(String resultCommandExecutionOutcome) {
    this.resultCommandExecutionOutcome = resultCommandExecutionOutcome;
  }

  public String getResultCommandGameplayResult() {
    return resultCommandGameplayResult;
  }

  public void setResultCommandGameplayResult(String resultCommandGameplayResult) {
    this.resultCommandGameplayResult = resultCommandGameplayResult;
  }

  public String getTargetEntityId() {
    return targetEntityId;
  }

  public void setTargetEntityId(String targetEntityId) {
    this.targetEntityId = targetEntityId;
  }

  public String getEffectKey() {
    return effectKey;
  }

  public void setEffectKey(String effectKey) {
    this.effectKey = effectKey;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String failureCode) {
    this.failureCode = failureCode;
  }

  public String getPayloadKind() {
    return payloadKind;
  }

  public void setPayloadKind(String payloadKind) {
    this.payloadKind = payloadKind;
  }

  public String getOriginSourceKind() {
    return originSourceKind;
  }

  public void setOriginSourceKind(String originSourceKind) {
    this.originSourceKind = originSourceKind;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getScriptEventId() {
    return scriptEventId;
  }

  public void setScriptEventId(String scriptEventId) {
    this.scriptEventId = scriptEventId;
  }

  public String getResultMessage() {
    return resultMessage;
  }

  public void setResultMessage(String resultMessage) {
    this.resultMessage = resultMessage;
  }

  public Boolean getRequiresSoloTick() {
    return requiresSoloTick;
  }

  public void setRequiresSoloTick(Boolean requiresSoloTick) {
    this.requiresSoloTick = requiresSoloTick;
  }

  public String getOriginSourceState() {
    return originSourceState;
  }

  public void setOriginSourceState(String originSourceState) {
    this.originSourceState = originSourceState;
  }

  public String getLateResultPolicy() {
    return lateResultPolicy;
  }

  public void setLateResultPolicy(String lateResultPolicy) {
    this.lateResultPolicy = lateResultPolicy;
  }

  public String getClaimedTickBatchId() {
    return claimedTickBatchId;
  }

  public void setClaimedTickBatchId(String claimedTickBatchId) {
    this.claimedTickBatchId = claimedTickBatchId;
  }

  public String getClaimTargetAggregate() {
    return claimTargetAggregate;
  }

  public void setClaimTargetAggregate(String claimTargetAggregate) {
    this.claimTargetAggregate = claimTargetAggregate;
  }

  public String getCurrentOriginRuntimeRegionId() {
    return currentOriginRuntimeRegionId;
  }

  public void setCurrentOriginRuntimeRegionId(String currentOriginRuntimeRegionId) {
    this.currentOriginRuntimeRegionId = currentOriginRuntimeRegionId;
  }

  public Long getCurrentOriginRuntimeRegionEpoch() {
    return currentOriginRuntimeRegionEpoch;
  }

  public void setCurrentOriginRuntimeRegionEpoch(Long currentOriginRuntimeRegionEpoch) {
    this.currentOriginRuntimeRegionEpoch = currentOriginRuntimeRegionEpoch;
  }

  public String getCurrentTargetRuntimeRegionId() {
    return currentTargetRuntimeRegionId;
  }

  public void setCurrentTargetRuntimeRegionId(String currentTargetRuntimeRegionId) {
    this.currentTargetRuntimeRegionId = currentTargetRuntimeRegionId;
  }

  public Long getCurrentTargetRuntimeRegionEpoch() {
    return currentTargetRuntimeRegionEpoch;
  }

  public void setCurrentTargetRuntimeRegionEpoch(Long currentTargetRuntimeRegionEpoch) {
    this.currentTargetRuntimeRegionEpoch = currentTargetRuntimeRegionEpoch;
  }

  public String getQueueSourceKind() {
    return queueSourceKind;
  }

  public void setQueueSourceKind(String queueSourceKind) {
    this.queueSourceKind = queueSourceKind;
  }

  public String getQueueSourceState() {
    return queueSourceState;
  }

  public void setQueueSourceState(String queueSourceState) {
    this.queueSourceState = queueSourceState;
  }

  public Long getQueueSourceOrdinal() {
    return queueSourceOrdinal;
  }

  public void setQueueSourceOrdinal(Long queueSourceOrdinal) {
    this.queueSourceOrdinal = queueSourceOrdinal;
  }

  public Long getQueueSourceDueTickId() {
    return queueSourceDueTickId;
  }

  public void setQueueSourceDueTickId(Long queueSourceDueTickId) {
    this.queueSourceDueTickId = queueSourceDueTickId;
  }

  public Long getQueueSourceDueAtMs() {
    return queueSourceDueAtMs;
  }

  public void setQueueSourceDueAtMs(Long queueSourceDueAtMs) {
    this.queueSourceDueAtMs = queueSourceDueAtMs;
  }

  public String getCurrentOriginRuntimeGameInstanceId() {
    return currentOriginRuntimeGameInstanceId;
  }

  public void setCurrentOriginRuntimeGameInstanceId(String currentOriginRuntimeGameInstanceId) {
    this.currentOriginRuntimeGameInstanceId = currentOriginRuntimeGameInstanceId;
  }

  public String getCurrentTargetRuntimeGameInstanceId() {
    return currentTargetRuntimeGameInstanceId;
  }

  public void setCurrentTargetRuntimeGameInstanceId(String currentTargetRuntimeGameInstanceId) {
    this.currentTargetRuntimeGameInstanceId = currentTargetRuntimeGameInstanceId;
  }
}
