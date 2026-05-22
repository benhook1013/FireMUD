package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;

class TemporalVersionPublishWorkflowMetadataResolverTest {
  @Test
  void resolveReportsDisabledWhenTemporalRuntimeUnavailable() {
    TemporalVersionPublishWorkflowMetadataResolver resolver =
        new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty());

    var metadata = resolver.resolve("publish:1:publish-request:req-1");

    assertThat(metadata.workflowId()).isEqualTo("publish:1:publish-request:req-1");
    assertThat(metadata.workflowStatus()).isEqualTo("TEMPORAL_DISABLED");
  }

  @Test
  void resolveReadsWorkflowStatusFromTemporalDescribeSurface() {
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
                                .setWorkflowId("publish:1:publish-request:req-1")
                                .setRunId("run-1")
                                .build())
                        .setStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
                        .build())
                .build());
    TemporalProperties properties = new TemporalProperties();
    properties.setNamespace("firemud-test");
    TemporalVersionPublishWorkflowMetadataResolver resolver =
        new TemporalVersionPublishWorkflowMetadataResolver(
            Optional.of(stubs), Optional.of(properties));

    var metadata = resolver.resolve("publish:1:publish-request:req-1");

    assertThat(metadata.workflowId()).isEqualTo("publish:1:publish-request:req-1");
    assertThat(metadata.workflowRunId()).isEqualTo("run-1");
    assertThat(metadata.workflowStatus()).isEqualTo("WORKFLOW_EXECUTION_STATUS_RUNNING");
  }

  @Test
  void resolveMapsMissingWorkflowToNotFound() {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(stubs.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.describeWorkflowExecution(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));
    TemporalProperties properties = new TemporalProperties();
    properties.setNamespace("firemud-test");
    TemporalVersionPublishWorkflowMetadataResolver resolver =
        new TemporalVersionPublishWorkflowMetadataResolver(
            Optional.of(stubs), Optional.of(properties));

    var metadata = resolver.resolve("publish:1:publish-request:req-1");

    assertThat(metadata.workflowStatus()).isEqualTo("NOT_FOUND");
  }
}
