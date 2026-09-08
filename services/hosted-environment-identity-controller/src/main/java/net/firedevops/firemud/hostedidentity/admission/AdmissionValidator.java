package net.firedevops.firemud.hostedidentity.admission;

import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.springframework.stereotype.Component;

/** Repeats the closed, fail-closed admission contract at reconciliation time. */
@Component
public class AdmissionValidator {
  private final EnvironmentIdentityPlanner planner;

  public AdmissionValidator(EnvironmentIdentityPlanner planner) {
    this.planner = planner;
  }

  public void validate(HostedEnvironmentIdentity resource) {
    if (resource == null || resource.getMetadata() == null) {
      throw new IllegalArgumentException("HostedEnvironmentIdentity metadata is required");
    }
    if (!planner.controlNamespace().equals(resource.getMetadata().getNamespace())) {
      throw new IllegalArgumentException(
          "HostedEnvironmentIdentity must be in " + planner.controlNamespace());
    }
    if (!HostedIdentityContract.KIND.equals(resource.getKind())
        || !HostedIdentityContract.API_VERSION_NAME.equals(resource.getApiVersion())) {
      throw new IllegalArgumentException(
          "unsupported HostedEnvironmentIdentity apiVersion or kind");
    }
    planner.plan(resource.getMetadata().getName());
    if (resource.getSpec() == null || resource.getSpec().getDesiredState() == null) {
      throw new IllegalArgumentException("spec.desiredState is required");
    }
    if (resource.getStatus() != null
        && net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.Phase.Retired
            .equals(resource.getStatus().getPhase())
        && resource.getSpec().getDesiredState()
            == net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec
                .DesiredState.Active) {
      throw new IllegalArgumentException("a Retired identity cannot be reactivated");
    }
    if (resource.getMetadata().getOwnerReferences() != null
        && !resource.getMetadata().getOwnerReferences().isEmpty()) {
      throw new IllegalArgumentException("HostedEnvironmentIdentity cannot claim an owner");
    }
    if (hasReservedMetadata(resource.getMetadata().getLabels())
        || hasReservedMetadata(resource.getMetadata().getAnnotations())) {
      throw new IllegalArgumentException(
          "HostedEnvironmentIdentity cannot claim reserved metadata");
    }
  }

  private static boolean hasReservedMetadata(java.util.Map<String, String> values) {
    return values != null
        && values.keySet().stream()
            .anyMatch(key -> key.startsWith("firemud.dev/") || key.startsWith("firemud.io/"));
  }
}
