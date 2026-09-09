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
            null,
            null,
            null);
      }
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

      RuntimeProfileService.RuntimeProfile expectedProfile = runtimeProfile;
      SecretProjectionService.ProjectionResult ingressProjection =
          project(plan, expectedProfile, ingress, HostedIdentityContract.INGRESS_ROLE);
      UpdateControl<HostedEnvironmentIdentity> projectionFence =
          runtimeProfileChangedStatus(
              resource,
              runtimeProfile,
              ingress,
              telnet,
              gatewayInternalWs,
              tcpProxyBridge,
              grpc,
              ingressProjection);
      if (projectionFence != null) {
        return projectionFence;
      }
      SecretProjectionService.ProjectionResult telnetProjection =
          project(plan, expectedProfile, telnet, HostedIdentityContract.TELNET_ROLE);
      projectionFence =
          runtimeProfileChangedStatus(
              resource,
              runtimeProfile,
              ingress,
              telnet,
              gatewayInternalWs,
              tcpProxyBridge,
              grpc,
              telnetProjection);
      if (projectionFence != null) {
        return projectionFence;
      }
      SecretProjectionService.ProjectionResult gatewayInternalWsProjection =
          project(
              plan,
              expectedProfile,
              gatewayInternalWs,
              HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE);
      projectionFence =
          runtimeProfileChangedStatus(
              resource,
              runtimeProfile,
              ingress,
              telnet,
              gatewayInternalWs,
              tcpProxyBridge,
              grpc,
              gatewayInternalWsProjection);
      if (projectionFence != null) {
        return projectionFence;
      }
      SecretProjectionService.ProjectionResult tcpProxyBridgeProjection =
          project(
              plan, expectedProfile, tcpProxyBridge, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE);
      projectionFence =
          runtimeProfileChangedStatus(
              resource,
              runtimeProfile,
              ingress,
              telnet,
              gatewayInternalWs,
              tcpProxyBridge,
              grpc,
              tcpProxyBridgeProjection);
      if (projectionFence != null) {
        return projectionFence;
      }
      SecretProjectionService.ProjectionResult grpcProjection =
          project(plan, expectedProfile, grpc, HostedIdentityContract.GRPC_ROLE);
      projectionFence =
          runtimeProfileChangedStatus(
              resource,
              runtimeProfile,
              ingress,
              telnet,
              gatewayInternalWs,
              tcpProxyBridge,
              grpc,
              grpcProjection);
      if (projectionFence != null) {
        return projectionFence;
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
            ingress,
            telnet,
            gatewayInternalWs,
            tcpProxyBridge,
            grpc);
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
            ingress,
            telnet,
            gatewayInternalWs,
            tcpProxyBridge,
            grpc);
      }
      runtimeProfile = beforeRollout.profile();
      DeploymentRolloutService.RolloutResult rollout =
          deploymentRolloutService.sync(
              client,
              plan,
              telnetProjection.revision(),
              grpcProjection.revision(),
              () -> assertRuntimeProfileCurrent(plan, expectedProfile, "rollout mutation"));
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
                            plan, expectedProfile, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE)),
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
            ingress,
            telnet,
            gatewayInternalWs,
            tcpProxyBridge,
            grpc);
      }
      runtimeProfile = beforeAcknowledgement.profile();
      RuntimeProfileService.RuntimeProfile acknowledgementProfile = runtimeProfile;
      if (rollout.ready() && probes.ready()) {
        ingressProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.INGRESS_ROLE,
                ingressProjection.revision(),
                ingress.sourceGeneration(),
                ingress.sourceObjectGeneration(),
                ingress.summary().spkiSha256(),
                () ->
                    assertRuntimeProfileCurrent(
                        plan, acknowledgementProfile, "projection acknowledgement"));
        UpdateControl<HostedEnvironmentIdentity> acknowledgementFence =
            runtimeProfileChangedStatus(
                resource,
                runtimeProfile,
                ingress,
                telnet,
                gatewayInternalWs,
                tcpProxyBridge,
                grpc,
                ingressProjection);
        if (acknowledgementFence != null) {
          return acknowledgementFence;
        }
        telnetProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.TELNET_ROLE,
                telnetProjection.revision(),
                telnet.sourceGeneration(),
                telnet.sourceObjectGeneration(),
                telnet.summary().spkiSha256(),
                () ->
                    assertRuntimeProfileCurrent(
                        plan, acknowledgementProfile, "projection acknowledgement"));
        acknowledgementFence =
            runtimeProfileChangedStatus(
                resource,
                runtimeProfile,
                ingress,
                telnet,
                gatewayInternalWs,
                tcpProxyBridge,
                grpc,
                telnetProjection);
        if (acknowledgementFence != null) {
          return acknowledgementFence;
        }
        gatewayInternalWsProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
                gatewayInternalWsProjection.revision(),
                gatewayInternalWs.sourceGeneration(),
                gatewayInternalWs.sourceObjectGeneration(),
                gatewayInternalWs.summary().spkiSha256(),
                () ->
                    assertRuntimeProfileCurrent(
                        plan, acknowledgementProfile, "projection acknowledgement"));
        acknowledgementFence =
            runtimeProfileChangedStatus(
                resource,
                runtimeProfile,
                ingress,
                telnet,
                gatewayInternalWs,
                tcpProxyBridge,
                grpc,
                gatewayInternalWsProjection);
        if (acknowledgementFence != null) {
          return acknowledgementFence;
        }
        tcpProxyBridgeProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
                tcpProxyBridgeProjection.revision(),
                tcpProxyBridge.sourceGeneration(),
                tcpProxyBridge.sourceObjectGeneration(),
                tcpProxyBridge.summary().spkiSha256(),
                () ->
                    assertRuntimeProfileCurrent(
                        plan, acknowledgementProfile, "projection acknowledgement"));
        acknowledgementFence =
            runtimeProfileChangedStatus(
                resource,
                runtimeProfile,
                ingress,
                telnet,
                gatewayInternalWs,
                tcpProxyBridge,
                grpc,
                tcpProxyBridgeProjection);
        if (acknowledgementFence != null) {
          return acknowledgementFence;
        }
        grpcProjection =
            projectionService.acknowledge(
                client,
                plan,
                HostedIdentityContract.GRPC_ROLE,
                grpcProjection.revision(),
                grpc.sourceGeneration(),
                grpc.sourceObjectGeneration(),
                grpc.summary().spkiSha256(),
                () ->
                    assertRuntimeProfileCurrent(
                        plan, acknowledgementProfile, "projection acknowledgement"));
        acknowledgementFence =
            runtimeProfileChangedStatus(
                resource,
                runtimeProfile,
                ingress,
                telnet,
                gatewayInternalWs,
                tcpProxyBridge,
                grpc,
                grpcProjection);
        if (acknowledgementFence != null) {
          return acknowledgementFence;
        }
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
    } catch (RuntimeProfileFenceException exception) {
      return status(
          resource,
          exception.phase(),
          exception.reason(),
          exception.getMessage(),
          false,
          null,
          null,
          null,
          null);
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

  private record RuntimeProfileValidation(
      RuntimeProfileService.RuntimeProfile profile,
      HostedEnvironmentIdentityStatus.Phase phase,
      String reason,
      String message,
      boolean valid) {}

  private UpdateControl<HostedEnvironmentIdentity> runtimeProfileChangedStatus(
      HostedEnvironmentIdentity resource,
      RuntimeProfileService.RuntimeProfile runtimeProfile,
      CertificateMaterialService.RoleMaterial ingress,
      CertificateMaterialService.RoleMaterial telnet,
      CertificateMaterialService.RoleMaterial gatewayInternalWs,
      CertificateMaterialService.RoleMaterial tcpProxyBridge,
      CertificateMaterialService.RoleMaterial grpc,
      SecretProjectionService.ProjectionResult projection) {
    if (projection == null || !"runtime-profile-changed".equals(projection.state())) {
      return null;
    }
    return status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RuntimeIdentityChanged",
        "runtime profile changed during guarded identity convergence; fresh convergence is required",
        false,
        runtimeProfile,
        ingress,
        telnet,
        gatewayInternalWs,
        tcpProxyBridge,
        grpc);
  }

  SecretProjectionService.ProjectionResult project(
      EnvironmentIdentityPlan plan,
      RuntimeProfileService.RuntimeProfile expectedProfile,
      CertificateMaterialService.RoleMaterial material,
      String role) {
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
          null,
          null,
          null);
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
            null,
            null,
            null);
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
            null,
            null,
            null);
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
            null,
            null,
            null);
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

  private boolean assertRuntimeProfileCurrent(
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
    return true;
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
