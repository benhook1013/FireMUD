package net.firedevops.firemud.worldmanagement.service.impl;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean({WorkflowClient.class, TemporalTaskQueueResolver.class})
public class TemporalWorldLifecycleOrchestrator {
  private static final Duration QUERY_WAIT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration QUERY_WAIT_INTERVAL = Duration.ofMillis(100);

  private final WorkflowClient workflowClient;
  private final TemporalTaskQueueResolver taskQueues;
  private final TemporalWorldLifecycleWorkflowMetadataResolver metadataResolver;

  public TemporalWorldLifecycleOrchestrator(
      WorkflowClient workflowClient,
      TemporalTaskQueueResolver taskQueues,
      TemporalWorldLifecycleWorkflowMetadataResolver metadataResolver) {
    this.workflowClient = workflowClient;
    this.taskQueues = taskQueues;
    this.metadataResolver = metadataResolver;
  }

  public WorldInstanceLifecycleSnapshotDto prepareWorldInstance(
      PreparedWorldInstanceRequest request) {
    TemporalWorldLifecycleWorkflow workflow =
        newWorkflowStub(request.tenantId(), request.gameInstanceId());
    try {
      WorkflowStub.fromTyped(workflow).start(request);
    } catch (WorkflowExecutionAlreadyStarted ignored) {
      // Idempotent lifecycle prepare can race with retries; query the existing workflow instead.
    }
    return waitForSnapshot(workflow, request.tenantId(), request.gameInstanceId(), "PREPARING", 1L);
  }

  public WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch) {
    TemporalWorldLifecycleWorkflow workflow = existingWorkflowStub(tenantId, gameInstanceId);
    workflow.activate(expectedLifecycleEpoch);
    return waitForSnapshot(
        workflow, tenantId, gameInstanceId, "ACTIVE", expectedLifecycleEpoch + 1L);
  }

  public WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    TemporalWorldLifecycleWorkflow workflow = existingWorkflowStub(tenantId, gameInstanceId);
    workflow.fail(expectedLifecycleEpoch, reason);
    return waitForSnapshot(
        workflow, tenantId, gameInstanceId, "FAILED_PRE_ACTIVATION", expectedLifecycleEpoch + 1L);
  }

  public WorldInstanceLifecycleSnapshotDto terminateWorldInstance(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason) {
    TemporalWorldLifecycleWorkflow workflow = existingWorkflowStub(tenantId, gameInstanceId);
    workflow.terminate(expectedLifecycleEpoch, terminationRequestId, reason);
    return waitForSnapshot(
        workflow, tenantId, gameInstanceId, "TERMINATED", expectedLifecycleEpoch + 2L);
  }

  public WorldInstanceLifecycleSnapshotDto getWorldInstanceLifecycle(
      long tenantId, long gameInstanceId, WorldInstanceLifecycleSnapshotDto fallback) {
    return metadataResolver.attach(fallback);
  }

  private TemporalWorldLifecycleWorkflow newWorkflowStub(long tenantId, long gameInstanceId) {
    return workflowClient.newWorkflowStub(
        TemporalWorldLifecycleWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(
                taskQueues.forWorkflowFamily(TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY))
            .setWorkflowId(workflowId(tenantId, gameInstanceId))
            .build());
  }

  private TemporalWorldLifecycleWorkflow existingWorkflowStub(long tenantId, long gameInstanceId) {
    return workflowClient.newWorkflowStub(
        TemporalWorldLifecycleWorkflow.class, workflowId(tenantId, gameInstanceId));
  }

  private WorldInstanceLifecycleSnapshotDto waitForSnapshot(
      TemporalWorldLifecycleWorkflow workflow,
      long tenantId,
      long gameInstanceId,
      String expectedStatus,
      long minimumLifecycleEpoch) {
    long deadline = System.nanoTime() + QUERY_WAIT_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      WorldInstanceLifecycleSnapshotDto snapshot = workflow.currentSnapshot();
      if (snapshot != null
          && expectedStatus.equals(snapshot.status())
          && snapshot.lifecycleEpoch() >= minimumLifecycleEpoch) {
        return metadataResolver.attach(snapshot);
      }
      sleepQuietly();
    }
    throw new IllegalStateException(
        "TEMPORAL_WORKFLOW_TIMEOUT: world lifecycle workflow did not converge for workflowId="
            + workflowId(tenantId, gameInstanceId));
  }

  private void sleepQuietly() {
    try {
      Thread.sleep(QUERY_WAIT_INTERVAL.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for Temporal workflow state", ex);
    }
  }

  private String workflowId(long tenantId, long gameInstanceId) {
    return FiremudWorkflowIds.workflowId(
        TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY,
        Long.toString(tenantId),
        "game-instance",
        Long.toString(gameInstanceId));
  }
}
