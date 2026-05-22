package net.firedevops.firemud.worldmanagement.service.impl;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;

public class TemporalWorldLifecycleWorkflowImpl implements TemporalWorldLifecycleWorkflow {
  private final TemporalWorldLifecycleActivities activities =
      Workflow.newActivityStub(
          TemporalWorldLifecycleActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(
                  io.temporal.common.RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofSeconds(10))
                      .setMaximumAttempts(5)
                      .build())
              .build());

  private WorldInstanceLifecycleSnapshotDto currentSnapshot;
  private Long pendingActivateEpoch;
  private Long pendingFailEpoch;
  private String pendingFailReason;
  private Long pendingTerminateEpoch;
  private String pendingTerminationRequestId;
  private String pendingTerminateReason;
  private boolean completed;

  @Override
  public WorldInstanceLifecycleSnapshotDto run(PreparedWorldInstanceRequest request) {
    currentSnapshot = activities.prepareWorldInstance(request);
    while (!completed) {
      Workflow.await(
          () ->
              pendingActivateEpoch != null
                  || pendingFailEpoch != null
                  || pendingTerminateEpoch != null);
      if (pendingActivateEpoch != null) {
        currentSnapshot =
            activities.activatePreparedWorldInstance(
                request.tenantId(), request.gameInstanceId(), pendingActivateEpoch);
        pendingActivateEpoch = null;
      }
      if (pendingFailEpoch != null) {
        currentSnapshot =
            activities.failPreparedWorldInstance(
                request.tenantId(), request.gameInstanceId(), pendingFailEpoch, pendingFailReason);
        pendingFailEpoch = null;
        pendingFailReason = null;
        completed = true;
      }
      if (pendingTerminateEpoch != null) {
        currentSnapshot =
            activities.terminateWorldInstance(
                request.tenantId(),
                request.gameInstanceId(),
                pendingTerminateEpoch,
                pendingTerminationRequestId,
                pendingTerminateReason);
        pendingTerminateEpoch = null;
        pendingTerminationRequestId = null;
        pendingTerminateReason = null;
        completed = true;
      }
    }
    return currentSnapshot;
  }

  @Override
  public void activate(long expectedLifecycleEpoch) {
    pendingActivateEpoch = expectedLifecycleEpoch;
  }

  @Override
  public void fail(long expectedLifecycleEpoch, String reason) {
    pendingFailEpoch = expectedLifecycleEpoch;
    pendingFailReason = reason;
  }

  @Override
  public void terminate(long expectedLifecycleEpoch, String terminationRequestId, String reason) {
    pendingTerminateEpoch = expectedLifecycleEpoch;
    pendingTerminationRequestId = terminationRequestId;
    pendingTerminateReason = reason;
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto currentSnapshot() {
    return currentSnapshot;
  }
}
