package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import net.firedevops.firemud.common.temporal.config.TemporalProperties;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import org.junit.jupiter.api.Test;

class TemporalWorldLifecycleWorkflowMetadataResolverTest {
  @Test
  void attachReportsDisabledWhenTemporalRuntimeUnavailable() {
    TemporalWorldLifecycleWorkflowMetadataResolver resolver =
        new TemporalWorldLifecycleWorkflowMetadataResolver(Optional.empty(), Optional.empty());

    WorldInstanceLifecycleSnapshotDto snapshot = resolver.attach(baseSnapshot());

    assertEquals("world-lifecycle:42:game-instance:101", snapshot.workflowId());
    assertEquals("world-lifecycle", snapshot.workflowFamily());
    assertEquals("TEMPORAL_DISABLED", snapshot.workflowStatus());
  }

  @Test
  void attachReadsWorkflowStatusFromTemporalDescribeSurface() {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(stubs.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.describeWorkflowExecution(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            DescribeWorkflowExecutionResponse.newBuilder()
                .setWorkflowExecutionInfo(
                    io.temporal.api.workflow.v1.WorkflowExecutionInfo.newBuilder()
                        .setExecution(
                            WorkflowExecution.newBuilder()
                                .setWorkflowId("world-lifecycle:42:game-instance:101")
                                .setRunId("run-1")
                                .build())
                        .setStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
                        .build())
                .build());
    TemporalProperties properties = new TemporalProperties();
    properties.setNamespace("firemud-test");
    TemporalWorldLifecycleWorkflowMetadataResolver resolver =
        new TemporalWorldLifecycleWorkflowMetadataResolver(
            Optional.of(stubs), Optional.of(properties));

    WorldInstanceLifecycleSnapshotDto snapshot = resolver.attach(baseSnapshot());

    assertEquals("world-lifecycle:42:game-instance:101", snapshot.workflowId());
    assertEquals("world-lifecycle", snapshot.workflowFamily());
    assertEquals("run-1", snapshot.workflowRunId());
    assertEquals("WORKFLOW_EXECUTION_STATUS_RUNNING", snapshot.workflowStatus());
  }

  @Test
  void attachMapsMissingWorkflowToNotFound() {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(stubs.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.describeWorkflowExecution(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));
    TemporalProperties properties = new TemporalProperties();
    properties.setNamespace("firemud-test");
    TemporalWorldLifecycleWorkflowMetadataResolver resolver =
        new TemporalWorldLifecycleWorkflowMetadataResolver(
            Optional.of(stubs), Optional.of(properties));

    WorldInstanceLifecycleSnapshotDto snapshot = resolver.attach(baseSnapshot());

    assertEquals("world-lifecycle", snapshot.workflowFamily());
    assertEquals("NOT_FOUND", snapshot.workflowStatus());
  }

  private WorldInstanceLifecycleSnapshotDto baseSnapshot() {
    return new WorldInstanceLifecycleSnapshotDto(
        42L,
        101L,
        7L,
        "cp-1",
        "ld-1",
        11L,
        77L,
        "genrev-11",
        "prb:42:11:77",
        77L,
        2L,
        "ACTIVE",
        "remap-1",
        null,
        null,
        null,
        null);
  }
}
