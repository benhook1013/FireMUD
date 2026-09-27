package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.junit.jupiter.api.Test;

class TemporalVersionPublishOrchestratorTest {
  private static final String WORKFLOW_ID = "publish:tenant-1:publish-request:request-1";

  @Test
  void timeoutPreservesPendingReconciliationCodeFromLastSnapshot() {
    PublishWorkflowSnapshot snapshot =
        new PublishWorkflowSnapshot(
            0L,
            0,
            WORKFLOW_ID,
            "PENDING",
            TemporalVersionPublishOrchestrator.PENDING_RECONCILIATION_REQUIRED_CODE,
            "publication readback is incomplete");

    IllegalStateException thrown =
        TemporalVersionPublishOrchestrator.timeoutException(snapshot, WORKFLOW_ID);

    assertEquals(
        "PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED: publication readback is incomplete; "
            + "version publish workflow did not converge for workflowId="
            + WORKFLOW_ID,
        thrown.getMessage());
  }

  @Test
  void timeoutKeepsGenericCodeForDifferentLastSnapshotFailure() {
    PublishWorkflowSnapshot snapshot =
        new PublishWorkflowSnapshot(
            0L, 0, WORKFLOW_ID, "PENDING", "PUBLISH_ATTEMPT_INCONSISTENT", "other failure");

    IllegalStateException thrown =
        TemporalVersionPublishOrchestrator.timeoutException(snapshot, WORKFLOW_ID);

    assertEquals(
        "TEMPORAL_WORKFLOW_TIMEOUT: version publish workflow did not converge for workflowId="
            + WORKFLOW_ID,
        thrown.getMessage());
  }

  @Test
  void timeoutKeepsGenericCodeWhenNoSnapshotWasObserved() {
    IllegalStateException thrown =
        TemporalVersionPublishOrchestrator.timeoutException(null, WORKFLOW_ID);

    assertEquals(
        "TEMPORAL_WORKFLOW_TIMEOUT: version publish workflow did not converge for workflowId="
            + WORKFLOW_ID,
        thrown.getMessage());
  }

  @Test
  void knownGateFailureCodeIsPreservedAsPublishGateFailure() {
    PublishWorkflowSnapshot snapshot =
        new PublishWorkflowSnapshot(
            0L,
            0,
            WORKFLOW_ID,
            "FAILED",
            PublishGateFailureCode.PARTICIPANT_SET_MISMATCH.name(),
            "participant set mismatch");

    RuntimeException failure = TemporalVersionPublishOrchestrator.failureForSnapshot(snapshot);

    PublishGateFailureException gateFailure =
        assertInstanceOf(PublishGateFailureException.class, failure);
    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, gateFailure.failureCode());
    assertEquals("participant set mismatch", gateFailure.getMessage());
  }

  @Test
  void unknownOrBlankFailureCodeRemainsGeneric() {
    PublishWorkflowSnapshot unknownSnapshot =
        new PublishWorkflowSnapshot(
            0L, 0, WORKFLOW_ID, "FAILED", "UNKNOWN_FAILURE", "unknown failure");
    PublishWorkflowSnapshot blankSnapshot =
        new PublishWorkflowSnapshot(0L, 0, WORKFLOW_ID, "FAILED", "", "blank failure code");

    RuntimeException unknownFailure =
        TemporalVersionPublishOrchestrator.failureForSnapshot(unknownSnapshot);
    RuntimeException blankFailure =
        TemporalVersionPublishOrchestrator.failureForSnapshot(blankSnapshot);

    assertInstanceOf(IllegalStateException.class, unknownFailure);
    assertInstanceOf(IllegalStateException.class, blankFailure);
    assertEquals("unknown failure", unknownFailure.getMessage());
    assertEquals("blank failure code", blankFailure.getMessage());
  }
}
