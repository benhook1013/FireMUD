package net.firedevops.firemud.gamedesign.service.impl;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TemporalVersionPublishWorkflow {
  String WORKFLOW_FAMILY = "publish";

  @WorkflowMethod
  PublishWorkflowSnapshot run(PublishWorkflowRequest request);

  @QueryMethod
  PublishWorkflowSnapshot currentSnapshot();
}
