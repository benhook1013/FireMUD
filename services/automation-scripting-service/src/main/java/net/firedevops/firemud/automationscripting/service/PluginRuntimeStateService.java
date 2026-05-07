package net.firedevops.firemud.automationscripting.service;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;

public interface PluginRuntimeStateService {
  Optional<PluginRuntimeStatus> getStatus(String tenantId, String gameInstanceId, String pluginId);

  ActivationResult setActiveVersion(ActivationCommand command);

  boolean disable(PluginStateCommand command);

  boolean drain(PluginStateCommand command);

  PolicyReconciliationResult reconcileActivePluginPolicy(int maxItems);

  PluginPolicyConvergence getPluginPolicyConvergence(
      String tenantId, String gameInstanceId, int maxResults);

  java.util.List<PluginRuntimeEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      PluginState pluginState,
      String activePluginVersionId,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

  record ActivationCommand(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      String targetPluginVersionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}

  record PluginStateCommand(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}

  record ActivationResult(
      String previousPluginVersionId, String activePluginVersionId, String controlPlaneRequestId) {}

  record PolicyReconciliationResult(int inspectedCount, int disabledCount) {}

  record PluginPolicyConvergence(
      int inspectedCount,
      int failClosedCount,
      boolean converged,
      long evaluatedAtMs,
      java.util.List<PluginPolicyViolation> violations) {
    public PluginPolicyConvergence {
      violations = java.util.List.copyOf(violations);
    }
  }

  record PluginPolicyViolation(
      String gameInstanceId,
      String runtimeRegionId,
      long runtimeRegionEpoch,
      String pluginId,
      String activePluginVersionId,
      String reason,
      long lastChangedAtMs,
      PluginPublicationLink activePublication) {}

  record PluginRuntimeEventSummary(
      String eventId,
      String tenantId,
      String gameInstanceId,
      String runtimeRegionId,
      long runtimeRegionEpoch,
      String pluginId,
      String previousPluginVersionId,
      String activePluginVersionId,
      PluginState pluginState,
      String statusReason,
      String controlPlaneRequestId,
      String actorPrincipal,
      long observedAtMs,
      PluginPublicationLink previousPublication,
      PluginPublicationLink activePublication) {}

  record PluginRuntimeStatus(
      String activePluginVersionId,
      String pendingPluginVersionId,
      String runtimeRegionId,
      long runtimeRegionEpoch,
      PluginState pluginState,
      String statusReason,
      long lastChangedAtMs,
      String controlPlaneRequestId,
      String actorPrincipal,
      long lastPolicyCheckedAtMs,
      PluginPublicationLink activePublication,
      PluginPublicationLink pendingPublication) {}

  record PluginPublicationLink(
      String pluginVersionId,
      long publicationId,
      VersionLifecycleState publicationState,
      String statusReason,
      long lastChangedAtMs,
      String lookupErrorCode,
      String lookupErrorMessage) {}
}
