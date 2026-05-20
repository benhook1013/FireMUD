package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import net.firedevops.firemud.common.temporal.config.TemporalProperties;
import org.springframework.stereotype.Component;

@Component
public class TemporalVersionPublishWorkflowMetadataResolver {
  private final Optional<WorkflowServiceStubs> workflowServiceStubs;
  private final Optional<TemporalProperties> temporalProperties;

  public TemporalVersionPublishWorkflowMetadataResolver(
      Optional<WorkflowServiceStubs> workflowServiceStubs,
      Optional<TemporalProperties> temporalProperties) {
    this.workflowServiceStubs = workflowServiceStubs;
    this.temporalProperties = temporalProperties;
  }

  public WorkflowMetadata resolve(String workflowId) {
    if (workflowId == null || workflowId.isBlank()) {
      return new WorkflowMetadata("", "", "UNAVAILABLE");
    }
    if (workflowServiceStubs.isEmpty() || temporalProperties.isEmpty()) {
      return new WorkflowMetadata(workflowId, "", "TEMPORAL_DISABLED");
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
      return new WorkflowMetadata(workflowId, runId, status.name());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return new WorkflowMetadata(workflowId, "", "NOT_FOUND");
      }
      throw ex;
    }
  }

  record WorkflowMetadata(String workflowId, String workflowRunId, String workflowStatus) {}
}
