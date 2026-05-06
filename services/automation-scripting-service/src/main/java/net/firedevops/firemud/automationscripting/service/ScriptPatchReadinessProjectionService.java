package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;

public interface ScriptPatchReadinessProjectionService {
  void beginPatchReadiness(String tenantId, String scriptPatchVersion, int affectedScriptCount);

  void refreshFromOnLoadWorkItems(String tenantId, String scriptPatchVersion);

  Optional<ReadinessStatusSummary> getProjection(String tenantId, String scriptPatchVersion);

  List<ReadinessStatusSummary> listProjections(String tenantId);

  record ReadinessStatusSummary(
      String tenantId,
      String scriptPatchVersion,
      ScriptPatchStatus status,
      String statusReason,
      String supersededByScriptPatchVersion,
      long lastChangedAtMs) {}
}
