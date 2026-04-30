package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;

public interface ScriptEventIngressService {
  TriggerAdmission admit(TriggerScriptEventRequest request);

  TriggerAdmission admit(TriggerScriptEventRequest request, String sourceService);

  record TriggerAdmission(
      boolean admitted, String outcome, String reason, int resolvedHandlerCount) {}
}
