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
    return commandService.reconcileFullVersionPublish(request);
  }
}
