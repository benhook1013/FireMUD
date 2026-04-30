package net.firedevops.firemud.gamesession.service;

public interface RemoteFollowupRuntimeService {
  ScheduleOutcome scheduleFollowup(ScheduleRequest request);

  ResultOutcome recordResult(ResultRequest request);

  int reconcileResults(long tenantId, String originRegionId, long currentOriginRegionEpoch);

  int reconcileTimeouts(
      long tenantId, String originRegionId, long currentOriginRegionEpoch, long currentTickId);

  record ScheduleRequest(
      long tenantId,
      String commandId,
      String coordinatorId,
      long originGameInstanceId,
      String originRegionId,
      long originRegionEpoch,
      long targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch,
      long targetDueTickId,
      long originDeadlineRegionEpoch,
      long originDeadlineTickId,
      String lateResultPolicy,
      String followupId,
      String effectKey,
      String targetEntityId,
      String payloadJson) {}

  record ResultRequest(
      long tenantId,
      String resultId,
      String coordinatorId,
      String followupId,
      String originRegionId,
      long originRegionEpoch,
      String targetRegionId,
      long targetRegionEpoch,
      String outcome,
      String resultPayloadJson) {}

  record ScheduleOutcome(
      String coordinatorId,
      String followupId,
      boolean coordinatorCreated,
      boolean followupCreated) {}

  record ResultOutcome(
      String coordinatorState,
      String followupStatus,
      boolean lateResult,
      boolean reconciledLateResult) {}
}
