package net.firedevops.firemud.automationscripting.service.impl;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean({WorkflowClient.class, TemporalTaskQueueResolver.class})
public class TemporalScriptPatchReadinessOrchestrator {
  private static final Duration QUERY_WAIT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration QUERY_WAIT_INTERVAL = Duration.ofMillis(100);

  private final WorkflowClient workflowClient;
  private final TemporalTaskQueueResolver taskQueues;

  public TemporalScriptPatchReadinessOrchestrator(
      WorkflowClient workflowClient, TemporalTaskQueueResolver taskQueues) {
    this.workflowClient = workflowClient;
    this.taskQueues = taskQueues;
  }

  public void startTracking(String tenantId, String scriptPatchVersion) {
    TemporalScriptPatchReadinessWorkflow workflow = newWorkflowStub(tenantId, scriptPatchVersion);
    try {
      WorkflowStub.fromTyped(workflow)
          .start(new ScriptPatchReadinessWorkflowRequest(tenantId, scriptPatchVersion));
    } catch (WorkflowExecutionAlreadyStarted ignored) {
      // Idempotent update retries should attach to the existing workflow.
    }
    waitForSnapshot(workflow, tenantId, scriptPatchVersion);
  }

  private TemporalScriptPatchReadinessWorkflow newWorkflowStub(
      String tenantId, String scriptPatchVersion) {
    return workflowClient.newWorkflowStub(
        TemporalScriptPatchReadinessWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(
                taskQueues.forWorkflowFamily(TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY))
            .setWorkflowId(
                TemporalScriptPatchReadinessWorkflowMetadataResolver.workflowId(
                    tenantId, scriptPatchVersion))
            .build());
  }

  private void waitForSnapshot(
      TemporalScriptPatchReadinessWorkflow workflow, String tenantId, String scriptPatchVersion) {
    long deadline = System.nanoTime() + QUERY_WAIT_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      ScriptPatchReadinessWorkflowSnapshot snapshot = workflow.currentSnapshot();
      if (snapshot != null) {
        return;
      }
      sleepQuietly();
    }
    throw new IllegalStateException(
        "TEMPORAL_WORKFLOW_TIMEOUT: script patch readiness workflow did not start for workflowId="
            + TemporalScriptPatchReadinessWorkflowMetadataResolver.workflowId(
                tenantId, scriptPatchVersion));
  }

  private void sleepQuietly() {
    try {
      Thread.sleep(QUERY_WAIT_INTERVAL.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for Temporal workflow state", ex);
    }
  }
}
