package net.firedevops.firemud.gamedesign.service.impl;

import io.temporal.client.WorkflowClient;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.service.PublishAttemptPendingReconciliationException;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean({WorkflowClient.class, TemporalTaskQueueResolver.class})
public class TemporalVersionPublishOrchestrator {
  static final String PENDING_RECONCILIATION_REQUIRED_CODE =
      PublishAttemptPendingReconciliationException.ERROR_CODE;
  private static final String VERSION_PUBLISH_WORKFLOW_FAILED = "VERSION_PUBLISH_WORKFLOW_FAILED";

  private final VersionPublishCommandServiceImpl commandService;

  public TemporalVersionPublishOrchestrator(
      WorkflowClient workflowClient,
      TemporalTaskQueueResolver taskQueues,
      VersionPublishCommandServiceImpl commandService) {
    this.commandService = commandService;
  }

  public VersionDto publishFullVersion(String tenantId, String notes, String publishRequestId) {
    String workflowId = workflowId(tenantId, publishRequestId);
    // The foundation has no production Draft association producer. Let the command boundary
    // replay an exact terminal result or reject fresh/pending publication before a workflow starts.
    return commandService.publishFullVersion(tenantId, notes, publishRequestId, workflowId);
  }

  static RuntimeException failureForSnapshot(PublishWorkflowSnapshot snapshot) {
    String failureCode = snapshot.failureCode();
    if (PublishAttemptPendingReconciliationException.ERROR_CODE.equals(failureCode)) {
      return new PublishAttemptPendingReconciliationException();
    }
    if (failureCode != null && !failureCode.isBlank()) {
      try {
        PublishGateFailureCode gateFailureCode = PublishGateFailureCode.valueOf(failureCode);
        return new PublishGateFailureException(gateFailureCode, snapshot.failureMessage());
      } catch (IllegalArgumentException ignored) {
        // Unknown snapshot codes remain generic failures.
      }
    }
    String failureMessage = snapshot.failureMessage();
    if (failureMessage != null && !failureMessage.isBlank()) {
      return new IllegalStateException(failureMessage);
    }
    if (failureCode != null && !failureCode.isBlank()) {
      return new IllegalStateException(failureCode);
    }
    return new IllegalStateException(VERSION_PUBLISH_WORKFLOW_FAILED);
  }

  static RuntimeException timeoutException(
      PublishWorkflowSnapshot lastSnapshot, String workflowId) {
    String timeoutDetail = "version publish workflow did not converge for workflowId=" + workflowId;
    if (lastSnapshot != null
        && PENDING_RECONCILIATION_REQUIRED_CODE.equals(lastSnapshot.failureCode())) {
      return new PublishAttemptPendingReconciliationException();
    }
    return new IllegalStateException("TEMPORAL_WORKFLOW_TIMEOUT: " + timeoutDetail);
  }

  static String workflowId(String tenantId, String publishRequestId) {
    return FiremudWorkflowIds.workflowId(
        TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
        tenantId,
        "publish-request",
        publishRequestId);
  }
}
