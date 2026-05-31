package net.firedevops.firemud.worldmanagement.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.temporal.config.TemporalProperties;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import org.springframework.stereotype.Component;

@Component
public class TemporalWorldLifecycleWorkflowMetadataResolver {
  private final Optional<WorkflowServiceStubs> workflowServiceStubs;
  private final Optional<TemporalProperties> temporalProperties;

  public TemporalWorldLifecycleWorkflowMetadataResolver(
      Optional<WorkflowServiceStubs> workflowServiceStubs,
      Optional<TemporalProperties> temporalProperties) {
    this.workflowServiceStubs = workflowServiceStubs;
    this.temporalProperties = temporalProperties;
  }

  public WorldInstanceLifecycleSnapshotDto attach(WorldInstanceLifecycleSnapshotDto snapshot) {
    String workflowId = workflowId(snapshot.tenantId(), snapshot.gameInstanceId());
    if (workflowServiceStubs.isEmpty() || temporalProperties.isEmpty()) {
      return snapshot.withWorkflowMetadata(
          workflowId, TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY, null, "TEMPORAL_DISABLED");
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
      return snapshot.withWorkflowMetadata(
          workflowId, TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY, runId, status.name());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return snapshot.withWorkflowMetadata(
            workflowId, TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY, null, "NOT_FOUND");
      }
      throw ex;
    }
  }

  String workflowId(long tenantId, long gameInstanceId) {
    return FiremudWorkflowIds.workflowId(
        TemporalWorldLifecycleWorkflow.WORKFLOW_FAMILY,
        Long.toString(tenantId),
        "game-instance",
        Long.toString(gameInstanceId));
  }
}
