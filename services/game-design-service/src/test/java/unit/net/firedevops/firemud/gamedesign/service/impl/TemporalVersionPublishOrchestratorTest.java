package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.temporal.client.WorkflowClient;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.service.PublishAttemptPendingReconciliationException;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.junit.jupiter.api.Test;

class TemporalVersionPublishOrchestratorTest {
  private static final String WORKFLOW_ID = "publish:tenant-1:publish-request:request-1";

  @Test
  void freshOrPendingPublicationIsDeniedBeforeTemporalWorkflowStart() {
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    TemporalTaskQueueResolver taskQueues = mock(TemporalTaskQueueResolver.class);
    VersionPublishCommandServiceImpl commandService = mock(VersionPublishCommandServiceImpl.class);
    when(commandService.publishFullVersion("tenant-1", "notes", "request-1", WORKFLOW_ID))
        .thenThrow(
            new VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException());
    TemporalVersionPublishOrchestrator orchestrator =
        new TemporalVersionPublishOrchestrator(workflowClient, taskQueues, commandService);

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () -> orchestrator.publishFullVersion("tenant-1", "notes", "request-1"));

    verify(commandService).publishFullVersion("tenant-1", "notes", "request-1", WORKFLOW_ID);
    verifyNoInteractions(workflowClient, taskQueues);
  }

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

    RuntimeException thrown =
        TemporalVersionPublishOrchestrator.timeoutException(snapshot, WORKFLOW_ID);

    PublishAttemptPendingReconciliationException pending =
        assertInstanceOf(PublishAttemptPendingReconciliationException.class, thrown);
    assertEquals(PublishAttemptPendingReconciliationException.ERROR_CODE, pending.errorCode());
    assertEquals(PublishAttemptPendingReconciliationException.SAFE_MESSAGE, pending.getMessage());
  }

  @Test
  void failedSnapshotWithPendingReconciliationCodeUsesTypedException() {
    PublishWorkflowSnapshot snapshot =
        new PublishWorkflowSnapshot(
            0L,
            0,
            WORKFLOW_ID,
            "FAILED",
            PublishAttemptPendingReconciliationException.ERROR_CODE,
            "internal workflow detail");

    RuntimeException thrown = TemporalVersionPublishOrchestrator.failureForSnapshot(snapshot);

    PublishAttemptPendingReconciliationException pending =
        assertInstanceOf(PublishAttemptPendingReconciliationException.class, thrown);
    assertEquals(PublishAttemptPendingReconciliationException.ERROR_CODE, pending.errorCode());
    assertEquals(PublishAttemptPendingReconciliationException.SAFE_MESSAGE, pending.getMessage());
  }

  @Test
  void timeoutKeepsGenericCodeForDifferentLastSnapshotFailure() {
    PublishWorkflowSnapshot snapshot =
        new PublishWorkflowSnapshot(
            0L, 0, WORKFLOW_ID, "PENDING", "PUBLISH_ATTEMPT_INCONSISTENT", "other failure");

    RuntimeException thrown =
        TemporalVersionPublishOrchestrator.timeoutException(snapshot, WORKFLOW_ID);

    assertInstanceOf(IllegalStateException.class, thrown);
    assertEquals(
        "TEMPORAL_WORKFLOW_TIMEOUT: version publish workflow did not converge for workflowId="
            + WORKFLOW_ID,
        thrown.getMessage());
  }

  @Test
  void timeoutKeepsGenericCodeWhenNoSnapshotWasObserved() {
    RuntimeException thrown =
        TemporalVersionPublishOrchestrator.timeoutException(null, WORKFLOW_ID);

    assertInstanceOf(IllegalStateException.class, thrown);
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

  @Test
  void genericFailureUsesStableFallbackWhenMessageAndCodeAreNullOrBlank() {
    PublishWorkflowSnapshot nullFieldsSnapshot =
        new PublishWorkflowSnapshot(0L, 0, WORKFLOW_ID, "FAILED", null, null);
    PublishWorkflowSnapshot blankFieldsSnapshot =
        new PublishWorkflowSnapshot(0L, 0, WORKFLOW_ID, "FAILED", "  ", "");
    PublishWorkflowSnapshot nullMessageBlankCodeSnapshot =
        new PublishWorkflowSnapshot(0L, 0, WORKFLOW_ID, "FAILED", " ", null);
    PublishWorkflowSnapshot blankMessageNullCodeSnapshot =
        new PublishWorkflowSnapshot(0L, 0, WORKFLOW_ID, "FAILED", null, "  ");

    assertEquals(
        "VERSION_PUBLISH_WORKFLOW_FAILED",
        TemporalVersionPublishOrchestrator.failureForSnapshot(nullFieldsSnapshot).getMessage());
    assertEquals(
        "VERSION_PUBLISH_WORKFLOW_FAILED",
        TemporalVersionPublishOrchestrator.failureForSnapshot(blankFieldsSnapshot).getMessage());
    assertEquals(
        "VERSION_PUBLISH_WORKFLOW_FAILED",
        TemporalVersionPublishOrchestrator.failureForSnapshot(nullMessageBlankCodeSnapshot)
            .getMessage());
    assertEquals(
        "VERSION_PUBLISH_WORKFLOW_FAILED",
        TemporalVersionPublishOrchestrator.failureForSnapshot(blankMessageNullCodeSnapshot)
            .getMessage());
  }
}
