package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;

public interface ScriptGameplayCommandHandoffService {
  HandoffResult handoff(ScriptWorkItem workItem, EmittedCommand command);

  record EmittedCommand(
      String commandText, boolean requiresSoloTick, long dueTickId, int ordinal) {}

  record HandoffResult(boolean accepted, String outcome, String commandId, String errorCode) {}
}
