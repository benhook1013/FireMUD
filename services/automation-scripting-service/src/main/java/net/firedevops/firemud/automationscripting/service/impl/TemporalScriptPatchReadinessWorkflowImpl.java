package net.firedevops.firemud.automationscripting.service.impl;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class TemporalScriptPatchReadinessWorkflowImpl
    implements TemporalScriptPatchReadinessWorkflow {
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final TemporalScriptPatchReadinessActivities activities =
      Workflow.newActivityStub(
          TemporalScriptPatchReadinessActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(1))
              .setRetryOptions(
                  io.temporal.common.RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofSeconds(10))
                      .setMaximumAttempts(5)
                      .build())
              .build());

  private ScriptPatchReadinessWorkflowSnapshot currentSnapshot;

  @Override
  public ScriptPatchReadinessWorkflowSnapshot run(ScriptPatchReadinessWorkflowRequest request) {
    currentSnapshot =
        activities.refreshAndLoadStatus(request.tenantId(), request.scriptPatchVersion());
    while (!currentSnapshot.isTerminal()) {
      Workflow.sleep(POLL_INTERVAL);
      currentSnapshot =
          activities.refreshAndLoadStatus(request.tenantId(), request.scriptPatchVersion());
    }
    return currentSnapshot;
  }

  @Override
  public ScriptPatchReadinessWorkflowSnapshot currentSnapshot() {
    return currentSnapshot;
  }
}
