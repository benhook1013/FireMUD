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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Narrow, periodic reconciler for one closed HostedEnvironmentIdentity resource. */
@Component
@ControllerConfiguration(
    finalizerName = HostedIdentityContract.FINALIZER,
    informer = @Informer(namespaces = {HostedIdentityContract.CONTROL_NAMESPACE}))
public class HostedIdentityReconciler implements Reconciler<HostedEnvironmentIdentity> {
  private static final Logger LOGGER = LoggerFactory.getLogger(HostedIdentityReconciler.class);
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
    EnvironmentIdentityPlan plannedEnvironment = null;
    try {
      admissionValidator.validate(resource);
      EnvironmentIdentityPlan plan = planner.plan(resource.getMetadata().getName());
      plannedEnvironment = plan;
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
            RoleMaterials.of());
      }
      if (isRetiring(resource)) {
        return retire(resource, plan, context);
      }
      RuntimeProfileService.RuntimeProfile runtimeProfile;
      try {
        runtimeProfile = runtimeProfileService.read(client, plan);
      } catch (IllegalStateException exception) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Blocked,
            "RuntimeProfileInvalid",
            boundedMessage(exception),
            false,
            null,
            RoleMaterials.of());
      }
      if (!runtimeProfile.present()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.RuntimeAbsent,
            "RuntimeAbsent",
            "runtime Namespace is absent; retained source material",
            false,
            runtimeProfile,
            RoleMaterials.of());
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
            ingress.state().statusValue(),
            ingress.state().statusValue(),
            false,
            runtimeProfile,
            RoleMaterials.of(ingress));
      }
      validateSourceProgress(ingress, previousRole(resource, HostedIdentityContract.INGRESS_ROLE));
      CertificateMaterialService.RoleMaterial telnet = materialization.telnet();
      if (!telnet.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            telnet.state().statusValue(),
            telnet.state().statusValue(),
            false,
            runtimeProfile,
            RoleMaterials.of(ingress, telnet));
      }
      validateSourceProgress(telnet, previousRole(resource, HostedIdentityContract.TELNET_ROLE));
      CertificateMaterialService.RoleMaterial gatewayInternalWs =
          materialization.gatewayInternalWs();
      if (!gatewayInternalWs.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            gatewayInternalWs.state().statusValue(),
            gatewayInternalWs.state().statusValue(),
            false,
            runtimeProfile,
            RoleMaterials.of(ingress, telnet, gatewayInternalWs));
      }
      validateSourceProgress(
          gatewayInternalWs,
          previousRole(resource, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE));
      CertificateMaterialService.RoleMaterial tcpProxyBridge = materialization.tcpProxyBridge();
      if (!tcpProxyBridge.ready()) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate,
            tcpProxyBridge.state().statusValue(),
            tcpProxyBridge.state().statusValue(),
            false,
            runtimeProfile,
            RoleMaterials.of(ingress, telnet, gatewayInternalWs, tcpProxyBridge));
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
            grpc.state().statusValue(),
            grpc.state().statusValue(),
            false,
            runtimeProfile,
            RoleMaterials.of(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc));
      }
      validateSourceProgress(grpc, previousRole(resource, HostedIdentityContract.GRPC_ROLE));
      validateDistinctIdentities(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc);

      List<RoleMaterialBinding> rolePipeline =
          List.of(
              new RoleMaterialBinding(HostedIdentityContract.INGRESS_ROLE, ingress),
              new RoleMaterialBinding(HostedIdentityContract.TELNET_ROLE, telnet),
              new RoleMaterialBinding(
                  HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE, gatewayInternalWs),
              new RoleMaterialBinding(HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE, tcpProxyBridge),
              new RoleMaterialBinding(HostedIdentityContract.GRPC_ROLE, grpc));
      Map<String, SecretProjectionService.ProjectionResult> projections = new LinkedHashMap<>();
      for (RoleMaterialBinding binding : rolePipeline) {
        SecretProjectionService.ProjectionResult projection =
            project(plan, runtimeProfile, binding.material(), binding.role());
        projections.put(binding.role(), projection);
      }
      ReadinessStatus deploymentHead = deploymentHeadStatus(runtimeProfile);
      if (!deploymentHead.ready()) {
        return status(
            resource,
            deploymentHead.phase(),
            deploymentHead.reason(),
            deploymentHead.message(),
            false,
            runtimeProfile,
            RoleMaterials.of(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc));
      }
      RuntimeProfileValidation beforeRollout =
          revalidateRuntimeProfile(plan, runtimeProfile, "the rollout boundary");
      if (!beforeRollout.valid()) {
        return status(
            resource,
            beforeRollout.phase(),
            beforeRollout.reason(),
            beforeRollout.message(),
            false,
            beforeRollout.profile(),
            RoleMaterials.of(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc));
      }
      DeploymentRolloutService.RolloutResult rollout =
          deploymentRolloutService.sync(
              client,
              plan,
              projections.get(HostedIdentityContract.TELNET_ROLE).revision(),
              projections.get(HostedIdentityContract.GRPC_ROLE).revision(),
              () -> assertRuntimeProfileCurrent(plan, runtimeProfile, "rollout mutation"));
      ServedEnvironmentProbe.ProbeResult probes;
      if (rollout.ready()) {
        probes =
            servedEnvironmentProbe.probe(
                plan,
                runtimeProfile.telnetPort(),
                ingress.summary().certificateFingerprint(),
                telnet.summary().certificateFingerprint(),
                bridgeProbeMaterial(
                    tcpProxyBridge,
                    () ->
                        runtimeProjection(
                            plan, runtimeProfile, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE)),
                gatewayInternalWs.summary().certificateFingerprint(),
                grpc.source(),
                grpc.summary().certificateFingerprint());
      } else {
        probes = new ServedEnvironmentProbe.ProbeResult(false, "rollout-pending");
      }
      RuntimeProfileValidation beforeAcknowledgement =
          revalidateRuntimeProfile(plan, runtimeProfile, "the readiness boundary");
      if (!beforeAcknowledgement.valid()) {
        return status(
            resource,
            beforeAcknowledgement.phase(),
            beforeAcknowledgement.reason(),
            beforeAcknowledgement.message(),
            false,
            beforeAcknowledgement.profile(),
            RoleMaterials.of(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc));
      }
      if (rollout.ready() && probes.ready()) {
        for (RoleMaterialBinding binding : rolePipeline) {
          SecretProjectionService.ProjectionResult acknowledged =
              projectionService.acknowledge(
                  client,
                  plan,
                  binding.role(),
                  projections.get(binding.role()).revision(),
                  binding.material().sourceGeneration(),
                  binding.material().sourceObjectGeneration(),
                  binding.material().summary().spkiSha256(),
                  () ->
                      assertRuntimeProfileCurrent(
                          plan, runtimeProfile, "projection acknowledgement"));
          projections.put(binding.role(), acknowledged);
        }
      }
      ReadinessStatus readiness =
          readinessStatus(
              rolePipeline.stream().map(binding -> projections.get(binding.role())).toList(),
              rollout,
              probes);
      return status(
          resource,
          readiness.phase(),
          readiness.reason(),
          readiness.message(),
          readiness.ready(),
          runtimeProfile,
          RoleMaterials.of(ingress, telnet, gatewayInternalWs, tcpProxyBridge, grpc));
    } catch (RuntimeProfileFenceException exception) {
      LOGGER.warn(
          "Hosted identity reconciliation fenced for environment '{}' and runtime Namespace '{}'",
          resourceName(resource),
          runtimeNamespace(plannedEnvironment),
          exception);
      return status(
          resource,
          exception.phase(),
          exception.reason(),
          exception.getMessage(),
          false,
          null,
          RoleMaterials.of());
    } catch (Exception exception) {
      LOGGER.error(
          "Hosted identity reconciliation failed for environment '{}' and runtime Namespace '{}'",
          resourceName(resource),
          runtimeNamespace(plannedEnvironment),
          exception);
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Blocked,
          "ReconciliationBlocked",
          boundedMessage(exception),
          false,
          null,
          RoleMaterials.of());
    }
  }

  private static String resourceName(HostedEnvironmentIdentity resource) {
    if (resource == null
        || resource.getMetadata() == null
        || resource.getMetadata().getName() == null
        || resource.getMetadata().getName().isBlank()) {
      return "<unknown>";
    }
    return resource.getMetadata().getName();
  }

  private static String runtimeNamespace(EnvironmentIdentityPlan plan) {
    return plan == null ? "<unresolved>" : plan.runtimeNamespace();
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

  private RuntimeProfileValidation revalidateRuntimeProfile(
      EnvironmentIdentityPlan plan,
      RuntimeProfileService.RuntimeProfile expected,
      String boundary) {
    RuntimeProfileService.RuntimeProfile current;
    try {
      current = runtimeProfileService.read(client, plan);
    } catch (IllegalStateException exception) {
      return new RuntimeProfileValidation(
          null,
          HostedEnvironmentIdentityStatus.Phase.Blocked,
          "RuntimeProfileInvalid",
          "runtime profile became malformed at " + boundary + ": " + boundedMessage(exception),
          false);
    }
    if (!current.present()) {
      return new RuntimeProfileValidation(
          current,
          HostedEnvironmentIdentityStatus.Phase.RuntimeAbsent,
          "RuntimeAbsent",
          "runtime Namespace disappeared at "
              + boundary
              + "; stale convergence was not acknowledged",
          false);
    }
    if (!RuntimeProfileService.exactlyMatches(expected, current)) {
      return new RuntimeProfileValidation(
          current,
          HostedEnvironmentIdentityStatus.Phase.Verifying,
          "RuntimeIdentityChanged",
          "runtime identity changed at "
              + boundary
              + " ("
              + RuntimeProfileService.changedFields(expected, current)
              + "); fresh convergence is required",
          false);
    }
    return new RuntimeProfileValidation(current, null, null, null, true);
  }

  record ReadinessStatus(
      HostedEnvironmentIdentityStatus.Phase phase, String reason, String message, boolean ready) {}

  private record RoleMaterialBinding(
      String role, CertificateMaterialService.RoleMaterial material) {
    private RoleMaterialBinding {
      if (material == null || !role.equals(material.role())) {
        throw new IllegalArgumentException("identity role does not match its material");
      }
    }
  }

  private record RoleMaterials(
      Map<String, CertificateMaterialService.RoleMaterial> materialsByRole) {
    private RoleMaterials {
      materialsByRole = Map.copyOf(materialsByRole);
    }

    private static RoleMaterials of(CertificateMaterialService.RoleMaterial... materials) {
      Map<String, CertificateMaterialService.RoleMaterial> materialsByRole = new LinkedHashMap<>();
      for (CertificateMaterialService.RoleMaterial material : materials) {
        if (material == null) {
          continue;
        }
        String role = material.role();
        switch (role) {
          case HostedIdentityContract.INGRESS_ROLE,
              HostedIdentityContract.TELNET_ROLE,
              HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
              HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
              HostedIdentityContract.GRPC_ROLE -> {
            if (materialsByRole.putIfAbsent(role, material) != null) {
              throw new IllegalArgumentException("duplicate identity material role: " + role);
            }
          }
          default -> throw new IllegalArgumentException("unsupported identity role: " + role);
        }
      }
      return new RoleMaterials(materialsByRole);
    }

    private CertificateMaterialService.RoleMaterial material(String role) {
      return materialsByRole.get(role);
    }
  }

  private record RuntimeProfileValidation(
      RuntimeProfileService.RuntimeProfile profile,
      HostedEnvironmentIdentityStatus.Phase phase,
      String reason,
      String message,
      boolean valid) {}

  SecretProjectionService.ProjectionResult project(
      EnvironmentIdentityPlan plan,
      RuntimeProfileService.RuntimeProfile expectedProfile,
      CertificateMaterialService.RoleMaterial material,
      String role) {
    validateSourceLabels(material.source(), plan, role);
    if (material.projectionDeferred()) {
      return SecretProjectionService.ProjectionResult.awaiting(
          material.state().statusValue(),
          SecretProjectionService.revisionForRole(material.role(), material.source().getData()));
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
        provenance,
        () -> assertRuntimeProfileCurrent(plan, expectedProfile, "identity projection"));
  }

  Secret runtimeProjection(
      EnvironmentIdentityPlan plan,
      RuntimeProfileService.RuntimeProfile expectedProfile,
      String role) {
    String name =
        switch (role) {
          case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE ->
              plan.gatewayInternalWsSecretName();
          case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeSecretName();
          default ->
              throw new IllegalArgumentException("unsupported bridge identity role: " + role);
        };
    assertRuntimeProfileCurrent(plan, expectedProfile, "runtime projection read");
    Secret secret = client.secrets().inNamespace(plan.runtimeNamespace()).withName(name).get();
    if (secret == null) {
      return null;
    }
    validateSourceLabels(secret, plan, role);
    return secret;
  }

  static Secret bridgeProbeMaterial(
      CertificateMaterialService.RoleMaterial material, Supplier<Secret> runtimeProjection) {
    return material.projectionDeferred() ? material.source() : runtimeProjection.get();
  }

  static void validateDistinctIdentities(CertificateMaterialService.RoleMaterial... materials) {
    java.util.Set<String> publicKeys = new java.util.HashSet<>();
    for (CertificateMaterialService.RoleMaterial material : materials) {
      String spkiSha256 = material.summary().spkiSha256();
      if (spkiSha256 == null) {
        throw new IllegalStateException(
            "controller-managed certificate identity has no SPKI digest");
      }
      if (!publicKeys.add(spkiSha256)) {
        throw new IllegalStateException(
            "controller-managed certificate identities must use independent keys");
      }
    }
  }

  private UpdateControl<HostedEnvironmentIdentity> retire(
      HostedEnvironmentIdentity resource,
      EnvironmentIdentityPlan plan,
      Context<HostedEnvironmentIdentity> context) {
    RuntimeProfileService.RuntimeProfile runtimeProfile;
    try {
      runtimeProfile = runtimeProfileService.read(client, plan);
    } catch (IllegalStateException exception) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          "RuntimeProfileInvalid",
          "runtime profile is malformed; bridge shutdown and identity deletion are withheld: "
              + boundedMessage(exception),
          false,
          null,
          RoleMaterials.of());
    }
    if (runtimeProfile.present()) {
      RuntimeProfileService.RuntimeProfile observedProfile =
          previouslyObservedRuntimeProfile(resource);
      if (observedProfile == null) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Retiring,
            "RuntimeIdentityUnproven",
            "retirement requires a complete previously observed runtime profile; bridge shutdown "
                + "and identity deletion are withheld",
            false,
            null,
            RoleMaterials.of());
      }
      if (!RuntimeProfileService.exactlyMatches(observedProfile, runtimeProfile)) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Retiring,
            "RuntimeIdentityChanged",
            "runtime Namespace identity does not match its previously observed status profile ("
                + RuntimeProfileService.changedFields(observedProfile, runtimeProfile)
                + "); bridge shutdown and identity deletion are withheld",
            false,
            null,
            RoleMaterials.of());
      }
      RuntimeProfileService.RuntimeProfile expectedProfile = runtimeProfile;
      DeploymentRolloutService.RetirementResult shutdown;
      try {
        shutdown =
            deploymentRolloutService.stopBridges(
                client,
                plan,
                () -> assertRuntimeProfileCurrent(plan, expectedProfile, "bridge shutdown"));
      } catch (RuntimeProfileFenceException exception) {
        return status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Retiring,
            exception.reason(),
            exception.getMessage(),
            false,
            null,
            RoleMaterials.of());
      }
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          shutdown.stopped() ? "RuntimePresent" : "BridgeShutdownPending",
          shutdown.stopped()
              ? "bridge workloads are stopped; retirement waits for runtime Namespace deletion"
              : "retirement is terminating Gateway and TCP Proxy bridge workloads",
          false,
          null,
          RoleMaterials.of());
    }
    if (!deleteOwnedMaterial(plan)) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          "IdentityOwnershipUncertain",
          "identity Namespace ownership could not be proven; retained material",
          false,
          null,
          RoleMaterials.of());
    }
    if (client.namespaces().withName(plan.identityNamespace()).get() != null) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retiring,
          "IdentityCleanupPending",
          "retirement waits for retained identity Namespace deletion",
          false,
          null,
          RoleMaterials.of());
    }
    if (!retiredStatusIsCurrent(resource)) {
      return status(
          resource,
          HostedEnvironmentIdentityStatus.Phase.Retired,
          "Retired",
          "runtime Namespace is absent and retained identity material was removed",
          false,
          null,
          RoleMaterials.of());
    }
    if (resource.getMetadata().getDeletionTimestamp() == null) {
      return UpdateControl.<HostedEnvironmentIdentity>noUpdate()
          .rescheduleAfter(properties.getReconcileInterval());
    }
    return finishRetirement(context);
  }

  private RuntimeProfileService.RuntimeProfile previouslyObservedRuntimeProfile(
      HostedEnvironmentIdentity resource) {
    if (resource.getStatus() == null || resource.getStatus().getProfile() == null) {
      return null;
    }
    HostedEnvironmentIdentityStatus.RuntimeProfile profile = resource.getStatus().getProfile();
    if (profile.getRuntimeNamespaceUid() == null
        || profile.getRuntimeNamespaceUid().isBlank()
        || !canonicalHead(profile.getRequestedHeadSha())
        || !optionalCanonicalHead(profile.getDeployedHeadSha())
        || profile.getTelnetPort() == null
        || profile.getTelnetPort() < 1
        || profile.getTelnetPort() > 65535) {
      return null;
    }
    return new RuntimeProfileService.RuntimeProfile(
        profile.getRuntimeNamespaceUid(),
        profile.getRequestedHeadSha(),
        profile.getDeployedHeadSha(),
        profile.getTelnetPort(),
        true);
  }

  /**
   * Confirms that the runtime profile still matches the expected mutation boundary.
   *
   * @throws RuntimeProfileFenceException when the profile is malformed, absent, or changed
   */
  private void assertRuntimeProfileCurrent(
      EnvironmentIdentityPlan plan,
      RuntimeProfileService.RuntimeProfile expectedProfile,
      String guardedAction) {
    RuntimeProfileService.RuntimeProfile current;
    try {
      current = runtimeProfileService.read(client, plan);
    } catch (IllegalStateException exception) {
      throw new RuntimeProfileFenceException(
          HostedEnvironmentIdentityStatus.Phase.Blocked,
          "RuntimeProfileInvalid",
          "runtime profile became malformed before "
              + guardedAction
              + "; "
              + guardedAction
              + " is withheld: "
              + boundedMessage(exception),
          exception);
    }
    if (!current.present()) {
      throw new RuntimeProfileFenceException(
          HostedEnvironmentIdentityStatus.Phase.RuntimeAbsent,
          "RuntimeAbsent",
          "runtime Namespace disappeared before "
              + guardedAction
              + "; "
              + guardedAction
              + " is withheld");
    }
    if (!RuntimeProfileService.exactlyMatches(expectedProfile, current)) {
      throw new RuntimeProfileFenceException(
          HostedEnvironmentIdentityStatus.Phase.Verifying,
          "RuntimeIdentityChanged",
          "runtime Namespace identity changed before "
              + guardedAction
              + " ("
              + RuntimeProfileService.changedFields(expectedProfile, current)
              + "); "
              + guardedAction
              + " is withheld");
    }
  }

  private static boolean canonicalHead(String head) {
    return head != null && head.matches("[0-9a-f]{40}");
  }

  private static boolean optionalCanonicalHead(String head) {
    return head == null || canonicalHead(head);
  }

  private static final class RuntimeProfileFenceException extends IllegalStateException {
    private final HostedEnvironmentIdentityStatus.Phase phase;
    private final String reason;

    private RuntimeProfileFenceException(
        HostedEnvironmentIdentityStatus.Phase phase, String reason, String message) {
      super(message);
      this.phase = phase;
      this.reason = reason;
    }

    private RuntimeProfileFenceException(
        HostedEnvironmentIdentityStatus.Phase phase,
        String reason,
        String message,
        Throwable cause) {
      super(message, cause);
      this.phase = phase;
      this.reason = reason;
    }

    private HostedEnvironmentIdentityStatus.Phase phase() {
      return phase;
    }

    private String reason() {
      return reason;
    }
  }

  static boolean retiredStatusIsCurrent(HostedEnvironmentIdentity resource) {
    if (resource.getStatus() == null
        || resource.getMetadata() == null
        || !HostedEnvironmentIdentityStatus.Phase.Retired.equals(resource.getStatus().getPhase())
        || !java.util.Objects.equals(
            resource.getMetadata().getGeneration(), resource.getStatus().getObservedGeneration())) {
      return false;
    }
    return resource.getStatus().getConditions().stream()
        .anyMatch(
            condition ->
                "Ready".equals(condition.getType())
                    && "False".equals(condition.getStatus())
                    && java.util.Objects.equals(
                        resource.getMetadata().getGeneration(), condition.getObservedGeneration()));
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
    if (!HostedIdentityScopeService.hasExpectedIdentityNamespaceMetadata(identityNamespace, plan)
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
            plan.tcpProxyBridgeCertificateName());
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
      if (!HostedIdentityScopeService.hasExpectedIdentityNamespaceMetadata(deletingNamespace, plan)
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
      RoleMaterials materials) {
    resource.setStatus(
        statusService.status(
            resource,
            phase,
            reason,
            message,
            ready,
            profile,
            roleStatus(
                materials.material(HostedIdentityContract.INGRESS_ROLE),
                previousRole(resource, HostedIdentityContract.INGRESS_ROLE)),
            roleStatus(
                materials.material(HostedIdentityContract.TELNET_ROLE),
                previousRole(resource, HostedIdentityContract.TELNET_ROLE)),
            roleStatus(
                materials.material(HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE),
                previousRole(resource, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE)),
            roleStatus(
                materials.material(HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE),
                previousRole(resource, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE)),
            roleStatus(
                materials.material(HostedIdentityContract.GRPC_ROLE),
                previousRole(resource, HostedIdentityContract.GRPC_ROLE))));
    return UpdateControl.patchStatus(resource).rescheduleAfter(properties.getReconcileInterval());
  }

  private static HostedEnvironmentIdentityStatus.RoleStatus roleStatus(
      CertificateMaterialService.RoleMaterial material,
      HostedEnvironmentIdentityStatus.RoleStatus previous) {
    if (material == null || !material.ready()) {
      // Early and failure statuses retain the last published role evidence until fresh material
      // is available.
      return previous;
    }
    return HostedStatusService.role(
        material.source() == null
            ? material.revision()
            : SecretProjectionService.revisionForRole(material.role(), material.source().getData()),
        material.sourceGeneration() < 1 ? null : material.sourceGeneration(),
        material.sourceObjectGeneration() < 1 ? null : material.sourceObjectGeneration(),
        material.summary() == null ? null : material.summary().spkiSha256(),
        material.provenance(),
        material.state().statusValue());
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
        && (!revision.equals(previous.getRevision())
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
