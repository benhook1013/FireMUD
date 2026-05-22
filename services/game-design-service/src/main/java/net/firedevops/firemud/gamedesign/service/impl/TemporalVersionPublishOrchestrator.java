package net.firedevops.firemud.gamedesign.service.impl;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean({WorkflowClient.class, TemporalTaskQueueResolver.class})
public class TemporalVersionPublishOrchestrator {
  private static final Duration QUERY_WAIT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration QUERY_WAIT_INTERVAL = Duration.ofMillis(100);

  private final WorkflowClient workflowClient;
  private final TemporalTaskQueueResolver taskQueues;
  private final VersionPublishCommandServiceImpl commandService;

  public TemporalVersionPublishOrchestrator(
      WorkflowClient workflowClient,
      TemporalTaskQueueResolver taskQueues,
      VersionPublishCommandServiceImpl commandService) {
    this.workflowClient = workflowClient;
    this.taskQueues = taskQueues;
    this.commandService = commandService;
  }

  public VersionDto publishFullVersion(String tenantId, String notes, String publishRequestId) {
    String workflowId = workflowId(tenantId, publishRequestId);
    PublishWorkflowRequest request = new PublishWorkflowRequest(tenantId, notes, workflowId);
    TemporalVersionPublishWorkflow workflow = newWorkflowStub(workflowId);
    try {
      WorkflowStub.fromTyped(workflow).start(request);
    } catch (WorkflowExecutionAlreadyStarted ignored) {
      // Idempotent retries should attach to the existing workflow.
    }
    PublishWorkflowSnapshot snapshot = waitForSnapshot(workflow, workflowId);
    if (snapshot.isSucceeded()) {
      return commandService.publishFullVersion(tenantId, notes, workflowId);
    }
    if (snapshot.failureCode() != null && snapshot.failureCode().startsWith("DIGEST_")) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.valueOf(snapshot.failureCode()), snapshot.failureMessage());
    }
    throw new IllegalStateException(
        snapshot.failureMessage() == null || snapshot.failureMessage().isBlank()
            ? snapshot.failureCode()
            : snapshot.failureMessage());
  }

  private TemporalVersionPublishWorkflow newWorkflowStub(String workflowId) {
    return workflowClient.newWorkflowStub(
        TemporalVersionPublishWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(
                taskQueues.forWorkflowFamily(TemporalVersionPublishWorkflow.WORKFLOW_FAMILY))
            .setWorkflowId(workflowId)
            .build());
  }

  private PublishWorkflowSnapshot waitForSnapshot(
      TemporalVersionPublishWorkflow workflow, String workflowId) {
    long deadline = System.nanoTime() + QUERY_WAIT_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      PublishWorkflowSnapshot snapshot = workflow.currentSnapshot();
      if (snapshot != null && snapshot.isTerminal()) {
        return snapshot;
      }
      sleepQuietly();
    }
    throw new IllegalStateException(
        "TEMPORAL_WORKFLOW_TIMEOUT: version publish workflow did not converge for workflowId="
            + workflowId);
  }

  private void sleepQuietly() {
    try {
      Thread.sleep(QUERY_WAIT_INTERVAL.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for Temporal workflow state", ex);
    }
  }

  static String workflowId(String tenantId, String publishRequestId) {
    return FiremudWorkflowIds.workflowId(
        TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
        tenantId,
        "publish-request",
        publishRequestId);
  }
}
