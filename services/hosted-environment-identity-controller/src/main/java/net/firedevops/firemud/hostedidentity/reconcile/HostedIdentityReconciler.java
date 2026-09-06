package net.firedevops.firemud.hostedidentity.reconcile;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.admission.AdmissionValidator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService;
import net.firedevops.firemud.hostedidentity.kubernetes.DeploymentRolloutService;
import net.firedevops.firemud.hostedidentity.kubernetes.HostedIdentityScopeService;
import net.firedevops.firemud.hostedidentity.kubernetes.ResourceContexts;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.kubernetes.SecretProjectionService;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.springframework.stereotype.Component;

/** Narrow, periodic reconciler for one closed HostedEnvironmentIdentity resource. */
@Component
@ControllerConfiguration(
    finalizerName = HostedIdentityContract.FINALIZER,
    informer = @Informer(namespaces = {"firemud-system"}))
public class HostedIdentityReconciler implements Reconciler<HostedEnvironmentIdentity> {
  private final KubernetesClient client;
  private final AdmissionValidator admissionValidator;
  private final EnvironmentIdentityPlanner planner;
  private final CertificateMaterialService certificateMaterialService;
  private final SecretProjectionService projectionService;
  private final HostedIdentityScopeService scopeService;
  private final RuntimeProfileService runtimeProfileService;
  private final DeploymentRolloutService deploymentRolloutService;
  private final ServedEnvironmentProbe servedEnvironmentProbe;
  private final HostedStatusService statusService;
  private final HostedIdentityProperties properties;

  public HostedIdentityReconciler(
      KubernetesClient client,
      AdmissionValidator admissionValidator,
      EnvironmentIdentityPlanner planner,
      CertificateMaterialService certificateMaterialService,
      SecretProjectionService projectionService,
      HostedIdentityScopeService scopeService,
      RuntimeProfileService runtimeProfileService,
      DeploymentRolloutService deploymentRolloutService,
      ServedEnvironmentProbe servedEnvironmentProbe,
      HostedStatusService statusService,
      HostedIdentityProperties properties) {
    this.client = client;
    this.admissionValidator = admissionValidator;
    this.planner = planner;
    this.certificateMaterialService = certificateMaterialService;
    this.projectionService = projectionService;
    this.scopeService = scopeService;
    this.runtimeProfileService = runtimeProfileService;
    this.deploymentRolloutService = deploymentRolloutService;
    this.servedEnvironmentProbe = servedEnvironmentProbe;
    this.statusService = statusService;
    this.properties = properties;
  }

  @Override
  public UpdateControl<HostedEnvironmentIdentity> reconcile(
      HostedEnvironmentIdentity resource, Context<HostedEnvironmentIdentity> context) {
    try {
      admissionValidator.validate(resource);
      EnvironmentIdentityPlan plan = planner.plan(resource.getMetadata().getName());
      HostedIdentityProperties.ActivationMode activationMode = properties.activationMode();
      if (activationMode != HostedIdentityProperties.ActivationMode.ACTIVE) {
        return status(
            resource,
            activationMode == HostedIdentityProperties.ActivationMode.PAUSED
                ? HostedEnvironmentIdentityStatus.Phase.Blocked
                : HostedEnvironmentIdentityStatus.Phase.Pending,
            activationMode == HostedIdentityProperties.ActivationMode.PAUSED
                ? "ActivationPaused"
                : "ObserveOnly",
            activationMode == HostedIdentityProperties.ActivationMode.PAUSED
                ? "activation mode is paused; no materialization or finalizer changes are allowed"
                : "activation mode is observe; reconciliation is non-materializing",
            false,
            null,
            null,
            null,
            null);
      }
      if (isRetiring(resource)) {
        return retire(resource, plan, context);
      }
      RuntimeProfileService.RuntimeProfile runtimeProfile =
          runtimeProfileService.read(client, plan);
      if (!runtimeProfile.present()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.RuntimeAbsent,
            "RuntimeAbsent",
            "runtime Namespace is absent; retained source material",
            false,
            runtimeProfile,
            null,
            null,
            null);
      }
      ensureFinalizer(resource, context);
      scopeService.ensure(client, plan);

      CertificateMaterialService.RoleMaterial ingress =
          certificateMaterialService.ingress(client, plan);
      if (!ingress.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            ingress.state(),
            ingress.state(),
            false,
            runtimeProfile,
            ingress,
            null,
            null);
      }
      validateSourceProgress(ingress, previousRole(resource, HostedIdentityContract.INGRESS_ROLE));
      CertificateMaterialService.RoleMaterial telnet =
          certificateMaterialService.telnet(client, plan);
      if (!telnet.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            telnet.state(),
            telnet.state(),
            false,
            runtimeProfile,
            ingress,
            telnet,
            null);
      }
      validateSourceProgress(telnet, previousRole(resource, HostedIdentityContract.TELNET_ROLE));
      if (ingress.summary().spkiSha256().equals(telnet.summary().spkiSha256())) {
        throw new IllegalStateException(
            "ingress and Telnet certificates must use independent keys");
      }
      Long acceptedGrpcGeneration =
          resource.getStatus() == null || resource.getStatus().getGrpc() == null
              ? null
              : resource.getStatus().getGrpc().getSourceGeneration();
      CertificateMaterialService.RoleMaterial grpc =
          certificateMaterialService.grpc(client, plan, acceptedGrpcGeneration);
      if (!grpc.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            grpc.state(),
            grpc.state(),
            false,
            runtimeProfile,
            ingress,
            telnet,
            grpc);
      }
      validateSourceProgress(grpc, previousRole(resource, HostedIdentityContract.GRPC_ROLE));

      SecretProjectionService.ProjectionResult ingressProjection =
          project(plan, ingress, HostedIdentityContract.INGRESS_ROLE);
      SecretProjectionService.ProjectionResult telnetProjection =
          project(plan, telnet, HostedIdentityContract.TELNET_ROLE);
      SecretProjectionService.ProjectionResult grpcProjection =
          project(plan, grpc, HostedIdentityContract.GRPC_ROLE);
      boolean projectionsReady =
          ingressProjection.isSynced() && telnetProjection.isSynced() && grpcProjection.isSynced();
      DeploymentRolloutService.RolloutResult rollout =
          deploymentRolloutService.sync(
              client, plan, telnetProjection.revision(), grpcProjection.revision());
      ServedEnvironmentProbe.ProbeResult probes =
          servedEnvironmentProbe.probe(
              plan,
              runtimeProfile.telnetPort(),
              ingress.summary().certificateFingerprint(),
              telnet.summary().certificateFingerprint());
      if (rollout.ready() && probes.ready()) {
        ingressProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.INGRESS_ROLE,
                ingressProjection.revision(),
                ingress.sourceGeneration(),
                ingress.sourceObjectGeneration(),
                ingress.summary().spkiSha256());
        telnetProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.TELNET_ROLE,
                telnetProjection.revision(),
                telnet.sourceGeneration(),
                telnet.sourceObjectGeneration(),
                telnet.summary().spkiSha256());
        grpcProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.GRPC_ROLE,
                grpcProjection.revision(),
                grpc.sourceGeneration(),
                grpc.sourceObjectGeneration(),
                grpc.summary().spkiSha256());
      }
      projectionsReady =
          ingressProjection.isSynced() && telnetProjection.isSynced() && grpcProjection.isSynced();
      boolean ready = projectionsReady && rollout.ready() && probes.ready();
      String reason =
          ready
              ? "Reconciled"
              : !projectionsReady
                  ? "AwaitingAcceptance"
                  : !rollout.ready() ? "RolloutPending" : "ServedProbePending";
      HostedEnvironmentIdentityStatus.Phase phase =
          ready
              ? HostedEnvironmentIdentityStatus.Phase.Ready
              : !projectionsReady
                  ? HostedEnvironmentIdentityStatus.Phase.Syncing
                  : HostedEnvironmentIdentityStatus.Phase.Verifying;
      return status(
          resource, phase, reason, probes.reason(), ready, runtimeProfile, ingress, telnet, grpc);
    } catch (Exception exception) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Blocked,
          "ReconciliationBlocked",
          boundedMessage(exception),
          false,
          null,
          null,
          null,
          null);
    }
  }

  private SecretProjectionService.ProjectionResult project(
      EnvironmentIdentityPlan plan, CertificateMaterialService.RoleMaterial material, String role) {
    validateSourceLabels(material.source(), plan, role);
    String provenance =
        HostedIdentityContract.GRPC_ROLE.equals(role)
            ? HostedIdentityContract.TRANSPORT_PROVENANCE
            : material.provenance();
    return projectionService.project(
        client,
        plan,
        role,
        material.source(),
        material.sourceGeneration(),
        material.sourceObjectGeneration(),
        material.summary().spkiSha256(),
        provenance);
  }

  private UpdateControl<HostedEnvironmentIdentity> retire(
      HostedEnvironmentIdentity resource,
      EnvironmentIdentityPlan plan,
      Context<HostedEnvironmentIdentity> context) {
    Namespace runtimeNamespace = client.namespaces().withName(plan.runtimeNamespace()).get();
    if (runtimeNamespace != null) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          "RuntimePresent",
          "retirement waits for runtime Namespace deletion",
          false,
          null,
          null,
          null,
          null);
    }
    if (!deleteOwnedMaterial(plan)) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          "IdentityOwnershipUncertain",
          "identity Namespace ownership could not be proven; retained material",
          false,
          null,
          null,
          null,
          null);
    }
    context.resourceOperations().removeFinalizer(HostedIdentityContract.FINALIZER);
    return status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Retired,
        "Retired",
        "runtime absent; owned retained material removed",
        false,
        null,
        null,
        null,
        null);
  }

  private boolean deleteOwnedMaterial(EnvironmentIdentityPlan plan) {
    Namespace identityNamespace = client.namespaces().withName(plan.identityNamespace()).get();
    if (identityNamespace == null) {
      return true;
    }
    if (!HostedIdentityScopeService.isExpectedIdentityNamespace(identityNamespace, plan)
        || !isOwnedIdentityNamespace(identityNamespace, plan)) {
      return false;
    }
    for (String name :
        List.of(
            plan.ingressSecretName(),
            plan.telnetSecretName(),
            plan.grpcSecretName(),
            plan.ingressSecretName() + "-previous",
            plan.telnetSecretName() + "-previous",
            plan.grpcSecretName() + "-previous")) {
      Secret secret = client.secrets().inNamespace(plan.identityNamespace()).withName(name).get();
      if (isOwned(secret, plan.name())) {
        client.secrets().inNamespace(plan.identityNamespace()).withName(name).delete();
      }
    }
    for (String name :
        List.of(
            plan.ingressCertificateName(),
            plan.telnetCertificateName(),
            plan.grpcCertificateName())) {
      var operation =
          client
              .genericKubernetesResources(ResourceContexts.CERTIFICATES)
              .inNamespace(plan.identityNamespace())
              .withName(name);
      var certificate = operation.get();
      if (certificate != null && isOwned(certificate.getMetadata().getLabels(), plan.name())) {
        operation.delete();
      }
    }
    deleteOwnedScope(plan.identityNamespace(), "firemud-hosted-identity-scope", plan.name());
    client.namespaces().withName(plan.identityNamespace()).delete();
    return true;
  }

  private void deleteOwnedScope(String namespace, String name, String environment) {
    var roleOperation = client.rbac().roles().inNamespace(namespace).withName(name);
    var role = roleOperation.get();
    if (role != null && isOwned(role.getMetadata().getLabels(), environment)) {
      roleOperation.delete();
    }
    var bindingOperation = client.rbac().roleBindings().inNamespace(namespace).withName(name);
    var binding = bindingOperation.get();
    if (binding != null && isOwned(binding.getMetadata().getLabels(), environment)) {
      bindingOperation.delete();
    }
  }

  private boolean isOwnedIdentityNamespace(Namespace namespace, EnvironmentIdentityPlan plan) {
    var role =
        client
            .rbac()
            .roles()
            .inNamespace(plan.identityNamespace())
            .withName("firemud-hosted-identity-scope")
            .get();
    var binding =
        client
            .rbac()
            .roleBindings()
            .inNamespace(plan.identityNamespace())
            .withName("firemud-hosted-identity-scope")
            .get();
    return namespace.getMetadata() != null
        && namespace.getMetadata().getName() != null
        && namespace.getMetadata().getName().equals(plan.identityNamespace())
        && role != null
        && isOwned(role.getMetadata().getLabels(), plan.name())
        && binding != null
        && isOwned(binding.getMetadata().getLabels(), plan.name());
  }

  private static boolean isOwned(Secret secret, String environment) {
    return secret != null
        && secret.getMetadata() != null
        && isOwned(secret.getMetadata().getLabels(), environment)
        && HostedIdentityContract.RETAINED.equals(
            secret.getMetadata().getLabels().get(HostedIdentityContract.RETENTION_LABEL));
  }

  private static boolean isOwned(Map<String, String> labels, String environment) {
    return labels != null
        && HostedIdentityContract.CONTROLLER_NAME.equals(
            labels.get(HostedIdentityContract.MANAGED_BY_LABEL))
        && environment.equals(labels.get(HostedIdentityContract.ENVIRONMENT_LABEL));
  }

  private static void validateSourceLabels(
      Secret secret, EnvironmentIdentityPlan plan, String role) {
    if (secret == null || secret.getMetadata() == null || !isOwned(secret, plan.name())) {
      throw new IllegalStateException("source Secret is not controller-owned");
    }
    if (!role.equals(secret.getMetadata().getLabels().get(HostedIdentityContract.ROLE_LABEL))) {
      throw new IllegalStateException("source Secret role label mismatch");
    }
  }

  private static void ensureFinalizer(
      HostedEnvironmentIdentity resource, Context<HostedEnvironmentIdentity> context) {
    List<String> finalizers = resource.getMetadata().getFinalizers();
    if (finalizers == null || !finalizers.contains(HostedIdentityContract.FINALIZER)) {
      context.resourceOperations().addFinalizer(HostedIdentityContract.FINALIZER);
    }
  }

  private UpdateControl<HostedEnvironmentIdentity> status(
      HostedEnvironmentIdentity resource,
      HostedEnvironmentIdentityStatus.Phase phase,
      String reason,
      String message,
      boolean ready,
      RuntimeProfileService.RuntimeProfile profile,
      CertificateMaterialService.RoleMaterial ingress,
      CertificateMaterialService.RoleMaterial telnet,
      CertificateMaterialService.RoleMaterial grpc) {
    resource.setStatus(
        statusService.status(
            resource,
            phase,
            reason,
            message,
            ready,
            profile,
            roleStatus(ingress, previousRole(resource, HostedIdentityContract.INGRESS_ROLE)),
            roleStatus(telnet, previousRole(resource, HostedIdentityContract.TELNET_ROLE)),
            roleStatus(grpc, previousRole(resource, HostedIdentityContract.GRPC_ROLE))));
    return UpdateControl.patchStatus(resource).rescheduleAfter(properties.getReconcileInterval());
  }

  private static HostedEnvironmentIdentityStatus.RoleStatus roleStatus(
      CertificateMaterialService.RoleMaterial material,
      HostedEnvironmentIdentityStatus.RoleStatus previous) {
    return material == null || !material.ready()
        ? previous
        : HostedStatusService.role(
            material.source() == null
                ? material.revision()
                : SecretProjectionService.revisionForRole(
                    material.role(), material.source().getData()),
            material.sourceGeneration() < 1 ? null : material.sourceGeneration(),
            material.sourceObjectGeneration() < 1 ? null : material.sourceObjectGeneration(),
            material.summary() == null ? null : material.summary().spkiSha256(),
            material.provenance(),
            material.state());
  }

  private static HostedEnvironmentIdentityStatus.RoleStatus previousRole(
      HostedEnvironmentIdentity resource, String role) {
    if (resource.getStatus() == null) return null;
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> resource.getStatus().getIngress();
      case HostedIdentityContract.TELNET_ROLE -> resource.getStatus().getTelnet();
      case HostedIdentityContract.GRPC_ROLE -> resource.getStatus().getGrpc();
      default -> throw new IllegalArgumentException("unsupported identity role: " + role);
    };
  }

  static void validateSourceProgress(
      CertificateMaterialService.RoleMaterial material,
      HostedEnvironmentIdentityStatus.RoleStatus previous) {
    if (previous == null || previous.getSourceGeneration() == null) return;
    long priorGeneration = previous.getSourceGeneration();
    long priorObjectGeneration =
        previous.getSourceObjectGeneration() == null ? 0 : previous.getSourceObjectGeneration();
    String revision =
        SecretProjectionService.revisionForRole(material.role(), material.source().getData());
    if (material.sourceGeneration() < priorGeneration) {
      throw new IllegalStateException("certificate source generation rolled back");
    }
    if (material.sourceObjectGeneration() < priorObjectGeneration) {
      throw new IllegalStateException("certificate source object generation rolled back");
    }
    if (material.sourceGeneration() == priorGeneration
        && (material.sourceObjectGeneration() != priorObjectGeneration
            || !revision.equals(previous.getRevision())
            || !material.summary().spkiSha256().equals(previous.getSpkiSha256()))) {
      throw new IllegalStateException("certificate source changed without generation advancement");
    }
    if (material.sourceGeneration() > priorGeneration
        && material.summary().spkiSha256().equals(previous.getSpkiSha256())) {
      throw new IllegalStateException("replacement certificate reused the prior public key");
    }
  }

  private static boolean isRetiring(HostedEnvironmentIdentity resource) {
    return resource.getMetadata().getDeletionTimestamp() != null
        || (resource.getSpec() != null
            && HostedEnvironmentIdentitySpec.DesiredState.Retired.equals(
                resource.getSpec().getDesiredState()));
  }

  private static String boundedMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
