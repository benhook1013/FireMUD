package net.firedevops.firemud.hostedidentity.reconcile;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.ArrayList;
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
    informer = @Informer(namespaces = {HostedIdentityContract.CONTROL_NAMESPACE}))
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected configuration is application-scoped and is never exposed.")
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
      CertificateMaterialService.MaterializationBatch materialization =
          certificateMaterialService.beginMaterialization(client, plan);

      CertificateMaterialService.RoleMaterial ingress = materialization.ingress();
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
      CertificateMaterialService.RoleMaterial telnet = materialization.telnet();
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
      CertificateMaterialService.RoleMaterial gatewayInternalWs =
          materialization.gatewayInternalWs();
      if (!gatewayInternalWs.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            gatewayInternalWs.state(),
            gatewayInternalWs.state(),
            false,
            runtimeProfile,
            ingress,
            telnet,
            gatewayInternalWs,
            null,
            null);
      }
      validateSourceProgress(
          gatewayInternalWs,
          previousRole(resource, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE));
      CertificateMaterialService.RoleMaterial tcpProxyBridge = materialization.tcpProxyBridge();
      if (!tcpProxyBridge.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            tcpProxyBridge.state(),
            tcpProxyBridge.state(),
            false,
            runtimeProfile,
            ingress,
            telnet,
            gatewayInternalWs,
            tcpProxyBridge,
            null);
      }
      validateSourceProgress(
          tcpProxyBridge, previousRole(resource, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE));
      Long acceptedGrpcGeneration =
          resource.getStatus() == null || resource.getStatus().getGrpc() == null
              ? null
              : resource.getStatus().getGrpc().getSourceGeneration();
      CertificateMaterialService.RoleMaterial grpc = materialization.grpc(acceptedGrpcGeneration);
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
            gatewayInternalWs,
            tcpProxyBridge,
            grpc);
      }
      validateSourceProgress(grpc, previousRole(resource, HostedIdentityContract.GRPC_ROLE));
      validateDistinctIdentities(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc);

      SecretProjectionService.ProjectionResult ingressProjection =
          project(plan, ingress, HostedIdentityContract.INGRESS_ROLE);
      SecretProjectionService.ProjectionResult telnetProjection =
          project(plan, telnet, HostedIdentityContract.TELNET_ROLE);
      SecretProjectionService.ProjectionResult gatewayInternalWsProjection =
          project(plan, gatewayInternalWs, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE);
      SecretProjectionService.ProjectionResult tcpProxyBridgeProjection =
          project(plan, tcpProxyBridge, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE);
      SecretProjectionService.ProjectionResult grpcProjection =
          project(plan, grpc, HostedIdentityContract.GRPC_ROLE);
      ReadinessStatus deploymentHead = deploymentHeadStatus(runtimeProfile);
      if (!deploymentHead.ready()) {
        return status(
            resource,
            deploymentHead.phase(),
            deploymentHead.reason(),
            deploymentHead.message(),
            false,
            runtimeProfile,
            ingress,
            telnet,
            gatewayInternalWs,
            tcpProxyBridge,
            grpc);
      }
      DeploymentRolloutService.RolloutResult rollout =
          deploymentRolloutService.sync(
              client, plan, telnetProjection.revision(), grpcProjection.revision());
      ServedEnvironmentProbe.ProbeResult probes =
          servedEnvironmentProbe.probe(
              plan,
              runtimeProfile.telnetPort(),
              ingress.summary().certificateFingerprint(),
              telnet.summary().certificateFingerprint(),
              runtimeProjection(plan, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE),
              gatewayInternalWs.summary().certificateFingerprint(),
              grpc.source(),
              grpc.summary().certificateFingerprint());
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
        gatewayInternalWsProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
                gatewayInternalWsProjection.revision(),
                gatewayInternalWs.sourceGeneration(),
                gatewayInternalWs.sourceObjectGeneration(),
                gatewayInternalWs.summary().spkiSha256());
        tcpProxyBridgeProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
                tcpProxyBridgeProjection.revision(),
                tcpProxyBridge.sourceGeneration(),
                tcpProxyBridge.sourceObjectGeneration(),
                tcpProxyBridge.summary().spkiSha256());
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
      ReadinessStatus readiness =
          readinessStatus(
              List.of(
                  ingressProjection,
                  telnetProjection,
                  gatewayInternalWsProjection,
                  tcpProxyBridgeProjection,
                  grpcProjection),
              rollout,
              probes);
      return status(
          resource,
          readiness.phase(),
          readiness.reason(),
          readiness.message(),
          readiness.ready(),
          runtimeProfile,
          ingress,
          telnet,
          gatewayInternalWs,
          tcpProxyBridge,
          grpc);
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

  static ReadinessStatus readinessStatus(
      List<SecretProjectionService.ProjectionResult> projections,
      DeploymentRolloutService.RolloutResult rollout,
      ServedEnvironmentProbe.ProbeResult probes) {
    for (SecretProjectionService.ProjectionResult projection : projections) {
      if (!projection.isSynced()) {
        return new ReadinessStatus(
            HostedEnvironmentIdentityStatus.Phase.Syncing,
            "AwaitingAcceptance",
            projection.state(),
            false);
      }
    }
    if (!rollout.ready()) {
      String message =
          !rollout.telnetReady()
              ? "telnet-rollout-pending"
              : !rollout.grpcReady() ? "grpc-rollout-pending" : "rollout-pending";
      return new ReadinessStatus(
          HostedEnvironmentIdentityStatus.Phase.Verifying, "RolloutPending", message, false);
    }
    if (!probes.ready()) {
      return new ReadinessStatus(
          HostedEnvironmentIdentityStatus.Phase.Verifying,
          "ServedProbePending",
          probes.reason(),
          false);
    }
    return new ReadinessStatus(
        HostedEnvironmentIdentityStatus.Phase.Ready, "Reconciled", probes.reason(), true);
  }

  static ReadinessStatus deploymentHeadStatus(RuntimeProfileService.RuntimeProfile runtimeProfile) {
    if (runtimeProfile.deployedHeadMatchesRequest()) {
      return new ReadinessStatus(
          HostedEnvironmentIdentityStatus.Phase.Ready,
          "RuntimeDeploymentCurrent",
          "deployed runtime head matches the requested head",
          true);
    }
    String message =
        runtimeProfile.deployedHeadSha() == null
            ? "waiting for successful Helm deployment evidence"
            : "deployed runtime head does not match the requested head";
    return new ReadinessStatus(
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RuntimeDeploymentPending",
        message,
        false);
  }

  record ReadinessStatus(
      HostedEnvironmentIdentityStatus.Phase phase, String reason, String message, boolean ready) {}

  SecretProjectionService.ProjectionResult project(
      EnvironmentIdentityPlan plan, CertificateMaterialService.RoleMaterial material, String role) {
    validateSourceLabels(material.source(), plan, role);
    if (material.projectionDeferred()) {
      return SecretProjectionService.ProjectionResult.awaiting(
          material.state(), material.revision());
    }
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

  private Secret runtimeProjection(EnvironmentIdentityPlan plan, String role) {
    String name =
        switch (role) {
          case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE ->
              plan.gatewayInternalWsSecretName();
          case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeSecretName();
          default ->
              throw new IllegalArgumentException("unsupported bridge identity role: " + role);
        };
    Secret secret = client.secrets().inNamespace(plan.runtimeNamespace()).withName(name).get();
    validateSourceLabels(secret, plan, role);
    return secret;
  }

  static void validateDistinctIdentities(CertificateMaterialService.RoleMaterial... materials) {
    java.util.Set<String> publicKeys = new java.util.HashSet<>();
    for (CertificateMaterialService.RoleMaterial material : materials) {
      if (!publicKeys.add(material.summary().spkiSha256())) {
        throw new IllegalStateException(
            "controller-managed certificate identities must use independent keys");
      }
    }
  }

  private UpdateControl<HostedEnvironmentIdentity> retire(
      HostedEnvironmentIdentity resource,
      EnvironmentIdentityPlan plan,
      Context<HostedEnvironmentIdentity> context) {
    Namespace runtimeNamespace = client.namespaces().withName(plan.runtimeNamespace()).get();
    if (runtimeNamespace != null) {
      DeploymentRolloutService.RetirementResult shutdown =
          deploymentRolloutService.stopBridges(client, plan);
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          shutdown.stopped() ? "RuntimePresent" : "BridgeShutdownPending",
          shutdown.stopped()
              ? "bridge workloads are stopped; retirement waits for runtime Namespace deletion"
              : "retirement is terminating Gateway and TCP Proxy bridge workloads",
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
    if (client.namespaces().withName(plan.identityNamespace()).get() != null) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          "IdentityCleanupPending",
          "retirement waits for retained identity Namespace deletion",
          false,
          null,
          null,
          null,
          null);
    }
    if (!retiredStatusIsCurrent(resource)) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retired,
          "Retired",
          "runtime Namespace is absent and retained identity material was removed",
          false,
          null,
          null,
          null,
          null);
    }
    if (resource.getMetadata().getDeletionTimestamp() == null) {
      return UpdateControl.noUpdate();
    }
    return finishRetirement(context);
  }

  static boolean retiredStatusIsCurrent(HostedEnvironmentIdentity resource) {
    if (resource.getStatus() == null
        || resource.getMetadata() == null
        || !HostedEnvironmentIdentityStatus.Phase.Retired.equals(resource.getStatus().getPhase())
        || !java.util.Objects.equals(
            resource.getMetadata().getGeneration(), resource.getStatus().getObservedGeneration())) {
      return false;
    }
    return resource.getStatus().getConditions() != null
        && resource.getStatus().getConditions().stream()
            .anyMatch(
                condition ->
                    "Ready".equals(condition.getType())
                        && "False".equals(condition.getStatus())
                        && java.util.Objects.equals(
                            resource.getMetadata().getGeneration(),
                            condition.getObservedGeneration()));
  }

  static UpdateControl<HostedEnvironmentIdentity> finishRetirement(
      Context<HostedEnvironmentIdentity> context) {
    try {
      context.resourceOperations().removeFinalizer(HostedIdentityContract.FINALIZER);
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 404) {
        throw exception;
      }
    }
    return UpdateControl.noUpdate();
  }

  private boolean deleteOwnedMaterial(EnvironmentIdentityPlan plan) {
    var namespaceOperation = client.namespaces().withName(plan.identityNamespace());
    Namespace identityNamespace = namespaceOperation.get();
    if (identityNamespace == null) {
      return true;
    }
    boolean namespaceTerminating = isTerminating(identityNamespace);
    if (!HostedIdentityScopeService.isExpectedIdentityNamespace(identityNamespace, plan)
        || !isOwnedIdentityNamespace(identityNamespace, plan, namespaceTerminating)) {
      return false;
    }
    List<String> secretNames =
        List.of(
            plan.ingressSecretName(),
            plan.telnetSecretName(),
            plan.gatewayInternalWsSecretName(),
            plan.tcpProxyBridgeSecretName(),
            plan.grpcSecretName(),
            plan.ingressSecretName() + "-previous",
            plan.telnetSecretName() + "-previous",
            plan.gatewayInternalWsSecretName() + "-previous",
            plan.tcpProxyBridgeSecretName() + "-previous",
            plan.grpcSecretName() + "-previous");
    List<String> ownedSecretNames = new ArrayList<>();
    for (String name : secretNames) {
      Secret secret = client.secrets().inNamespace(plan.identityNamespace()).withName(name).get();
      if (secret == null) {
        continue;
      }
      if (!isOwned(secret, plan.name())) {
        return false;
      }
      ownedSecretNames.add(name);
    }
    List<String> certificateNames =
        List.of(
            plan.ingressCertificateName(),
            plan.telnetCertificateName(),
            plan.gatewayInternalWsCertificateName(),
            plan.tcpProxyBridgeCertificateName(),
            plan.grpcCertificateName());
    List<String> ownedCertificateNames = new ArrayList<>();
    for (String name : certificateNames) {
      var operation =
          client
              .genericKubernetesResources(ResourceContexts.CERTIFICATES)
              .inNamespace(plan.identityNamespace())
              .withName(name);
      var certificate = operation.get();
      if (certificate == null) {
        continue;
      }
      if (certificate.getMetadata() == null
          || !isOwned(certificate.getMetadata().getLabels(), plan.name())) {
        return false;
      }
      ownedCertificateNames.add(name);
    }
    for (String name : ownedSecretNames) {
      client.secrets().inNamespace(plan.identityNamespace()).withName(name).delete();
    }
    for (String name : ownedCertificateNames) {
      client
          .genericKubernetesResources(ResourceContexts.CERTIFICATES)
          .inNamespace(plan.identityNamespace())
          .withName(name)
          .delete();
    }
    if (!namespaceTerminating) {
      namespaceOperation.delete();
      Namespace deletingNamespace = namespaceOperation.get();
      if (deletingNamespace == null) {
        return true;
      }
      if (!HostedIdentityScopeService.isExpectedIdentityNamespace(deletingNamespace, plan)
          || !isTerminating(deletingNamespace)) {
        return false;
      }
    }
    return deleteOwnedScope(plan.identityNamespace(), "firemud-hosted-identity-scope", plan.name());
  }

  private boolean deleteOwnedScope(String namespace, String name, String environment) {
    var roleOperation = client.rbac().roles().inNamespace(namespace).withName(name);
    var role = roleOperation.get();
    if (role != null) {
      if (!isOwnedScopeOrAllowedMissing(role, environment, false)) {
        return false;
      }
      roleOperation.delete();
    }
    var bindingOperation = client.rbac().roleBindings().inNamespace(namespace).withName(name);
    var binding = bindingOperation.get();
    if (binding != null) {
      if (!isOwnedScopeOrAllowedMissing(binding, environment, false)) {
        return false;
      }
      bindingOperation.delete();
    }
    return true;
  }

  private boolean isOwnedIdentityNamespace(
      Namespace namespace, EnvironmentIdentityPlan plan, boolean allowMissingScope) {
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
        && isOwnedScopeOrAllowedMissing(role, plan.name(), allowMissingScope)
        && isOwnedScopeOrAllowedMissing(binding, plan.name(), allowMissingScope);
  }

  private static boolean isTerminating(Namespace namespace) {
    return namespace != null
        && namespace.getMetadata() != null
        && namespace.getMetadata().getDeletionTimestamp() != null
        && !namespace.getMetadata().getDeletionTimestamp().isBlank();
  }

  private static boolean isOwnedScopeOrAllowedMissing(
      HasMetadata scope, String environment, boolean allowMissing) {
    if (scope == null) {
      return allowMissing;
    }
    return scope.getMetadata() != null && isOwned(scope.getMetadata().getLabels(), environment);
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
    return status(
        resource, phase, reason, message, ready, profile, ingress, telnet, null, null, grpc);
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
      CertificateMaterialService.RoleMaterial gatewayInternalWs,
      CertificateMaterialService.RoleMaterial tcpProxyBridge,
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
            roleStatus(
                gatewayInternalWs,
                previousRole(resource, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE)),
            roleStatus(
                tcpProxyBridge,
                previousRole(resource, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE)),
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
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE ->
          resource.getStatus().getGatewayInternalWs();
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> resource.getStatus().getTcpProxyBridge();
      case HostedIdentityContract.GRPC_ROLE -> resource.getStatus().getGrpc();
      default -> throw new IllegalArgumentException("unsupported identity role: " + role);
    };
  }

  static void validateSourceProgress(
      CertificateMaterialService.RoleMaterial material,
      HostedEnvironmentIdentityStatus.RoleStatus previous) {
    if (previous == null
        || previous.getSourceGeneration() == null
        || material.acceptedSnapshotDeferred()) return;
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
