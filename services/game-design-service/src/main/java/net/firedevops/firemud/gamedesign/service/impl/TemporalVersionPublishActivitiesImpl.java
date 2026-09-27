package net.firedevops.firemud.gamedesign.service.impl;

import org.springframework.stereotype.Component;

@Component
public class TemporalVersionPublishActivitiesImpl implements TemporalVersionPublishActivities {
  private final VersionPublishCommandServiceImpl commandService;

  public TemporalVersionPublishActivitiesImpl(VersionPublishCommandServiceImpl commandService) {
    this.commandService = commandService;
  }

  @Override
  public PublishWorkflowSnapshot reconcile(PublishWorkflowRequest request) {
    try {
      return commandService.reconcileFullVersionPublish(request);
    } catch (VersionPublishCommandServiceImpl.PendingReconciliationException ex) {
      // Unresolved publication evidence is durable pending state, not an activity failure. The
      // workflow's existing nonterminal loop will retry the exact request identity without
      // terminalizing or guessing about the committed outcome.
      return new PublishWorkflowSnapshot(
          0L,
          0,
          request.publishWorkflowId(),
          "PENDING",
          "PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED",
          ex.getMessage());
    }
  }
}
