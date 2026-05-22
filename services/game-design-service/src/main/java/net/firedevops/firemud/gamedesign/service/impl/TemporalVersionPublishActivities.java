package net.firedevops.firemud.gamedesign.service.impl;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface TemporalVersionPublishActivities {
  @ActivityMethod
  PublishWorkflowSnapshot reconcile(PublishWorkflowRequest request);
}
