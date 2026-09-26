package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;

public interface ScriptPatchReadinessProjectionService {
  /** Returns true only when this call admitted a previously unseen immutable script set. */
  boolean beginPatchReadiness(
      String tenantId, String scriptPatchVersion, List<String> canonicalScriptNames);

  /** Runs database downstream work only while this is the current readiness generation. */
  boolean applyIfCurrent(
      String tenantId,
      String scriptPatchVersion,
      List<String> canonicalScriptNames,
      Consumer<Boolean> downstreamWork);

  /** Runs process-local registry work after commit, fenced by the current readiness generation. */
  boolean rebuildRegistryIfCurrent(
      String tenantId,
      String scriptPatchVersion,
      List<String> canonicalScriptNames,
      Runnable registryRebuild);

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
