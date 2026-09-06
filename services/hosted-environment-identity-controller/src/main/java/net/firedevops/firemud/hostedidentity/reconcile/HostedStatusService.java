package net.firedevops.firemud.hostedidentity.reconcile;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.model.HostedCondition;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.Phase;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.RoleStatus;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.RuntimeProfile;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.springframework.stereotype.Component;

/** Builds non-secret status and binds Ready to the exact runtime namespace/head tuple. */
@Component
public class HostedStatusService {
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
    HostedEnvironmentIdentityStatus status =
        resource.getStatus() == null ? new HostedEnvironmentIdentityStatus() : resource.getStatus();
    boolean profileChanged = !profileMatches(status.getProfile(), runtimeProfile);
    boolean effectiveReady = ready && !profileChanged;
    if (profileChanged && ready) {
      reason = "RuntimeIdentityChanged";
      message = "runtime Namespace UID or deployed head changed; fresh convergence is required";
      if (phase == Phase.Ready) {
        phase = Phase.Pending;
      }
    }
    status.setObservedGeneration(resource.getMetadata().getGeneration());
    status.setPhase(phase);
    status.setIngress(ingress);
    status.setTelnet(telnet);
    status.setGrpc(grpc);
    RuntimeProfile profile = new RuntimeProfile();
    var plan = planner.plan(resource.getMetadata().getName());
    profile.setName(plan.name());
    profile.setEnvironmentClass("dev-demo".equals(plan.name()) ? "dev-demo-cluster" : "pr-preview");
    profile.setIdentityNamespace(plan.identityNamespace());
    profile.setRuntimeNamespace(plan.runtimeNamespace());
    profile.setHostname(plan.hostname());
    if (runtimeProfile != null && runtimeProfile.present()) {
      profile.setTelnetPort(runtimeProfile.telnetPort());
      profile.setRuntimeNamespaceUid(runtimeProfile.runtimeNamespaceUid());
      profile.setDeployedHeadSha(runtimeProfile.deployedHeadSha());
    }
    status.setProfile(profile);
    HostedCondition condition =
        new HostedCondition("Ready", effectiveReady ? "True" : "False", reason, message);
    condition.setObservedGeneration(resource.getMetadata().getGeneration());
    condition.setLastTransitionTime(Instant.now().toString());
    status.setConditions(List.of(condition));
    return status;
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
    if (previous == null || current == null || !current.present()) {
      return previous == null && !currentPresent(current);
    }
    return current.runtimeNamespaceUid().equals(previous.getRuntimeNamespaceUid())
        && current.deployedHeadSha().equals(previous.getDeployedHeadSha());
  }

  private static boolean currentPresent(RuntimeProfileService.RuntimeProfile current) {
    return current != null && current.present();
  }
}
