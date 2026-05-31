package net.firedevops.firemud.automationscripting.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.temporal.config.TemporalProperties;
import org.springframework.stereotype.Component;

@Component
public class TemporalScriptPatchReadinessWorkflowMetadataResolver {
  private final Optional<WorkflowServiceStubs> workflowServiceStubs;
  private final Optional<TemporalProperties> temporalProperties;

  public TemporalScriptPatchReadinessWorkflowMetadataResolver(
      Optional<WorkflowServiceStubs> workflowServiceStubs,
      Optional<TemporalProperties> temporalProperties) {
    this.workflowServiceStubs = workflowServiceStubs;
    this.temporalProperties = temporalProperties;
  }

  public WorkflowMetadata resolve(String tenantId, String scriptPatchVersion) {
    String workflowId = workflowId(tenantId, scriptPatchVersion);
    if (workflowServiceStubs.isEmpty() || temporalProperties.isEmpty()) {
      return new WorkflowMetadata(
          workflowId,
          TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY,
          "",
          "TEMPORAL_DISABLED");
    }
    try {
      var response =
          workflowServiceStubs
              .get()
              .blockingStub()
              .describeWorkflowExecution(
                  DescribeWorkflowExecutionRequest.newBuilder()
                      .setNamespace(temporalProperties.get().getNamespace())
                      .setExecution(
                          WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                      .build());
      WorkflowExecutionStatus status = response.getWorkflowExecutionInfo().getStatus();
      String runId = response.getWorkflowExecutionInfo().getExecution().getRunId();
      return new WorkflowMetadata(
          workflowId, TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY, runId, status.name());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return new WorkflowMetadata(
            workflowId, TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY, "", "NOT_FOUND");
      }
      throw ex;
    }
  }

  static String workflowId(String tenantId, String scriptPatchVersion) {
    return FiremudWorkflowIds.workflowId(
        TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY,
        tenantId,
        "script-patch-version",
        scriptPatchVersion);
  }

  record WorkflowMetadata(
      String workflowId, String workflowFamily, String workflowRunId, String workflowStatus) {}
}
