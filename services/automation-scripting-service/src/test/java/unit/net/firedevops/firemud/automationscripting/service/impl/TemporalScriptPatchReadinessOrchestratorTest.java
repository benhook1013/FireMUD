package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;

class TemporalScriptPatchReadinessOrchestratorTest {
  @Test
  void workflowOptionsUseStableIdentityAndRejectDuplicateReuse() {
    WorkflowOptions options =
        TemporalScriptPatchReadinessOrchestrator.newWorkflowOptions(
            "automation-queue", "1", "patch-1");

    assertThat(options.getTaskQueue()).isEqualTo("automation-queue");
    assertThat(options.getWorkflowId())
        .isEqualTo("script-patch-readiness:1:script-patch-version:patch-1");
    assertThat(options.getWorkflowIdReusePolicy())
        .isEqualTo(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
  }
}
