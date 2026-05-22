package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;

public interface ScriptGameplayCommandHandoffService {
  HandoffResult handoff(ScriptWorkItem workItem, EmittedCommand command);

  record EmittedCommand(
      String commandText,
      String targetEntityId,
      String targetGameInstanceId,
      String targetRegionId,
      Long targetRegionEpoch,
      boolean requiresSoloTick,
      long dueTickId,
      int ordinal) {}

  record HandoffResult(
      boolean accepted,
      String outcome,
      String commandId,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String errorCode) {}
}
