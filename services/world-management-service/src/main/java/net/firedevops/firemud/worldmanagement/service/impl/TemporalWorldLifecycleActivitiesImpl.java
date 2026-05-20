package net.firedevops.firemud.worldmanagement.service.impl;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

@Component
public class TemporalWorldLifecycleActivitiesImpl implements TemporalWorldLifecycleActivities {
  private static final Logger logger =
      LoggingUtil.getLogger(TemporalWorldLifecycleActivitiesImpl.class);

  private final WorldLifecycleCommandService commandService;

  public TemporalWorldLifecycleActivitiesImpl(WorldLifecycleCommandService commandService) {
    this.commandService = commandService;
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto prepareWorldInstance(
      PreparedWorldInstanceRequest request) {
    logStep("prepare", workflowBusinessKey(request.tenantId(), request.gameInstanceId()));
    return commandService.prepareWorldInstance(request);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch) {
    logStep("activate", workflowBusinessKey(tenantId, gameInstanceId));
    return commandService.activatePreparedWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    logStep("fail", workflowBusinessKey(tenantId, gameInstanceId));
    return commandService.failPreparedWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch, reason);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto terminateWorldInstance(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason) {
    logStep("terminate", workflowBusinessKey(tenantId, gameInstanceId));
    return commandService.terminateWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId, reason);
  }

  private void logStep(String stepName, String workflowId) {
    logger.info(
        "Executing Temporal world lifecycle step workflowId={} businessStepKey={}",
        workflowId,
        FiremudWorkflowIds.businessStepKey(workflowId, stepName, stepName));
  }

  static String workflowBusinessKey(long tenantId, long gameInstanceId) {
    return FiremudWorkflowIds.workflowId(
        TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY,
        Long.toString(tenantId),
        "game-instance",
        Long.toString(gameInstanceId));
  }
}
