package net.firedevops.firemud.hostedidentity.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.model.HostedCondition;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.Phase;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.RoleStatus;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.RuntimeProfile;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Builds non-secret status and binds Ready to the exact runtime namespace/head tuple. */
@Component
public class HostedStatusService {
  private static final Logger LOGGER = LoggerFactory.getLogger(HostedStatusService.class);
  private final EnvironmentIdentityPlanner planner;

  public HostedStatusService(EnvironmentIdentityPlanner planner) {
    this.planner = planner;
  }

  public HostedEnvironmentIdentityStatus status(
      HostedEnvironmentIdentity resource,
      Phase phase,
      String reason,
      String message,
      boolean ready,
      RuntimeProfileService.RuntimeProfile runtimeProfile,
      RoleStatus ingress,
      RoleStatus telnet,
      RoleStatus grpc) {
    HostedEnvironmentIdentityStatus previous = resource.getStatus();
    return status(
        resource,
        phase,
        reason,
        message,
        ready,
        runtimeProfile,
        ingress,
        telnet,
        previous == null ? null : previous.getGatewayInternalWs(),
        previous == null ? null : previous.getTcpProxyBridge(),
        grpc);
  }

  public HostedEnvironmentIdentityStatus status(
      HostedEnvironmentIdentity resource,
      Phase phase,
      String reason,
      String message,
      boolean ready,
      RuntimeProfileService.RuntimeProfile runtimeProfile,
      RoleStatus ingress,
      RoleStatus telnet,
      RoleStatus gatewayInternalWs,
      RoleStatus tcpProxyBridge,
      RoleStatus grpc) {
    HostedEnvironmentIdentityStatus status =
        resource.getStatus() == null ? new HostedEnvironmentIdentityStatus() : resource.getStatus();
    HostedCondition previousReady =
        status.getConditions() == null
            ? null
            : status.getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .orElse(null);
    RuntimeProfile previousProfile = status.getProfile();
    boolean profileChanged = !profileMatches(previousProfile, runtimeProfile);
    boolean identityEvidenceComplete =
        roleReady(ingress)
            && roleReady(telnet)
            && roleReady(gatewayInternalWs)
            && roleReady(tcpProxyBridge)
            && roleReady(grpc);
    boolean deploymentEvidenceCurrent =
        runtimeProfile == null || runtimeProfile.deployedHeadMatchesRequest();
    boolean effectiveReady =
        ready && deploymentEvidenceCurrent && !profileChanged && identityEvidenceComplete;
    if (ready && !deploymentEvidenceCurrent) {
      reason = "RuntimeDeploymentPending";
      message = "deployed runtime head must match the requested head";
      if (phase == Phase.Ready) {
        phase = Phase.Pending;
      }
    } else if (profileChanged && ready) {
      reason = "RuntimeIdentityChanged";
      message =
          "runtime Namespace UID, requested head, or deployed head changed; fresh convergence is required";
      if (phase == Phase.Ready) {
        phase = Phase.Pending;
      }
    } else if (ready && !identityEvidenceComplete) {
      reason = "IdentityEvidenceIncomplete";
      message = "all five controller-managed identity revisions are required for readiness";
      if (phase == Phase.Ready) {
        phase = Phase.Pending;
      }
    }
    status.setObservedGeneration(resource.getMetadata().getGeneration());
    status.setPhase(phase);
    status.setIngress(ingress);
    status.setTelnet(telnet);
    status.setGatewayInternalWs(gatewayInternalWs);
    status.setTcpProxyBridge(tcpProxyBridge);
    status.setGrpc(grpc);
    RuntimeProfile profile = new RuntimeProfile();
    try {
      var plan = planner.plan(resource.getMetadata().getName());
      profile.setName(plan.name());
      profile.setEnvironmentClass(
          "dev-demo".equals(plan.name()) ? "dev-demo-cluster" : "pr-preview");
      profile.setIdentityNamespace(plan.identityNamespace());
      profile.setRuntimeNamespace(plan.runtimeNamespace());
      profile.setHostname(plan.hostname());
    } catch (RuntimeException exception) {
      LOGGER.warn(
          "Unable to plan hosted identity resource '{}'; preserving previous runtime profile",
          resource.getMetadata().getName(),
          exception);
      if (previousProfile != null) {
        profile.setName(previousProfile.getName());
        profile.setEnvironmentClass(previousProfile.getEnvironmentClass());
        profile.setIdentityNamespace(previousProfile.getIdentityNamespace());
        profile.setRuntimeNamespace(previousProfile.getRuntimeNamespace());
        profile.setHostname(previousProfile.getHostname());
      }
    }
    if (runtimeProfile != null && runtimeProfile.present()) {
      profile.setTelnetPort(runtimeProfile.telnetPort());
      profile.setRuntimeNamespaceUid(runtimeProfile.runtimeNamespaceUid());
      profile.setRequestedHeadSha(runtimeProfile.requestedHeadSha());
      profile.setDeployedHeadSha(runtimeProfile.deployedHeadSha());
    } else if (previousProfile != null) {
      profile.setTelnetPort(previousProfile.getTelnetPort());
      profile.setRuntimeNamespaceUid(previousProfile.getRuntimeNamespaceUid());
      profile.setRequestedHeadSha(previousProfile.getRequestedHeadSha());
      profile.setDeployedHeadSha(previousProfile.getDeployedHeadSha());
    }
    status.setProfile(profile);
    HostedCondition condition =
        new HostedCondition("Ready", effectiveReady ? "True" : "False", reason, message);
    condition.setObservedGeneration(resource.getMetadata().getGeneration());
    condition.setLastTransitionTime(
        sameConditionStatus(previousReady, condition)
                && previousReady.getLastTransitionTime() != null
                && !previousReady.getLastTransitionTime().isBlank()
            ? previousReady.getLastTransitionTime()
            : Instant.now().toString());
    status.setConditions(List.of(condition));
    return status;
  }

  private static boolean roleReady(RoleStatus role) {
    return role != null && role.getRevision() != null && !role.getRevision().isBlank();
  }

  private static boolean sameConditionStatus(HostedCondition previous, HostedCondition current) {
    return previous != null && Objects.equals(previous.getStatus(), current.getStatus());
  }

  public static RoleStatus role(
      String revision,
      Long sourceGeneration,
      Long sourceObjectGeneration,
      String spkiSha256,
      String provenance,
      String state) {
    RoleStatus result = new RoleStatus();
    result.setRevision(revision);
    result.setSourceGeneration(sourceGeneration);
    result.setSourceObjectGeneration(sourceObjectGeneration);
    result.setSpkiSha256(spkiSha256);
    result.setProvenance(provenance);
    result.setState(state);
    return result;
  }

  static boolean profileMatches(
      RuntimeProfile previous, RuntimeProfileService.RuntimeProfile current) {
    if (current == null) {
      return true;
    }
    if (previous == null || !current.present()) {
      return previous == null && !currentPresent(current);
    }
    return Objects.equals(current.runtimeNamespaceUid(), previous.getRuntimeNamespaceUid())
        && Objects.equals(current.requestedHeadSha(), previous.getRequestedHeadSha())
        && Objects.equals(current.deployedHeadSha(), previous.getDeployedHeadSha())
        && Objects.equals(current.telnetPort(), previous.getTelnetPort());
  }

  private static boolean currentPresent(RuntimeProfileService.RuntimeProfile current) {
    return current != null && current.present();
  }
}
