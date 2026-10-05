package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import org.junit.jupiter.api.Test;

class TemporalVersionPublishActivitiesImplTest {
  @Test
  void pendingReconciliationIsReturnedAsNonterminalSnapshot() {
    VersionPublishCommandServiceImpl commandService = mock(VersionPublishCommandServiceImpl.class);
    // Synthetic typed payload for Temporal status translation only; it is not owner authorization.
    PublishIntent intent =
        new PublishIntent(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "request-1",
            "9",
            "notes",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "sha256:" + "a".repeat(64));
    PublishWorkflowRequest request =
        new PublishWorkflowRequest(
            "private-tenant-key",
            "notes",
            intent.publishRequestId(),
            TemporalVersionPublishOrchestrator.workflowId(
                intent.canonicalTenantId().toString(), intent.publishRequestId()),
            intent);
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
