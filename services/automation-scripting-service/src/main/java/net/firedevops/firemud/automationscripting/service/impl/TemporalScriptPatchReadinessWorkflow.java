package net.firedevops.firemud.automationscripting.service.impl;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TemporalScriptPatchReadinessWorkflow {
  String WORKFLOW_FAMILY = "script-patch-readiness";

  @WorkflowMethod
  ScriptPatchReadinessWorkflowSnapshot run(ScriptPatchReadinessWorkflowRequest request);

  @QueryMethod
  ScriptPatchReadinessWorkflowSnapshot currentSnapshot();
}
