package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class TemporalVersionPublishActivitiesImplTest {
  @Test
  void pendingReconciliationIsReturnedAsNonterminalSnapshot() {
    VersionPublishCommandServiceImpl commandService = mock(VersionPublishCommandServiceImpl.class);
    PublishWorkflowRequest request =
        new PublishWorkflowRequest(
            "tenant-1", "notes", "request-1", "publish:tenant-1:publish-request:request-1");
    when(commandService.reconcileFullVersionPublish(request))
        .thenThrow(
            new VersionPublishCommandServiceImpl.PendingReconciliationException(
                "committed publication readback could not be reconciled"));

    PublishWorkflowSnapshot snapshot =
        new TemporalVersionPublishActivitiesImpl(commandService).reconcile(request);

    assertEquals("PENDING", snapshot.status());
    assertEquals(request.publishWorkflowId(), snapshot.publishWorkflowId());
    assertEquals("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED", snapshot.failureCode());
    assertEquals(
        "committed publication readback could not be reconciled", snapshot.failureMessage());
    assertEquals(false, snapshot.isTerminal());
  }
}
