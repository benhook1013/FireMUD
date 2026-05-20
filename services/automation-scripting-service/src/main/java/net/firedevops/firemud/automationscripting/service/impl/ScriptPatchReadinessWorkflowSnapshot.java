package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;

public record ScriptPatchReadinessWorkflowSnapshot(
    String tenantId,
    String scriptPatchVersion,
    ScriptPatchStatus status,
    String statusReason,
    String supersededByScriptPatchVersion,
    long lastChangedAtMs) {
  public boolean isTerminal() {
    return switch (status) {
      case SCRIPT_PATCH_STATUS_READY,
          SCRIPT_PATCH_STATUS_FAILED,
          SCRIPT_PATCH_STATUS_ROLLED_BACK,
          SCRIPT_PATCH_STATUS_SUPERSEDED ->
          true;
      default -> false;
    };
  }
}
