package net.firedevops.firemud.gamedesign.service.impl;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class TemporalVersionPublishWorkflowImpl implements TemporalVersionPublishWorkflow {
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final TemporalVersionPublishActivities activities =
      Workflow.newActivityStub(
          TemporalVersionPublishActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(3))
              .setRetryOptions(
                  io.temporal.common.RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofSeconds(10))
                      .setMaximumAttempts(5)
                      .build())
              .build());

  private PublishWorkflowSnapshot currentSnapshot;

  @Override
  public PublishWorkflowSnapshot run(PublishWorkflowRequest request) {
    currentSnapshot = activities.reconcile(request);
    while (!currentSnapshot.isTerminal()) {
      Workflow.sleep(POLL_INTERVAL);
      currentSnapshot = activities.reconcile(request);
    }
    return currentSnapshot;
  }

  @Override
  public PublishWorkflowSnapshot currentSnapshot() {
    return currentSnapshot;
  }
}
