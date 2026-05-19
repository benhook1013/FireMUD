package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.v1.DisablePluginRequest;
import net.firedevops.firemud.automationscripting.v1.DisablePluginResponse;
import net.firedevops.firemud.automationscripting.v1.DrainPluginRequest;
import net.firedevops.firemud.automationscripting.v1.DrainPluginResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsResponse;
import net.firedevops.firemud.automationscripting.v1.PluginPolicyViolation;
import net.firedevops.firemud.automationscripting.v1.PluginPublicationLink;
import net.firedevops.firemud.automationscripting.v1.PluginRuntimeEventEntry;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionRequest;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionResponse;
import org.springframework.stereotype.Service;

@Service
final class AutomationPluginControlPlaneService {
  private final PluginRuntimeStateService pluginRuntimeStateService;
  private final ScriptRuntimeProperties runtimeProperties;

  AutomationPluginControlPlaneService(
      PluginRuntimeStateService pluginRuntimeStateService,
      ScriptRuntimeProperties runtimeProperties) {
    this.pluginRuntimeStateService = pluginRuntimeStateService;
    this.runtimeProperties = runtimeProperties;
  }

  GetPluginStatusResponse getPluginStatus(GetPluginStatusRequest request) {
    GetPluginStatusResponse.Builder response = GetPluginStatusResponse.newBuilder();
    pluginRuntimeStateService
        .getStatus(request.getTenantId(), request.getGameInstanceId(), request.getPluginId())
        .ifPresentOrElse(
            status -> {
              response
                  .setActivePluginVersionId(status.activePluginVersionId())
                  .setPendingPluginVersionId(status.pendingPluginVersionId())
                  .setRuntimeRegionId(status.runtimeRegionId())
                  .setRuntimeRegionEpoch(status.runtimeRegionEpoch())
                  .setPluginState(status.pluginState())
                  .setStatusReason(status.statusReason())
                  .setLastChangedAtMs(status.lastChangedAtMs())
                  .setControlPlaneRequestId(status.controlPlaneRequestId())
                  .setActorPrincipal(status.actorPrincipal())
                  .setLastPolicyCheckedAtMs(status.lastPolicyCheckedAtMs())
                  .setPolicyCheckStale(isPolicyCheckStale(status.lastPolicyCheckedAtMs()));
              if (status.activePublication() != null) {
                response.setActivePublication(toProto(status.activePublication()));
              }
              if (status.pendingPublication() != null) {
                response.setPendingPublication(toProto(status.pendingPublication()));
              }
            },
            () ->
                response.setError(
                    AutomationControlPlaneSupport.notFound(
                        "GetPluginStatus", "plugin_runtime_state_not_found")));
    return response.build();
  }

  ListPluginRuntimeEventsResponse listPluginRuntimeEvents(ListPluginRuntimeEventsRequest request) {
    ListPluginRuntimeEventsResponse.Builder response = ListPluginRuntimeEventsResponse.newBuilder();
    pluginRuntimeStateService
        .listEvents(
            request.getTenantId(),
            request.getGameInstanceId(),
            request.getPluginId(),
            request.getPluginState(),
            request.getActivePluginVersionId(),
            request.getChangedAfterMs(),
            request.getChangedBeforeMs(),
            request.getLimit())
        .stream()
        .map(AutomationPluginControlPlaneService::toProto)
        .forEach(response::addEvents);
    return response.build();
  }

  GetPluginPolicyConvergenceResponse getPluginPolicyConvergence(
      GetPluginPolicyConvergenceRequest request) {
    PluginRuntimeStateService.PluginPolicyConvergence convergence =
        pluginRuntimeStateService.getPluginPolicyConvergence(
            request.getTenantId(), request.getGameInstanceId(), request.getMaxResults());
    GetPluginPolicyConvergenceResponse.Builder response =
        GetPluginPolicyConvergenceResponse.newBuilder()
            .setInspectedCount(convergence.inspectedCount())
            .setFailClosedCount(convergence.failClosedCount())
            .setConverged(convergence.converged())
            .setEvaluatedAtMs(convergence.evaluatedAtMs())
            .setIsStale(isPolicyCheckStale(convergence.evaluatedAtMs()));
    convergence.violations().stream()
        .map(AutomationPluginControlPlaneService::toProto)
        .forEach(response::addViolations);
    return response.build();
  }

  SetPluginActiveVersionResponse setPluginActiveVersion(SetPluginActiveVersionRequest request) {
    PluginRuntimeStateService.ActivationResult result =
        pluginRuntimeStateService.setActiveVersion(
            new PluginRuntimeStateService.ActivationCommand(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getPluginId(),
                request.getTargetPluginVersionId(),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return SetPluginActiveVersionResponse.newBuilder()
        .setPreviousPluginVersionId(result.previousPluginVersionId())
        .setActivePluginVersionId(result.activePluginVersionId())
        .setControlPlaneRequestId(result.controlPlaneRequestId())
        .build();
  }

  DisablePluginResponse disablePlugin(DisablePluginRequest request) {
    boolean success =
        pluginRuntimeStateService.disable(
            new PluginRuntimeStateService.PluginStateCommand(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getPluginId(),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return DisablePluginResponse.newBuilder().setSuccess(success).build();
  }

  DrainPluginResponse drainPlugin(DrainPluginRequest request) {
    boolean success =
        pluginRuntimeStateService.drain(
            new PluginRuntimeStateService.PluginStateCommand(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getPluginId(),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return DrainPluginResponse.newBuilder().setSuccess(success).build();
  }

  private boolean isPolicyCheckStale(long lastPolicyCheckedAtMs) {
    long ageMs = Instant.now().toEpochMilli() - lastPolicyCheckedAtMs;
    return ageMs > runtimeProperties.getPluginPolicyStaleThresholdSeconds() * 1_000L;
  }

  private static PluginPolicyViolation toProto(
      PluginRuntimeStateService.PluginPolicyViolation violation) {
    PluginPolicyViolation.Builder builder =
        PluginPolicyViolation.newBuilder()
            .setGameInstanceId(violation.gameInstanceId())
            .setRuntimeRegionId(violation.runtimeRegionId())
            .setRuntimeRegionEpoch(violation.runtimeRegionEpoch())
            .setPluginId(violation.pluginId())
            .setActivePluginVersionId(violation.activePluginVersionId())
            .setReason(violation.reason())
            .setLastChangedAtMs(violation.lastChangedAtMs());
    if (violation.activePublication() != null) {
      builder.setActivePublication(toProto(violation.activePublication()));
    }
    return builder.build();
  }

  private static PluginPublicationLink toProto(
      PluginRuntimeStateService.PluginPublicationLink link) {
    return PluginPublicationLink.newBuilder()
        .setPluginVersionId(link.pluginVersionId())
        .setPublicationId(link.publicationId())
        .setPublicationState(link.publicationState())
        .setStatusReason(link.statusReason())
        .setLastChangedAtMs(link.lastChangedAtMs())
        .setLookupErrorCode(link.lookupErrorCode())
        .setLookupErrorMessage(link.lookupErrorMessage())
        .build();
  }

  private static PluginRuntimeEventEntry toProto(
      PluginRuntimeStateService.PluginRuntimeEventSummary summary) {
    PluginRuntimeEventEntry.Builder builder =
        PluginRuntimeEventEntry.newBuilder()
            .setEventId(summary.eventId())
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setRuntimeRegionId(summary.runtimeRegionId())
            .setRuntimeRegionEpoch(summary.runtimeRegionEpoch())
            .setPluginId(summary.pluginId())
            .setPreviousPluginVersionId(summary.previousPluginVersionId())
            .setActivePluginVersionId(summary.activePluginVersionId())
            .setPluginState(summary.pluginState())
            .setStatusReason(summary.statusReason())
            .setControlPlaneRequestId(summary.controlPlaneRequestId())
            .setActorPrincipal(summary.actorPrincipal())
            .setObservedAtMs(summary.observedAtMs());
    if (summary.previousPublication() != null) {
      builder.setPreviousPublication(toProto(summary.previousPublication()));
    }
    if (summary.activePublication() != null) {
      builder.setActivePublication(toProto(summary.activePublication()));
    }
    return builder.build();
  }
}
