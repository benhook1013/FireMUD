package net.firedevops.firemud.automationscripting.service.impl;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface TemporalScriptPatchReadinessActivities {
  @ActivityMethod
  ScriptPatchReadinessWorkflowSnapshot refreshAndLoadStatus(
      String tenantId, String scriptPatchVersion);
}
