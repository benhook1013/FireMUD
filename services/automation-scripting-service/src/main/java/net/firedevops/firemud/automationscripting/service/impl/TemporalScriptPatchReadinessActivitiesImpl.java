package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

@Component
public class TemporalScriptPatchReadinessActivitiesImpl
    implements TemporalScriptPatchReadinessActivities {
  private static final Logger logger =
      LoggingUtil.getLogger(TemporalScriptPatchReadinessActivitiesImpl.class);

  private final ScriptPatchReadinessProjectionService readinessProjectionService;

  public TemporalScriptPatchReadinessActivitiesImpl(
      ScriptPatchReadinessProjectionService readinessProjectionService) {
    this.readinessProjectionService = readinessProjectionService;
  }

  @Override
  public ScriptPatchReadinessWorkflowSnapshot refreshAndLoadStatus(
      String tenantId, String scriptPatchVersion) {
    String workflowId = workflowId(tenantId, scriptPatchVersion);
    logger.info(
        "Executing Temporal script patch readiness step workflowId={} businessStepKey={}",
        workflowId,
        FiremudWorkflowIds.businessStepKey(workflowId, "refresh-status", scriptPatchVersion));
    readinessProjectionService.refreshFromOnLoadWorkItems(tenantId, scriptPatchVersion);
    return readinessProjectionService
        .getProjection(tenantId, scriptPatchVersion)
        .map(
            summary ->
                new ScriptPatchReadinessWorkflowSnapshot(
                    summary.tenantId(),
                    summary.scriptPatchVersion(),
                    summary.status(),
                    summary.statusReason(),
                    summary.supersededByScriptPatchVersion(),
                    summary.lastChangedAtMs()))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "SCRIPT_PATCH_READINESS_NOT_FOUND: tenantId="
                        + tenantId
                        + ", scriptPatchVersion="
                        + scriptPatchVersion));
  }

  static String workflowId(String tenantId, String scriptPatchVersion) {
    return FiremudWorkflowIds.workflowId(
        TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY,
        tenantId,
        "script-patch-version",
        scriptPatchVersion);
  }
}
